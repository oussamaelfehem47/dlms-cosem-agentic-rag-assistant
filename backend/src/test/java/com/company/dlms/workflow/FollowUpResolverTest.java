package com.company.dlms.workflow;

import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.siconia.AffectedComponent;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.AlarmSeverity;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.domain.siconia.SiconiaProcessingMetadata;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.domain.siconia.SiconiaXmlTrace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpResolverTest {

    private final FollowUpResolver resolver = new FollowUpResolver();

    @Test
    void previousFrameTypeQuestionUsesNarrativeContext() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        1,
                        "U_FRAME (SNRM)",
                        "COMPLETE",
                        "CONNECTING",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_FRAME_TYPE);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("U-frame (SNRM)");
        assertThat(resolution.answer()).contains("CONNECTING");
    }

    @Test
    void typoedPreviousFrameTypeQuestionStillUsesNarrativeContext() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "waht was the frame type of the frame I just sent?")
                .toBuilder()
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        1,
                        "U_FRAME (SNRM)",
                        "COMPLETE",
                        "CONNECTING",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_FRAME_TYPE);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("U-frame (SNRM)");
        assertThat(resolution.answer()).contains("CONNECTING");
    }

    @Test
    void meaningQuestionAfterControlFrameUsesDecodeContext() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what does that mean?")
                .toBuilder()
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA00A030383CD6F7E",
                        List.of()
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_MEANING);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("Set Normal Response Mode");
        assertThat(resolution.answer()).contains("HDLC connection establishment");
    }

    @Test
    void failureQuestionAfterInvalidFcsUsesDeterministicFailureReason() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "why did it fail?")
                .toBuilder()
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, false, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA006030363E9737E",
                        List.of()
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_FAILURE_REASON);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("checksum did not match");
        assertThat(resolution.answer()).contains("Re-capture or retransmit");
    }

    @Test
    void meaningQuestionAfterSiconiaAlarmUsesCurrentStructuredResult() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what does that mean?")
                .toBuilder()
                .siconiaResult(new SiconiaResult(
                        null,
                        List.of(new AlarmDecodeResult(
                                "0x1342",
                                AlarmSeverity.HIGH,
                                "SICONIA DCU comm failure",
                                "Check DCU-HES link, verify credentials",
                                AffectedComponent.HES
                        )),
                        null,
                        "ALARM_CODE",
                        new SiconiaProcessingMetadata(null, ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_MEANING);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("Alarm 0x1342 is HIGH on HES");
        assertThat(resolution.answer()).contains("SICONIA DCU comm failure");
    }

    @Test
    void failureQuestionAfterSiconiaXmlWarningUsesCurrentStructuredResult() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "why did it fail?")
                .toBuilder()
                .siconiaResult(new SiconiaResult(
                        new SiconiaXmlTrace(List.of(), List.of("Malformed closing tag"), "<Event></Alarm>"),
                        List.of(),
                        null,
                        "XML_TRACE",
                        new SiconiaProcessingMetadata(null, ParseProvenance.STRUCTURED_HEURISTIC, List.of(), null)
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_FAILURE_REASON);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("Malformed closing tag");
        assertThat(resolution.answer()).contains("XML parser recovered what it could");
    }

    @Test
    void previousFrameTypeQuestionUsesIFrameLabelNotBareApduName() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                        ApduType.AARE,
                        null,
                        List.of(),
                        false,
                        "7EA0",
                        List.of()
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_FRAME_TYPE);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("I-frame (AARE)");
        assertThat(resolution.answer()).doesNotContain("was AARE.");
    }

    @Test
    void previousFrameQuestionWithoutSessionContextReturnsDeterministicNoContextAnswer() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?");

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.resolvedFromContext()).isFalse();
        assertThat(resolution.answer()).contains("do not have previous decoded frame");
    }

    @Test
    void previousFrameQuestionPrefersLastFrameEventOverLaterSiconiaEvent() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .narrativeContext(List.of(
                        new SessionEvent(
                                "s1",
                                Instant.parse("2026-05-30T10:00:00Z"),
                                1,
                                "U_FRAME (SNRM)",
                                "COMPLETE",
                                "CONNECTING",
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new SessionEvent(
                                "s1",
                                Instant.parse("2026-05-30T10:01:00Z"),
                                2,
                                "SICONIA_ALARM_CODE",
                                "SICONIA_ANALYSIS",
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.PREVIOUS_FRAME_TYPE);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("U-frame (SNRM)");
        assertThat(resolution.answer()).doesNotContain("SICONIA_ALARM_CODE");
    }

    @Test
    void connectionRoleQuestionUsesLastSnrmFrameContext() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what type of connection is being established?")
                .toBuilder()
                .associationState("CONNECTING")
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        1,
                        "U_FRAME (SNRM)",
                        "COMPLETE",
                        "CONNECTING",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.CONNECTION_ROLE);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("HDLC link layer");
        assertThat(resolution.answer()).contains("normal response mode");
        assertThat(resolution.answer()).contains("CONNECTING");
        assertThat(resolution.answer()).doesNotContain("TCP");
    }

    @Test
    void typoedConnectionRoleQuestionUsesLastSnrmFrameContext() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what type of conection is being esablished?")
                .toBuilder()
                .associationState("CONNECTING")
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        1,
                        "U_FRAME (SNRM)",
                        "COMPLETE",
                        "CONNECTING",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.CONNECTION_ROLE);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("HDLC link layer");
        assertThat(resolution.answer()).contains("normal response mode");
        assertThat(resolution.answer()).contains("CONNECTING");
    }

    @Test
    void lastObisRecallUsesSessionStateAndBestEffortDescription() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what OBIS code was in the last response?")
                .toBuilder()
                .lastObis("1.0.1.8.0.255")
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        1,
                        "GET_RESPONSE",
                        "COMPLETE",
                        "ASSOCIATED",
                        "1.0.1.8.0.255",
                        "3",
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.LAST_ENTITY_RECALL);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("1.0.1.8.0.255");
        assertThat(resolution.answer().toLowerCase()).contains("active energy import");
    }

    @Test
    void typoedLastObisRecallUsesSessionStateAndBestEffortDescription() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "hat OBIS code was in the last repsonse?")
                .toBuilder()
                .lastObis("1.0.1.8.0.255")
                .narrativeContext(List.of(new SessionEvent(
                        "s1",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        1,
                        "GET_RESPONSE",
                        "COMPLETE",
                        "ASSOCIATED",
                        "1.0.1.8.0.255",
                        "3",
                        List.of(),
                        List.of(),
                        List.of()
                )))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.LAST_ENTITY_RECALL);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("1.0.1.8.0.255");
        assertThat(resolution.answer().toLowerCase()).contains("active energy import");
    }

    @Test
    void lastObisRecallPrefersExactDescriptionFromStoredArtifactResults() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what OBIS code was in the last response?")
                .toBuilder()
                .lastObis("1.0.1.8.0.255")
                .recentArtifactResults(List.of(
                        new ArtifactResultPayload(
                                "artifact-3",
                                2,
                                ArtifactSource.PASTED_BLOCK,
                                null,
                                "C4020109060100010800FF",
                                com.company.dlms.domain.InputClass.QUERY,
                                com.company.dlms.domain.DlmsIntent.APDU_ANALYSIS,
                                java.util.Map.of(
                                        "apduType", "GET_RESPONSE",
                                        "obisResolutions", List.of(java.util.Map.of(
                                                "obis", "1.0.1.8.0.255",
                                                "description", "Active energy import total"
                                        ))
                                ),
                                null,
                                "What it means: The payload decodes deterministically as GET_RESPONSE.",
                                null,
                                com.company.dlms.domain.orchestration.OrchestrationMode.DETERMINISTIC_FAST_PATH,
                                false,
                                List.of(),
                                null,
                                null,
                                null
                        )
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.LAST_ENTITY_RECALL);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("1.0.1.8.0.255");
        assertThat(resolution.answer()).contains("Active energy import total");
        assertThat(resolution.answer()).doesNotContain("Electricity active energy import total");
    }

    @Test
    void payloadBearingApduPromptIsNotTreatedAsSessionFollowUp() {
        String prompt = "Decode APDU C4020109060100010800FF and explain what object was returned.";

        assertThat(resolver.isFollowUpQuestion(prompt)).isFalse();

        WorkflowState state = WorkflowState.empty("s1", "c1", prompt)
                .toBuilder()
                .lastObis("1.0.1.8.0.255")
                .build();

        assertThat(resolver.resolve(state)).isEmpty();
    }

    @Test
    void artifactFollowUpUsesStoredBatchArtifactContext() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "explain artifact 2")
                .toBuilder()
                .recentArtifactResults(List.of(
                        new ArtifactResultPayload(
                                "artifact-1",
                                0,
                                ArtifactSource.PASTED_BLOCK,
                                null,
                                "7EA00A030383CD6F7E",
                                com.company.dlms.domain.InputClass.HEX_FRAME,
                                com.company.dlms.domain.DlmsIntent.FRAME_DECODE,
                                java.util.Map.of(
                                        "hdlcFrame", java.util.Map.of(
                                                "frameType", "U_FRAME",
                                                "uFrameType", "SNRM"
                                        )
                                ),
                                null,
                                "What it means: This is an HDLC Set Normal Response Mode control frame.",
                                null,
                                com.company.dlms.domain.orchestration.OrchestrationMode.DETERMINISTIC_FAST_PATH,
                                false,
                                List.of(),
                                null,
                                null,
                                null
                        ),
                        new ArtifactResultPayload(
                                "artifact-2",
                                1,
                                ArtifactSource.PASTED_BLOCK,
                                null,
                                "03 01",
                                com.company.dlms.domain.InputClass.QUERY,
                                com.company.dlms.domain.DlmsIntent.APDU_ANALYSIS,
                                java.util.Map.of(
                                        "axdrTree", java.util.Map.of(
                                                "type", "boolean",
                                                "value", true
                                        )
                                ),
                                null,
                                "What it means: The payload decodes as AXDR boolean true in raw AXDR form without an APDU or HDLC envelope.",
                                null,
                                com.company.dlms.domain.orchestration.OrchestrationMode.DETERMINISTIC_FAST_PATH,
                                false,
                                List.of(),
                                null,
                                null,
                                null
                        )
                ))
                .build();

        FollowUpResolver.FollowUpResolution resolution = resolver.resolve(state).orElseThrow();

        assertThat(resolution.kind()).isEqualTo(FollowUpResolver.FollowUpKind.ARTIFACT_RECALL);
        assertThat(resolution.resolvedFromContext()).isTrue();
        assertThat(resolution.answer()).contains("Artifact 2");
        assertThat(resolution.answer()).contains("AXDR boolean `true`");
        assertThat(resolution.answer()).contains("stored structured result");
        assertThat(resolution.answer()).contains("The payload decodes as AXDR boolean true");
    }
}
