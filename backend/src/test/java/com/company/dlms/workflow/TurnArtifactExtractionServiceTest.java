package com.company.dlms.workflow;

import com.company.dlms.agent.dlms.DlmsInputNormalizer;
import com.company.dlms.agent.siconia.SiconiaInputNormalizer;
import com.company.dlms.domain.InputClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnArtifactExtractionServiceTest {

    private final TurnArtifactExtractionService service = new TurnArtifactExtractionService(
            new DlmsInputNormalizer(),
            new SiconiaInputNormalizer()
    );

    @Test
    void explicitArtifactsArePreservedAndInstructionIsSeparated() {
        WorkflowRequest request = new WorkflowRequest(
                "session-1",
                "conversation-1",
                """
                [Attachment 1: frame.hex]
                7EA00A030383CD6F7E

                [Attachment 2: apdu.hex]
                C4020109060100010800FF

                [User context]
                Decode these and compare them.
                """,
                List.of(
                        new TurnArtifact("a1", ArtifactSource.ATTACHMENT, "frame.hex", "7EA00A030383CD6F7E", InputClass.HEX_FRAME, "decode"),
                        new TurnArtifact("a2", ArtifactSource.ATTACHMENT, "apdu.hex", "C4020109060100010800FF", InputClass.QUERY, "decode")
                ),
                "ENGINEER",
                InputClass.QUERY
        );

        TurnArtifactExtraction extraction = service.extract(request);

        assertThat(extraction.explicitArtifacts()).isTrue();
        assertThat(extraction.tooManyArtifacts()).isFalse();
        assertThat(extraction.artifacts()).hasSize(2);
        assertThat(extraction.artifacts().getFirst().filename()).isEqualTo("frame.hex");
        assertThat(extraction.turnInstruction()).isEqualTo("Decode these and compare them.");
    }

    @Test
    void pastedBlocksBecomeMultipleArtifactsAndProseRemainsInstruction() {
        WorkflowRequest request = new WorkflowRequest(
                "session-2",
                "conversation-2",
                """
                Decode these and compare them.

                7EA00A030383CD6F7E

                03 01

                C4020109060100010800FF
                """,
                "ENGINEER",
                InputClass.QUERY
        );

        TurnArtifactExtraction extraction = service.extract(request);

        assertThat(extraction.explicitArtifacts()).isFalse();
        assertThat(extraction.artifacts()).hasSize(3);
        assertThat(extraction.turnInstruction()).isEqualTo("Decode these and compare them.");
        assertThat(extraction.artifacts().get(0).text()).isEqualTo("7EA00A030383CD6F7E");
        assertThat(extraction.artifacts().get(1).text()).isEqualTo("03 01");
        assertThat(extraction.artifacts().get(2).text()).isEqualTo("C4020109060100010800FF");
    }

    @Test
    void mixedMultilinePromptStillSeparatesInstructionFromStructuredArtifacts() {
        WorkflowRequest request = new WorkflowRequest(
                "session-mixed",
                "conversation-mixed",
                """
                Compare these in context:
                7EA00A030383CD6F7E
                03 01
                C4020109060100010800FF
                """,
                "ENGINEER",
                InputClass.QUERY
        );

        TurnArtifactExtraction extraction = service.extract(request);

        assertThat(extraction.artifacts()).hasSize(3);
        assertThat(extraction.turnInstruction()).isEqualTo("Compare these in context:");
        assertThat(extraction.artifacts().get(0).text()).isEqualTo("7EA00A030383CD6F7E");
        assertThat(extraction.artifacts().get(1).text()).isEqualTo("03 01");
        assertThat(extraction.artifacts().get(2).text()).isEqualTo("C4020109060100010800FF");
    }

    @Test
    void moreThanEightArtifactsTriggersSafeLimit() {
        List<TurnArtifact> artifacts = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> new TurnArtifact(
                        "artifact-" + index,
                        ArtifactSource.ATTACHMENT,
                        "file-" + index + ".txt",
                        "7EA00A030383CD6F7E",
                        InputClass.HEX_FRAME,
                        "decode"
                ))
                .toList();

        WorkflowRequest request = new WorkflowRequest(
                "session-3",
                "conversation-3",
                "Batch decode",
                artifacts,
                "ENGINEER",
                InputClass.QUERY
        );

        TurnArtifactExtraction extraction = service.extract(request);

        assertThat(extraction.tooManyArtifacts()).isTrue();
        assertThat(extraction.artifacts()).hasSize(TurnArtifactExtractionService.MAX_ARTIFACTS);
    }
}
