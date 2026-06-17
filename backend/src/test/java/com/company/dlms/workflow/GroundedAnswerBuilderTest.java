package com.company.dlms.workflow;

import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.answer.AnswerMode;
import com.company.dlms.domain.answer.GroundedAnswerContext;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.DlmsProcessingMetadata;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.orchestration.StrategyCandidate;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.domain.siconia.AffectedComponent;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.AlarmSeverity;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.domain.siconia.SiconiaProcessingMetadata;
import com.company.dlms.domain.siconia.SiconiaResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedAnswerBuilderTest {

    private final GroundedAnswerBuilder groundedAnswerBuilder = new GroundedAnswerBuilder(new FollowUpResolver());

    @Test
    void groundedContextRepresentsDeterministicHdlcDecode() {
        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(
                        FrameType.U_FRAME,
                        UFrameType.SNRM,
                        null,
                        16,
                        1,
                        new byte[0],
                        true,
                        new byte[]{0x7E}
                ),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A0000007E",
                List.of(),
                new DlmsProcessingMetadata(
                        DlmsNormalizedKind.FRAME_HEX,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of("FCS verified"),
                        null
                )
        );

        GroundedAnswerContext context = GroundedAnswerContext.deterministicDecode(
                StrategyKey.DLMS_FRAME_DECODE,
                decodeResult,
                List.of("deterministic parser"),
                List.of(),
                0.99,
                false
        );

        assertThat(context.mode()).isEqualTo(AnswerMode.DETERMINISTIC_DECODE);
        assertThat(context.selectedStrategy()).isEqualTo(StrategyKey.DLMS_FRAME_DECODE);
        assertThat(context.decodeResult()).isEqualTo(decodeResult);
        assertThat(context.siconiaResult()).isNull();
        assertThat(context.warnings()).containsExactly("deterministic parser");
        assertThat(context.tentative()).isFalse();
    }

    @Test
    void groundedContextRepresentsDeterministicSiconiaAlarm() {
        SiconiaResult siconiaResult = new SiconiaResult(
                null,
                List.of(new AlarmDecodeResult(
                        "1200",
                        AlarmSeverity.CRITICAL,
                        "Power failure",
                        "Inspect DCU power feed",
                        AffectedComponent.HES
                )),
                null,
                "ALARM_CODE",
                new SiconiaProcessingMetadata(
                        com.company.dlms.domain.InputClass.QUERY,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        "alarm code extracted directly"
                )
        );

        GroundedAnswerContext context = GroundedAnswerContext.deterministicSiconia(
                StrategyKey.SICONIA_ALARM_ANALYSIS,
                siconiaResult,
                List.of(),
                List.of("power-loss-pattern"),
                0.97,
                false
        );

        assertThat(context.mode()).isEqualTo(AnswerMode.DETERMINISTIC_SICONIA);
        assertThat(context.selectedStrategy()).isEqualTo(StrategyKey.SICONIA_ALARM_ANALYSIS);
        assertThat(context.siconiaResult()).isEqualTo(siconiaResult);
        assertThat(context.decodeResult()).isNull();
        assertThat(context.anomalies()).containsExactly("power-loss-pattern");
    }

    @Test
    void groundedContextRepresentsRetrievalBackedDocumentation() {
        RetrievalResult retrievalResult = new RetrievalResult(
                new DocumentChunk(
                        "doc-1",
                        "HDLC frame structure overview",
                        new SourceCitation(
                                "dlms",
                                "blue-book.pdf",
                                12,
                                "General",
                                "HDLC frame structure",
                                null,
                                0.91,
                                "DLMS Blue Book - HDLC frame structure"
                        )
                ),
                0.91,
                0.88,
                0.75
        );

        GroundedAnswerContext context = GroundedAnswerContext.retrievalDocs(
                List.of(retrievalResult),
                List.of("cite authoritative docs"),
                0.86
        );

        assertThat(context.mode()).isEqualTo(AnswerMode.RETRIEVAL_DOCS);
        assertThat(context.selectedStrategy()).isEqualTo(StrategyKey.DOCUMENTATION);
        assertThat(context.retrievalResults()).containsExactly(retrievalResult);
        assertThat(context.confidence()).isEqualTo(0.86);
    }

    @Test
    void groundedContextRepresentsSessionRecall() {
        StmSnapshot stmSnapshot = new StmSnapshot(7L, 1, "ASSOCIATED", 1024);
        SessionEvent sessionEvent = new SessionEvent(
                "s1",
                Instant.parse("2026-05-26T15:00:00Z"),
                7,
                "U_FRAME (SNRM)",
                "COMPLETE",
                "ASSOCIATING",
                null,
                null,
                List.of(),
                List.of("previous frame remembered"),
                List.of()
        );

        GroundedAnswerContext context = GroundedAnswerContext.sessionRecall(
                StrategyKey.DLMS_FRAME_DECODE,
                stmSnapshot,
                List.of(sessionEvent),
                List.of("session-derived"),
                0.72,
                true
        );

        WorkflowState updatedState = WorkflowState.empty("s1", "c1", "what was the previous frame?")
                .toBuilder()
                .groundedAnswerContext(context)
                .build();

        assertThat(updatedState.groundedAnswerContext()).isEqualTo(context);
        assertThat(updatedState.toBuilder().build().groundedAnswerContext()).isEqualTo(context);
        assertThat(updatedState.stmSnapshot()).isEqualTo(stmSnapshot);
        assertThat(updatedState.narrativeContext()).containsExactly(sessionEvent);
        assertThat(updatedState.retrievalResults()).isEmpty();
        assertThat(updatedState.decodeResult()).isNull();
        assertThat(context.stmSnapshot()).isEqualTo(stmSnapshot);
        assertThat(context.narrativeContext()).containsExactly(sessionEvent);
        assertThat(context.tentative()).isTrue();
    }

    @Test
    void groundedContextRepresentsAmbiguityWithCandidates() {
        StrategyCandidate frameCandidate = new StrategyCandidate(
                StrategyKey.DLMS_FRAME_DECODE,
                "HDLC frame decode",
                0.52,
                "Looks like framed hex",
                true,
                false,
                "QUERY",
                "FRAME_HEX",
                "STRUCTURED_HEURISTIC",
                "7EA0...",
                List.of()
        );
        StrategyCandidate docCandidate = new StrategyCandidate(
                StrategyKey.DOCUMENTATION,
                "Documentation answer",
                0.48,
                "Could also be a conceptual question",
                false,
                true,
                "QUERY",
                null,
                "RAW_FALLBACK",
                "Explain 7EA0...",
                List.of("close scores")
        );

        GroundedAnswerContext context = GroundedAnswerContext.ambiguous(
                List.of(frameCandidate, docCandidate),
                List.of("multiple plausible interpretations"),
                0.52
        );

        assertThat(context.mode()).isEqualTo(AnswerMode.AMBIGUOUS);
        assertThat(context.selectedStrategy()).isEqualTo(StrategyKey.UNKNOWN);
        assertThat(context.ambiguityCandidates()).containsExactly(frameCandidate, docCandidate);
        assertThat(context.tentative()).isTrue();
    }

    @Test
    void workflowStateExplicitDecodeOverridesAlsoUpdateGroundedContext() {
        DecodeResult initialDecode = deterministicDecode("7EA00A0000007E");
        DecodeResult updatedDecode = deterministicDecode("7EA00A030383CD6F7E");

        GroundedAnswerContext context = GroundedAnswerContext.deterministicDecode(
                StrategyKey.DLMS_FRAME_DECODE,
                initialDecode,
                List.of("initial"),
                List.of(),
                0.92,
                false
        );

        WorkflowState updatedState = WorkflowState.empty("s1", "c1", "decode this")
                .withGroundedAnswerContext(context)
                .toBuilder()
                .decodeResult(updatedDecode)
                .build();

        assertThat(updatedState.decodeResult()).isEqualTo(updatedDecode);
        assertThat(updatedState.groundedAnswerContext().decodeResult()).isEqualTo(updatedDecode);
        assertThat(updatedState.groundedAnswerContext().warnings()).containsExactly("initial");
    }

    @Test
    void workflowStateExplicitSiconiaOverrideAlsoUpdatesGroundedContext() {
        SiconiaResult initialResult = singleAlarmResult("0x0001", "Power failure");
        SiconiaResult updatedResult = singleAlarmResult("0x1342", "SICONIA DCU comm failure");

        GroundedAnswerContext context = GroundedAnswerContext.deterministicSiconia(
                StrategyKey.SICONIA_ALARM_ANALYSIS,
                initialResult,
                List.of("initial"),
                List.of("alarm"),
                0.91,
                false
        );

        WorkflowState updatedState = WorkflowState.empty("s1", "c1", "0x1342")
                .withGroundedAnswerContext(context)
                .toBuilder()
                .siconiaResult(updatedResult)
                .build();

        assertThat(updatedState.siconiaResult()).isEqualTo(updatedResult);
        assertThat(updatedState.groundedAnswerContext().siconiaResult()).isEqualTo(updatedResult);
    }

    @Test
    void workflowStateExplicitRetrievalOverrideAlsoUpdatesGroundedContext() {
        RetrievalResult initialResult = retrievalResult("doc-1", "Initial doc");
        RetrievalResult updatedResult = retrievalResult("doc-2", "Updated doc");

        GroundedAnswerContext context = GroundedAnswerContext.retrievalDocs(
                List.of(initialResult),
                List.of("cite docs"),
                0.83
        );

        WorkflowState updatedState = WorkflowState.empty("s1", "c1", "what is hdlc?")
                .withGroundedAnswerContext(context)
                .toBuilder()
                .retrievalResults(List.of(updatedResult))
                .build();

        assertThat(updatedState.retrievalResults()).containsExactly(updatedResult);
        assertThat(updatedState.groundedAnswerContext().retrievalResults()).containsExactly(updatedResult);
    }

    @Test
    void workflowStateExplicitSessionRecallOverridesAlsoUpdateGroundedContext() {
        GroundedAnswerContext context = GroundedAnswerContext.sessionRecall(
                StrategyKey.DLMS_FRAME_DECODE,
                new StmSnapshot(2L, 1, "ASSOCIATING", 1024),
                List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-26T15:00:00Z"),
                        2,
                        "U_FRAME (SNRM)",
                        "COMPLETE",
                        "ASSOCIATING",
                        null,
                        null,
                        List.of(),
                        List.of("initial"),
                        List.of()
                )),
                List.of("session-derived"),
                0.72,
                true
        );

        StmSnapshot updatedSnapshot = new StmSnapshot(3L, 2, "ASSOCIATED", 2048);
        SessionEvent updatedEvent = new SessionEvent(
                "s1",
                Instant.parse("2026-05-26T15:05:00Z"),
                3,
                "AARE",
                "COMPLETE",
                "ASSOCIATED",
                null,
                null,
                List.of(),
                List.of("updated"),
                List.of()
        );

        WorkflowState updatedState = WorkflowState.empty("s1", "c1", "what happened?")
                .withGroundedAnswerContext(context)
                .toBuilder()
                .stmSnapshot(updatedSnapshot)
                .narrativeContext(List.of(updatedEvent))
                .build();

        assertThat(updatedState.stmSnapshot()).isEqualTo(updatedSnapshot);
        assertThat(updatedState.narrativeContext()).containsExactly(updatedEvent);
        assertThat(updatedState.groundedAnswerContext().stmSnapshot()).isEqualTo(updatedSnapshot);
        assertThat(updatedState.groundedAnswerContext().narrativeContext()).containsExactly(updatedEvent);
    }

    @Test
    void workflowStateInvalidOverrideCombinationFailsFast() {
        GroundedAnswerContext context = GroundedAnswerContext.deterministicDecode(
                StrategyKey.DLMS_FRAME_DECODE,
                deterministicDecode("7EA00A0000007E"),
                List.of("initial"),
                List.of(),
                0.92,
                false
        );

        assertThatThrownBy(() -> WorkflowState.empty("s1", "c1", "invalid")
                .withGroundedAnswerContext(context)
                .toBuilder()
                .siconiaResult(singleAlarmResult("0x0001", "Power failure"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("siconiaResult must be null");
    }

    @Test
    void groundedContextDefensivelyCopiesMutableLists() {
        List<String> warnings = new ArrayList<>(List.of("first warning"));
        List<String> anomalies = new ArrayList<>(List.of("first anomaly"));

        GroundedAnswerContext context = GroundedAnswerContext.failure(
                StrategyKey.UNKNOWN,
                warnings,
                anomalies,
                0.2,
                true
        );

        warnings.add("mutated warning");
        anomalies.clear();

        assertThat(context.warnings()).containsExactly("first warning");
        assertThat(context.anomalies()).containsExactly("first anomaly");
    }

    @Test
    void groundedContextRejectsInvalidDeterministicDecodeConstruction() {
        assertThatThrownBy(() -> new GroundedAnswerContext(
                AnswerMode.DETERMINISTIC_DECODE,
                StrategyKey.DLMS_FRAME_DECODE,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                0.9,
                false
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decodeResult is required");
    }

    @Test
    void groundedContextRejectsEmptyAmbiguityCandidates() {
        assertThatThrownBy(() -> GroundedAnswerContext.ambiguous(List.of(), List.of("ambiguous"), 0.3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguityCandidates are required");
    }

    @Test
    void sameAxdrPayloadPhrasedThreeWaysProducesSameAnswerMode() {
        WorkflowState barePayload = WorkflowState.empty("s1", "c1", "1907E80416010E1E0000003C00")
                .toBuilder()
                .strategyMetadata(new com.company.dlms.domain.orchestration.StrategyMetadata(
                        StrategyKey.DLMS_AXDR_DECODE,
                        StrategyKey.DLMS_AXDR_DECODE.label(),
                        0.95,
                        false,
                        false,
                        List.of(),
                        List.of()
                ))
                .decodeResult(directAxdrDecode("1907E80416010E1E0000003C00", com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT))
                .build();
        WorkflowState explicitPrompt = WorkflowState.empty("s1", "c1", "Explain AXDR payload 1907E80416010E1E0000003C00")
                .toBuilder()
                .strategyMetadata(new com.company.dlms.domain.orchestration.StrategyMetadata(
                        StrategyKey.DLMS_AXDR_DECODE,
                        StrategyKey.DLMS_AXDR_DECODE.label(),
                        0.91,
                        false,
                        true,
                        List.of(),
                        List.of("Recovered AXDR payload from wrapped prose input")
                ))
                .decodeResult(directAxdrDecode("1907E80416010E1E0000003C00", com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_HEURISTIC))
                .build();
        WorkflowState wrappedPrompt = WorkflowState.empty("s1", "c1", "payload 1907E80416010E1E0000003C00")
                .toBuilder()
                .strategyMetadata(new com.company.dlms.domain.orchestration.StrategyMetadata(
                        StrategyKey.DLMS_AXDR_DECODE,
                        StrategyKey.DLMS_AXDR_DECODE.label(),
                        0.89,
                        false,
                        true,
                        List.of(),
                        List.of("Recovered AXDR payload from wrapped prose input")
                ))
                .decodeResult(directAxdrDecode("1907E80416010E1E0000003C00", com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_HEURISTIC))
                .build();

        assertThat(groundedAnswerBuilder.build(barePayload).mode()).isEqualTo(AnswerMode.DETERMINISTIC_DECODE);
        assertThat(groundedAnswerBuilder.build(explicitPrompt).mode()).isEqualTo(AnswerMode.DETERMINISTIC_DECODE);
        assertThat(groundedAnswerBuilder.build(wrappedPrompt).mode()).isEqualTo(AnswerMode.DETERMINISTIC_DECODE);
    }

    @Test
    void directAlarmInputsProduceDeterministicSiconiaMode() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "0x1342")
                .toBuilder()
                .strategyMetadata(new com.company.dlms.domain.orchestration.StrategyMetadata(
                        StrategyKey.SICONIA_ALARM_ANALYSIS,
                        StrategyKey.SICONIA_ALARM_ANALYSIS.label(),
                        0.97,
                        false,
                        false,
                        List.of(),
                        List.of()
                ))
                .siconiaResult(singleAlarmResult("0x1342", "SICONIA DCU comm failure"))
                .build();

        GroundedAnswerContext context = groundedAnswerBuilder.build(state);

        assertThat(context.mode()).isEqualTo(AnswerMode.DETERMINISTIC_SICONIA);
        assertThat(context.selectedStrategy()).isEqualTo(StrategyKey.SICONIA_ALARM_ANALYSIS);
    }

    @Test
    void previousFrameQuestionsProduceSessionRecallModeWhenStateExists() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .strategyMetadata(new com.company.dlms.domain.orchestration.StrategyMetadata(
                        StrategyKey.UNKNOWN,
                        StrategyKey.UNKNOWN.label(),
                        0.72,
                        false,
                        false,
                        List.of(),
                        List.of()
                ))
                .stmSnapshot(new StmSnapshot(2L, 1, "ASSOCIATING", 1024))
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-26T15:00:00Z"),
                        2,
                        "U_FRAME (SNRM)",
                        "COMPLETE",
                        "ASSOCIATING",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        GroundedAnswerContext context = groundedAnswerBuilder.build(state);

        assertThat(context.mode()).isEqualTo(AnswerMode.SESSION_RECALL);
        assertThat(context.stmSnapshot()).isNotNull();
        assertThat(context.narrativeContext()).hasSize(1);
    }

    @Test
    void siconiaMeaningFollowUpKeepsDeterministicSiconiaModeWithoutNarrativeState() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what does that mean?")
                .toBuilder()
                .siconiaResult(singleAlarmResult("0x1342", "SICONIA DCU comm failure"))
                .build();

        GroundedAnswerContext context = groundedAnswerBuilder.build(state);

        assertThat(context.mode()).isEqualTo(AnswerMode.DETERMINISTIC_SICONIA);
        assertThat(context.selectedStrategy()).isEqualTo(StrategyKey.SICONIA_ALARM_ANALYSIS);
        assertThat(context.siconiaResult()).isNotNull();
    }

    private static DecodeResult deterministicDecode(String rawHex) {
        return new DecodeResult(
                new HdlcFrame(
                        FrameType.U_FRAME,
                        UFrameType.SNRM,
                        null,
                        16,
                        1,
                        new byte[0],
                        true,
                        new byte[]{0x7E}
                ),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                rawHex,
                List.of(),
                new DlmsProcessingMetadata(
                        DlmsNormalizedKind.FRAME_HEX,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of("FCS verified"),
                        null
                )
        );
    }

    private static DecodeResult directAxdrDecode(String rawHex, ParseProvenance provenance) {
        return new DecodeResult(
                null,
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                rawHex,
                List.of(),
                new DlmsProcessingMetadata(
                        DlmsNormalizedKind.AXDR_HEX,
                        provenance,
                        List.of(),
                        provenance == ParseProvenance.STRUCTURED_HEURISTIC
                                ? "Recovered AXDR payload from wrapped prose input"
                                : null
                )
        );
    }

    private static SiconiaResult singleAlarmResult(String code, String rootCause) {
        return new SiconiaResult(
                null,
                List.of(new AlarmDecodeResult(
                        code,
                        AlarmSeverity.MEDIUM,
                        rootCause,
                        "Check related system",
                        AffectedComponent.METER
                )),
                null,
                "ALARM_CODE",
                new SiconiaProcessingMetadata(
                        com.company.dlms.domain.InputClass.QUERY,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        "alarm extracted directly"
                )
        );
    }

    private static RetrievalResult retrievalResult(String id, String content) {
        return new RetrievalResult(
                new DocumentChunk(
                        id,
                        content,
                        new SourceCitation(
                                "dlms",
                                "blue-book.pdf",
                                12,
                                "General",
                                "Reference",
                                null,
                                0.91,
                                "DLMS Blue Book - Reference"
                        )
                ),
                0.91,
                0.88,
                0.75
        );
    }
}
