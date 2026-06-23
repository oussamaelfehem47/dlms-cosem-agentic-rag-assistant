package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrBoolean;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.DlmsProcessingMetadata;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.infrastructure.llm.OllamaStreamingClient;
import com.company.dlms.infrastructure.security.OutputFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TurnSynthesisPlannerServiceTest {

    private final OllamaStreamingClient ollamaStreamingClient = mock(OllamaStreamingClient.class);
    private final TurnSynthesisPlannerService service = new TurnSynthesisPlannerService(
            ollamaStreamingClient,
            new OutputFilter()
    );

    @Test
    void deterministicBatchSummaryUsesStructuredArtifactLabels() {
        TurnArtifactExtraction extraction = new TurnArtifactExtraction(
                List.of(
                        new TurnArtifact("artifact-1", ArtifactSource.PASTED_BLOCK, null, "7EA00A030383CD6F7E", InputClass.HEX_FRAME, null),
                        new TurnArtifact("artifact-2", ArtifactSource.PASTED_BLOCK, null, "03 01", InputClass.QUERY, null),
                        new TurnArtifact("artifact-3", ArtifactSource.PASTED_BLOCK, null, "C4020109060100010800FF", InputClass.QUERY, null)
                ),
                null,
                false,
                false
        );

        WorkflowState snrm = WorkflowState.empty("s1", "c1", "7EA00A030383CD6F7E")
                .toBuilder()
                .inputClass(InputClass.HEX_FRAME)
                .intent(DlmsIntent.FRAME_DECODE)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA00A030383CD6F7E",
                        List.of(),
                        new DlmsProcessingMetadata(DlmsNormalizedKind.FRAME_HEX, com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        WorkflowState axdr = WorkflowState.empty("s1", "c1", "03 01")
                .toBuilder()
                .inputClass(InputClass.QUERY)
                .intent(DlmsIntent.APDU_ANALYSIS)
                .decodeResult(new DecodeResult(
                        null,
                        ApduType.UNKNOWN,
                        new AxdrBoolean(true),
                        List.of(),
                        false,
                        "0301",
                        List.of(),
                        new DlmsProcessingMetadata(DlmsNormalizedKind.AXDR_HEX, com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        WorkflowState getResponse = WorkflowState.empty("s1", "c1", "C4020109060100010800FF")
                .toBuilder()
                .inputClass(InputClass.QUERY)
                .intent(DlmsIntent.APDU_ANALYSIS)
                .decodeResult(new DecodeResult(
                        null,
                        ApduType.GET_RESPONSE,
                        null,
                        List.of(new ObisResolution(
                                "1.0.1.8.0.255",
                                "Active energy import total",
                                3,
                                "Wh",
                                -3,
                                ResolutionTier.KG
                        )),
                        false,
                        "C4020109060100010800FF",
                        List.of(),
                        new DlmsProcessingMetadata(DlmsNormalizedKind.APDU_HEX, com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        TurnSynthesisPlannerService.TurnSynthesisResult summary = service.synthesize(extraction, List.of(snrm, axdr, getResponse)).block();

        assertThat(summary.text())
                .contains("I analyzed 3 artifacts:")
                .contains("- Artifact 1: U_FRAME (SNRM)")
                .contains("- Artifact 2: AXDR boolean `true`")
                .contains("- Artifact 3: GET_RESPONSE for OBIS `1.0.1.8.0.255`");
        assertThat(summary.plannerUsed()).isFalse();
        verifyNoInteractions(ollamaStreamingClient);
    }

    @Test
    void compareInstructionUsesDeterministicCrossArtifactSummary() {
        TurnArtifactExtraction extraction = new TurnArtifactExtraction(
                List.of(
                        new TurnArtifact("artifact-1", ArtifactSource.PASTED_BLOCK, null, "7EA00A030383CD6F7E", InputClass.HEX_FRAME, null),
                        new TurnArtifact("artifact-2", ArtifactSource.PASTED_BLOCK, null, "03 01", InputClass.QUERY, null),
                        new TurnArtifact("artifact-3", ArtifactSource.PASTED_BLOCK, null, "C4020109060100010800FF", InputClass.QUERY, null)
                ),
                "Compare these in context.",
                false,
                false
        );

        WorkflowState snrm = WorkflowState.empty("s1", "c1", "7EA00A030383CD6F7E")
                .toBuilder()
                .inputClass(InputClass.HEX_FRAME)
                .intent(DlmsIntent.FRAME_DECODE)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA00A030383CD6F7E",
                        List.of(),
                        new DlmsProcessingMetadata(DlmsNormalizedKind.FRAME_HEX, com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        WorkflowState axdr = WorkflowState.empty("s1", "c1", "03 01")
                .toBuilder()
                .inputClass(InputClass.QUERY)
                .intent(DlmsIntent.APDU_ANALYSIS)
                .decodeResult(new DecodeResult(
                        null,
                        ApduType.UNKNOWN,
                        new AxdrBoolean(true),
                        List.of(),
                        false,
                        "0301",
                        List.of(),
                        new DlmsProcessingMetadata(DlmsNormalizedKind.AXDR_HEX, com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        WorkflowState getResponse = WorkflowState.empty("s1", "c1", "C4020109060100010800FF")
                .toBuilder()
                .inputClass(InputClass.QUERY)
                .intent(DlmsIntent.APDU_ANALYSIS)
                .decodeResult(new DecodeResult(
                        null,
                        ApduType.GET_RESPONSE,
                        null,
                        List.of(new ObisResolution(
                                "1.0.1.8.0.255",
                                "Active energy import total",
                                3,
                                "Wh",
                                -3,
                                ResolutionTier.KG
                        )),
                        false,
                        "C4020109060100010800FF",
                        List.of(),
                        new DlmsProcessingMetadata(DlmsNormalizedKind.APDU_HEX, com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        TurnSynthesisPlannerService.TurnSynthesisResult summary = service.synthesize(extraction, List.of(snrm, axdr, getResponse)).block();

        assertThat(summary.text())
                .contains("These artifacts are related")
                .contains("Artifact 1: U_FRAME (SNRM)")
                .contains("Artifact 2: AXDR boolean `true`")
                .contains("Artifact 3: GET_RESPONSE for OBIS `1.0.1.8.0.255`")
                .contains("HDLC")
                .contains("AXDR")
                .contains("application-layer");
        assertThat(summary.plannerUsed()).isFalse();
        verifyNoInteractions(ollamaStreamingClient);
    }
}
