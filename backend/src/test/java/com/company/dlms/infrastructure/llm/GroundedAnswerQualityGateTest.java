package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.answer.AnswerTopicFamily;
import com.company.dlms.domain.answer.GroundedFactBundle;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedAnswerQualityGateTest {

    @Test
    void usesGroundedFallbackWhenDocsAnswerStartsWithQuotedDocumentFragment() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("qwen2.5:3b");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s1", "c1", "What is the difference between AARQ and AARE in DLMS?"),
                new GroundedFactBundle(
                        AnswerTopicFamily.ASSOCIATION_APDUS,
                        "Association APDUs",
                        "",
                        List.of("AARQ is request", "AARE is response")
                ),
                "\"This Clause contains examples of encoding the AARQ and AARE APDUs.\" In DLMS/COSEM, AARQ is the Association Request APDU."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isIn("quoted document fragment opening", "weak synthesis model prefers grounded family summary");
    }

    @Test
    void acceptsCleanDocsAnswerWhenQuotedFragmentIsAbsent() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s2", "c2", "What is the DLMS Green Book?"),
                new GroundedFactBundle(
                        AnswerTopicFamily.STANDARDS_BOOK,
                        "DLMS Green Book",
                        "",
                        List.of("Green Book defines architecture")
                ),
                "The DLMS Green Book defines the DLMS/COSEM architecture, terminology, and communication profiles. It complements the Blue Book."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.ACCEPT_GENERATED);
    }

    @Test
    void prefersGroundedFallbackForWeakQwenModelEvenWhenDocsAnswerIsClean() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("qwen2.5:3b");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s2b", "c2b", "What is the DLMS Green Book?"),
                new GroundedFactBundle(
                        AnswerTopicFamily.STANDARDS_BOOK,
                        "DLMS Green Book",
                        "",
                        List.of("Green Book defines architecture")
                ),
                "The DLMS Green Book defines the DLMS/COSEM architecture, terminology, and communication profiles. It complements the Blue Book."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("weak synthesis model prefers grounded family summary");
    }

    @Test
    void usesGroundedFallbackWhenDocsAnswerStartsWithQuotedHeadingLikeFragment() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("qwen2.5:3b");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s3", "c3", "What is the DLMS Green Book?"),
                new GroundedFactBundle(
                        AnswerTopicFamily.STANDARDS_BOOK,
                        "DLMS Green Book",
                        "",
                        List.of("Green Book defines architecture")
                ),
                "\"10 | DLMS/COSEM Architecture and Protocols, the \\\"Green Book\\\" Edition 10.\" The DLMS Green Book defines the architecture."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isIn("quoted document fragment opening", "weak synthesis model prefers grounded family summary");
    }

    @Test
    void usesGroundedFallbackWhenSecurityAnswerStartsWithQuotedBibliographyLikeFragment() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("qwen2.5:3b");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s4", "c4", "What is DLMS security suite 1?"),
                new GroundedFactBundle(
                        AnswerTopicFamily.SECURITY_SUITE,
                        "DLMS security suite 1",
                        "",
                        List.of("Suite 1 provides authentication and encryption", "AES-GCM-128")
                ),
                "\"ELECTRICITY METERING DATA EXCHANGE -THE DLMS/COSEM SUITE -Part 84...\" DLMS security suite 1 provides both authentication and encryption."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isIn("quoted document fragment opening", "weak synthesis model prefers grounded family summary");
    }

    @Test
    void usesGroundedFallbackWhenAssociationAnswerMisusesAcknowledgeSemantics() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s5", "c5", "What is the difference between AARQ and AARE in DLMS?"),
                new GroundedFactBundle(
                        AnswerTopicFamily.ASSOCIATION_APDUS,
                        "Association APDUs",
                        "",
                        List.of("AARQ is request", "AARE is response")
                ),
                "AARQ is the acknowledge request and AARE is the acknowledge response used to confirm the session."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("misdefined AARQ/AARE semantics");
    }

    @Test
    void usesGroundedFallbackWhenMultiAlarmAnswerCollapsesToSingleAlarm() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s6", "c6", "0x0142"),
                new GroundedFactBundle(
                        AnswerTopicFamily.SICONIA_ALARM_SUMMARY,
                        "SICONIA Alarm Summary",
                        "",
                        List.of(
                                "The input decoded into 3 separate SICONIA alarms.",
                                "Alarm 0x0002 is MEDIUM on HES with root cause Clock sync failure.",
                                "Alarm 0x0040 is MEDIUM on SECURITY with root cause Cover opened.",
                                "Alarm 0x0100 is MEDIUM on METER with root cause Reverse energy."
                        )
                ),
                "What it means: Alarm 0x0100 is MEDIUM on METER because of reverse energy. Next step: Check wiring polarity."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("missing grouped alarm summary detail");
    }

    @Test
    void rejectsUnsupportedSnrmReplySpeculation() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of()
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s7", "c7", "7EA00A030383CD6F7E what does this do?")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_HDLC_U_FRAME,
                        "HDLC U-frame Role",
                        "",
                        List.of("The deterministic decode classified the frame as an HDLC U-frame with subtype SNRM.")
                ),
                "This SNRM frame asks for an ACK or RCV and waits for an SNRM reply from the server."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("unsupported u-frame protocol speculation");
    }

    @Test
    void rejectsSnrmAnswersThatPredictUaReply() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of()
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s7b", "c7b", "7EA00A030383CD6F7E what does this do?")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_HDLC_U_FRAME,
                        "HDLC U-frame Role",
                        "",
                        List.of("The deterministic decode classified the frame as an HDLC U-frame with subtype SNRM.")
                ),
                "This SNRM frame starts the HDLC link, and the next step is that the peer replies with UA."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("unsupported u-frame protocol speculation");
    }

    @Test
    void rejectsSnrmAnswersThatClaimLinkAlreadyEnteredNormalResponseMode() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of()
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s7bb", "c7bb", "7EA00A030383CD6F7E what does this do?")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_HDLC_U_FRAME,
                        "HDLC U-frame Role",
                        "",
                        List.of("The deterministic decode classified the frame as an HDLC U-frame with subtype SNRM.")
                ),
                "The HDLC link is now in normal response mode and ready for subsequent APDU traffic."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("unsupported u-frame protocol speculation");
    }

    @Test
    void rejectsTentativeOuterRoleAnswersThatInventPeerBehavior() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(FrameType.S_FRAME, null, com.company.dlms.domain.decoder.SFrameType.RR, 1, 35651712, null, false, new byte[0]),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E",
                List.of("Unexpected information field on supervisory frame", "FCS invalid")
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s7c", "c7c", "Decode this malformed frame")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_HDLC_TENTATIVE_OUTER_ROLE,
                        "Tentative HDLC Outer Role",
                        "",
                        List.of(
                                "Only the outer HDLC classification is trustworthy because the frame is tentative.",
                                "The outer frame classification is S_FRAME with subtype RR.",
                                "FCS validity is false and payload interpretation must not be trusted."
                        )
                ),
                "The server acknowledged readiness for more I-frames, which could indicate a previous unsent request or acknowledgment."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("tentative outer-role overreach");
    }

    @Test
    void rejectsGetResponseAnswersThatCallItARequestOrActivePower() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                List.of(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)),
                false,
                "C4020109060100010800FF",
                List.of()
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s8", "c8", "C4020109060100010800FF")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_APDU_OPERATION,
                        "DLMS APDU Operation",
                        "",
                        List.of(
                                "The deterministic decode identified the payload as the DLMS APDU GET_RESPONSE.",
                                "GET_RESPONSE is the server's reply to a prior GET_REQUEST and carries the requested attribute value or returned structure.",
                                "GET_RESPONSE is not a request.",
                                "The decoded payload includes OBIS 1.0.1.8.0.255, which means Active energy import total."
                        )
                ),
                "GET_RESPONSE is a request APDU returning the active power meter reading."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("get_response semantics drift");
    }

    @Test
    void rejectsGetResponseAnswersThatOverclaimLiveValueWhenOnlyObjectReferenceWasDecoded() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                List.of(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)),
                false,
                "C4020109060100010800FF",
                List.of()
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s8b", "c8b", "Decode APDU C4020109060100010800FF and explain what object was returned.")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_APDU_OPERATION,
                        "DLMS APDU Operation",
                        "",
                        List.of(
                                "The deterministic decode identified the payload as the DLMS APDU GET_RESPONSE.",
                                "GET_RESPONSE is the server's reply to a prior GET_REQUEST and carries the requested attribute value or returned structure.",
                                "GET_RESPONSE is not a request.",
                                "In this decode, the AXDR content identifies the returned object reference or structure rather than proving a live meter reading or current measurement value.",
                                "The decoded payload includes OBIS 1.0.1.8.0.255, which means Active energy import total."
                        )
                ),
                "The server has responded with the requested attribute value and confirms the current state of active energy import total."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("get_response semantics drift");
    }

    @Test
    void rejectsGetResponseAnswersThatSayAxdrRepresentsSpecificValueForObjectReferenceOnlyDecode() {
        GroundedAnswerQualityGate gate = new GroundedAnswerQualityGate("strong-model");

        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                List.of(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)),
                false,
                "C4020109060100010800FF",
                List.of()
        );

        GroundedAnswerQualityGate.Evaluation evaluation = gate.evaluate(
                WorkflowState.empty("s8c", "c8c", "Decode APDU C4020109060100010800FF and explain what object was returned.")
                        .toBuilder()
                        .decodeResult(decodeResult)
                        .build(),
                new GroundedFactBundle(
                        AnswerTopicFamily.DECODE_APDU_OPERATION,
                        "DLMS APDU Operation",
                        "",
                        List.of(
                                "The deterministic decode identified the payload as the DLMS APDU GET_RESPONSE.",
                                "GET_RESPONSE is the server's reply to a prior GET_REQUEST and carries the requested attribute value or returned structure.",
                                "GET_RESPONSE is not a request.",
                                "In this decode, the AXDR content identifies the returned object reference or structure rather than proving a live meter reading or current measurement value.",
                                "The six-byte octet-string here is the returned OBIS/object identifier, so do not describe it as the specific meter value.",
                                "The decoded payload includes OBIS 1.0.1.8.0.255, which means Active energy import total."
                        )
                ),
                "Impact: The returned structure in the AXDR tree represents the value of the Active energy import total attribute. Next step: Refer to the AXDR content for the specific value of the Active energy import total."
        );

        assertThat(evaluation.decision()).isEqualTo(GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK);
        assertThat(evaluation.reason()).isEqualTo("get_response semantics drift");
    }
}
