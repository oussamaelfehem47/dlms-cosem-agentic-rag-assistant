package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.answer.AnswerMode;
import com.company.dlms.domain.answer.AnswerTopicFamily;
import com.company.dlms.domain.answer.GroundedAnswerContext;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
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
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedFactBundleBuilderTest {

    private final GroundedFactBundleBuilder builder = new GroundedFactBundleBuilder();

    @Test
    void dropsClippedSecurityQuoteFragments() {
        WorkflowState state = retrievalState(
                "How does replay protection work in DLMS?",
                "ng the response protection parameters that must meet required_protection is applied. "
                        + "Replay protection rejects stale invocation counters."
        );

        var bundle = builder.build(state);

        assertThat(bundle.family()).isEqualTo(AnswerTopicFamily.SECURITY_REPLAY);
        assertThat(bundle.preferredQuote()).isEqualTo("Replay protection rejects stale invocation counters.");
    }

    @Test
    void dropsHeadingLikeAndMetadataQuotes() {
        WorkflowState greenBookState = retrievalState(
                "What is the DLMS Green Book?",
                "10 | DLMS/COSEM Architecture and Protocols, the Green Book Edition 10. "
                        + "The Green Book defines the DLMS/COSEM architecture and terminology."
        );

        WorkflowState suiteState = retrievalState(
                "What is DLMS security suite 1?",
                "Function: H: a hash function: SHA-256 in the case of security suite 1. "
                        + "Security suite 1 provides authenticated encryption with AES-GCM-128."
        );

        assertThat(builder.build(greenBookState).preferredQuote())
                .isEqualTo("The Green Book defines the DLMS/COSEM architecture and terminology.");
        assertThat(builder.build(suiteState).preferredQuote())
                .isEqualTo("Security suite 1 provides authenticated encryption with AES-GCM-128.");
    }

    @Test
    void dropsMalformedSpacingQuotes() {
        WorkflowState state = retrievalState(
                "What is the difference between AARQ and AARE in DLMS?",
                "This Clause contains examples of encoding the AARQ and AARE APDUs, in cases of using variou s levels of authentication. "
                        + "AARQ is the Association Request APDU and AARE is the Association Response APDU."
        );

        var bundle = builder.build(state);

        assertThat(bundle.family()).isEqualTo(AnswerTopicFamily.ASSOCIATION_APDUS);
        assertThat(bundle.preferredQuote())
                .isEqualTo("AARQ is the Association Request APDU and AARE is the Association Response APDU.");
    }

    @Test
    void keepsReadablePreferredQuotes() {
        WorkflowState state = retrievalState(
                "What is the difference between AARQ and AARE in DLMS?",
                "AARQ is the Association Request APDU sent by the client. "
                        + "AARE is the Association Response APDU returned by the server."
        );

        var bundle = builder.build(state);

        assertThat(bundle.preferredQuote()).isEqualTo("AARQ is the Association Request APDU sent by the client.");
    }

    @Test
    void singleAarqQuestionStillBuildsAssociationApduFactBundle() {
        WorkflowState state = retrievalState(
                "What is AARQ in DLMS?",
                "AARQ is the Association Request APDU sent by the client. "
                        + "It proposes the application context and authentication parameters."
        );

        var bundle = builder.build(state);

        assertThat(bundle.family()).isEqualTo(AnswerTopicFamily.ASSOCIATION_APDUS);
        assertThat(bundle.authoritativeFacts())
                .anyMatch(fact -> fact.contains("Association Request APDU"))
                .anyMatch(fact -> fact.contains("Association Response APDU"));
    }

    @Test
    void buildDecodeBundleAddsGetResponseAndActiveEnergyFacts() {
        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                List.of(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)),
                false,
                "C4020109060100010800FF",
                List.of()
        );

        WorkflowState state = WorkflowState.empty("s2", "c2", "C4020109060100010800FF")
                .toBuilder()
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_APDU_DECODE,
                        decodeResult,
                        List.of(),
                        List.of(),
                        0.94,
                        false
                ))
                .decodeResult(decodeResult)
                .build();

        var bundle = builder.build(state);

        assertThat(bundle.family()).isEqualTo(AnswerTopicFamily.DECODE_APDU_OPERATION);
        assertThat(bundle.authoritativeFacts())
                .anyMatch(fact -> fact.contains("GET_RESPONSE is the server's reply to a prior GET_REQUEST"))
                .anyMatch(fact -> fact.contains("GET_RESPONSE is not a request"))
                .anyMatch(fact -> fact.contains("returned object reference or structure"))
                .anyMatch(fact -> fact.contains("Active energy import total"));
    }

    @Test
    void buildDecodeBundleAddsSnrmRequestAndNonConfirmationFacts() {
        DecodeResult decodeResult = new DecodeResult(
                new com.company.dlms.domain.decoder.HdlcFrame(
                        com.company.dlms.domain.decoder.FrameType.U_FRAME,
                        com.company.dlms.domain.decoder.UFrameType.SNRM,
                        null,
                        1,
                        1,
                        null,
                        true,
                        new byte[0]
                ),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of()
        );

        WorkflowState state = WorkflowState.empty("s2b", "c2b", "7EA00A030383CD6F7E what does this do?")
                .toBuilder()
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_FRAME_DECODE,
                        decodeResult,
                        List.of(),
                        List.of(),
                        0.94,
                        false
                ))
                .decodeResult(decodeResult)
                .build();

        var bundle = builder.build(state);

        assertThat(bundle.family()).isEqualTo(AnswerTopicFamily.DECODE_HDLC_U_FRAME);
        assertThat(bundle.authoritativeFacts())
                .anyMatch(fact -> fact.contains("asks the peer to enter normal response mode"))
                .anyMatch(fact -> fact.contains("does not prove that the peer accepted"));
    }

    @Test
    void singleCommunicationAlarmBundleAddsOperationalImpactFact() {
        SiconiaResult result = new SiconiaResult(
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
                new SiconiaProcessingMetadata(
                        com.company.dlms.domain.InputClass.ALARM_CODE,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null
                )
        );

        WorkflowState state = WorkflowState.empty("s3", "c3", "0x1342")
                .withGroundedAnswerContext(GroundedAnswerContext.deterministicSiconia(
                        StrategyKey.SICONIA_ALARM_ANALYSIS,
                        result,
                        List.of(),
                        List.of(),
                        0.9,
                        false
                ))
                .toBuilder()
                .siconiaResult(result)
                .build();

        var bundle = builder.build(state);

        assertThat(bundle.family()).isEqualTo(AnswerTopicFamily.SICONIA_ALARM_SUMMARY);
        assertThat(bundle.authoritativeFacts())
                .anyMatch(fact -> fact.contains("communication-path disruption"))
                .anyMatch(fact -> fact.contains("recommended remediation"));
    }

    private static WorkflowState retrievalState(String rawInput, String content) {
        GroundedAnswerContext context = GroundedAnswerContext.retrievalDocs(
                List.of(retrievalResult(content)),
                List.of("cite docs"),
                0.88
        );

        return WorkflowState.empty("s1", "c1", rawInput)
                .withGroundedAnswerContext(context);
    }

    private static RetrievalResult retrievalResult(String content) {
        return new RetrievalResult(
                new DocumentChunk(
                        "doc-1",
                        content,
                        new SourceCitation(
                                "dlms",
                                "green-book.pdf",
                                11,
                                "General",
                                "Reference",
                                null,
                                1.0,
                                "DLMS Standard — §11.1 General"
                        )
                ),
                0.91,
                0.88,
                0.75
        );
    }
}
