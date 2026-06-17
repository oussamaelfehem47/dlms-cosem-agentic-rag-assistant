package com.company.dlms.workflow;

import com.company.dlms.agent.RouterAgent;
import com.company.dlms.agent.dlms.DlmsInputNormalizer;
import com.company.dlms.agent.siconia.SiconiaInputNormalizer;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputUnderstandingServiceTest {

    private final InputUnderstandingService service = new InputUnderstandingService(
            new DlmsInputNormalizer(),
            new SiconiaInputNormalizer(),
            new RouterAgent(),
            new FollowUpResolver()
    );

    @Test
    void bareWrappedAndGenericAxdrPayloadsConvergeOnSameDeterministicStrategy() {
        InputUnderstanding bare = service.understand("1907E80416010E1E0000003C00", InputClass.QUERY);
        InputUnderstanding explicit = service.understand(
                "Explain AXDR payload 1907E80416010E1E0000003C00",
                InputClass.QUERY
        );
        InputUnderstanding generic = service.understand(
                "payload 1907E80416010E1E0000003C00",
                InputClass.QUERY
        );

        assertThat(bare.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_AXDR_DECODE);
        assertThat(explicit.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_AXDR_DECODE);
        assertThat(generic.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_AXDR_DECODE);

        assertThat(bare.intent()).isEqualTo(DlmsIntent.APDU_ANALYSIS);
        assertThat(explicit.intent()).isEqualTo(DlmsIntent.APDU_ANALYSIS);
        assertThat(generic.intent()).isEqualTo(DlmsIntent.APDU_ANALYSIS);

        assertThat(bare.dlmsNormalization()).isNotNull();
        assertThat(explicit.dlmsNormalization()).isNotNull();
        assertThat(generic.dlmsNormalization()).isNotNull();

        assertThat(bare.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        assertThat(explicit.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        assertThat(generic.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);

        assertThat(bare.dlmsNormalization().normalizedInput()).isEqualTo("1907E80416010E1E0000003C00");
        assertThat(explicit.dlmsNormalization().normalizedInput()).isEqualTo("1907E80416010E1E0000003C00");
        assertThat(generic.dlmsNormalization().normalizedInput()).isEqualTo("1907E80416010E1E0000003C00");
        assertThat(bare.orchestrationMode()).isEqualTo(OrchestrationMode.DETERMINISTIC_FAST_PATH);
        assertThat(explicit.orchestrationMode()).isEqualTo(OrchestrationMode.DETERMINISTIC_FAST_PATH);
        assertThat(generic.orchestrationMode()).isEqualTo(OrchestrationMode.DETERMINISTIC_FAST_PATH);
    }

    @Test
    void wrappedXmlProseSelectsSiconiaXmlStrategy() {
        InputUnderstanding understanding = service.understand(
                "Please inspect this XML trace: <Event timestamp=\"2024-01-15T10:30:00Z\"><Alarm code=\"0x1342\" severity=\"critical\"/><Source device=\"DCU-01\"/></Event>",
                InputClass.QUERY
        );

        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SICONIA_XML_ANALYSIS);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.XML_TRACE);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
        assertThat(understanding.siconiaNormalization()).isNotNull();
        assertThat(understanding.siconiaNormalization().normalizedInput()).contains("<Event");
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.DETERMINISTIC_FAST_PATH);
    }

    @Test
    void securityPhrasePrefersSecurityExplainOverAlarmLikeTokens() {
        InputUnderstanding understanding = service.understand(
                "AARE association rejected, diagnostic 6 - what does this usually mean?",
                InputClass.QUERY
        );

        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SECURITY_EXPLAIN);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.siconiaNormalization()).isNull();
    }

    @Test
    void ambiguousMultipleFramesReturnRankedCandidatesInsteadOfSingleForcedDecode() {
        InputUnderstanding understanding = service.understand(
                "Decode one of these frames: 7EA00A030383CD6F7E and 7EA00A0101934D7E",
                InputClass.QUERY
        );

        assertThat(understanding.strategyMetadata().ambiguous()).isTrue();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_FRAME_DECODE);
        assertThat(understanding.strategyMetadata().candidates()).isNotEmpty();
        assertThat(understanding.strategyMetadata().warnings())
                .anyMatch(warning -> warning.contains("More than one interpretation"));
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.AMBIGUOUS_SAFE_FALLBACK);
    }

    @Test
    void directAlarmCodeAutoExecutesSiconiaWithoutSurfacingAxdrAlternative() {
        InputUnderstanding understanding = service.understand("0x1342", InputClass.QUERY);

        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SICONIA_ALARM_ANALYSIS);
        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
        assertThat(understanding.siconiaNormalization()).isNotNull();
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.strategyMetadata().candidates())
                .extracting(candidate -> candidate.strategy())
                .containsExactly(StrategyKey.SICONIA_ALARM_ANALYSIS);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.DETERMINISTIC_FAST_PATH);
    }

    @Test
    void plainEnglishQuestionSkipsCandidateDisambiguationAndRoutesAsQuery() {
        InputUnderstanding understanding = service.understand("what can you do?", InputClass.QUERY);

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.CASUAL_CHAT);
    }

    @Test
    void assistantRoleQuestionAlsoUsesCapabilityPathWithoutRetrievalInference() {
        InputUnderstanding understanding = service.understand(
                "tell me what you can do and what is your role ?",
                InputClass.QUERY
        );

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.CASUAL_CHAT);
    }

    @Test
    void capabilityVariantWithActuallyStillUsesCapabilityPath() {
        InputUnderstanding understanding = service.understand(
                "what you can actually do ?",
                InputClass.QUERY
        );

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.CASUAL_CHAT);
    }

    @Test
    void tellMeMoreFallsBackToCapabilityPathWhenNoTechnicalSignalsExist() {
        InputUnderstanding understanding = service.understand("tell me more", InputClass.QUERY);

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.CASUAL_CHAT);
    }

    @Test
    void previousFrameRecallQuestionSkipsFreshDecodeInference() {
        InputUnderstanding understanding = service.understand(
                "what was the frame type of the frame I just sent?",
                InputClass.QUERY
        );

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.NATURAL_LANGUAGE_AGENTIC);
    }

    @Test
    void genericMeaningFollowUpAlsoSkipsFreshDecodeInference() {
        InputUnderstanding understanding = service.understand("what does that mean?", InputClass.QUERY);

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
    }

    @Test
    void failureReasonFollowUpAlsoSkipsFreshDecodeInference() {
        InputUnderstanding understanding = service.understand("why did it fail?", InputClass.QUERY);

        assertThat(understanding.strategyMetadata().ambiguous()).isFalse();
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
    }

    @Test
    void connectionRoleFollowUpUsesSessionRecallStrategy() {
        InputUnderstanding understanding = service.understand(
                "what type of connection is being established?",
                InputClass.QUERY
        );

        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void lastObisRecallFollowUpUsesSessionRecallStrategy() {
        InputUnderstanding understanding = service.understand(
                "what OBIS code was in the last response?",
                InputClass.QUERY
        );

        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void typoedLastObisRecallStillUsesSessionRecallStrategy() {
        InputUnderstanding understanding = service.understand(
                "hat OBIS code was in the last response?",
                InputClass.QUERY
        );

        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void typoedPreviousFrameRecallStillUsesSessionRecallStrategy() {
        InputUnderstanding understanding = service.understand(
                "waht was the frame type of the frame I just sent?",
                InputClass.QUERY
        );

        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.intent()).isEqualTo(DlmsIntent.UNKNOWN);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SESSION_RECALL);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void shortBareAndSpacedAxdrPayloadsStayOnDeterministicAxdrPath() {
        InputUnderstanding bare = service.understand("00", InputClass.QUERY);
        InputUnderstanding spaced = service.understand("03 01", InputClass.QUERY);

        assertThat(bare.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_AXDR_DECODE);
        assertThat(spaced.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_AXDR_DECODE);

        assertThat(bare.dlmsNormalization()).isNotNull();
        assertThat(spaced.dlmsNormalization()).isNotNull();

        assertThat(bare.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        assertThat(spaced.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);

        assertThat(bare.dlmsNormalization().normalizedInput()).isEqualTo("00");
        assertThat(spaced.dlmsNormalization().normalizedInput()).isEqualTo("0301");
    }

    @Test
    void protocolQuestionAboutAarqAndAareRoutesToDocumentationWithHighConfidence() {
        InputUnderstanding understanding = service.understand(
                "What are the differences between AARQ and AARE?",
                InputClass.QUERY
        );

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DOCUMENTATION);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void typoedProtocolQuestionAboutAarqAndAareStillRoutesToDocumentation() {
        InputUnderstanding understanding = service.understand(
                "What are the diferences between AARQ and AARE in DLMS?",
                InputClass.QUERY
        );

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.dlmsNormalization()).isNull();
        assertThat(understanding.siconiaNormalization()).isNull();
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DOCUMENTATION);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void hdlcFramingQuestionRoutesToDocumentationWithHighConfidence() {
        InputUnderstanding understanding = service.understand("What is HDLC framing?", InputClass.QUERY);

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DOCUMENTATION);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.NATURAL_LANGUAGE_AGENTIC);
    }

    @Test
    void hlsAuthenticationQuestionRoutesToSecurityWithHighConfidence() {
        InputUnderstanding understanding = service.understand("How does HLS authentication work?", InputClass.QUERY);

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SECURITY_EXPLAIN);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.NATURAL_LANGUAGE_AGENTIC);
    }

    @Test
    void typoedReplayProtectionQuestionStillRoutesToSecurity() {
        InputUnderstanding understanding = service.understand(
                "How deos replay protection work in DLMS?",
                InputClass.QUERY
        );

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.SECURITY_EXPLAIN);
        assertThat(understanding.strategyMetadata().confidence()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void gmacQuestionDoesNotFallIntoFrameDecode() {
        InputUnderstanding understanding = service.understand("Explain GMAC in DLMS", InputClass.QUERY);

        assertThat(understanding.intent()).isIn(DlmsIntent.SECURITY_EXPLAIN, DlmsIntent.DOCUMENTATION);
        assertThat(understanding.strategyMetadata().selectedStrategy())
                .isNotIn(StrategyKey.DLMS_FRAME_DECODE, StrategyKey.DLMS_APDU_DECODE);
    }

    @Test
    void gbtFrameQuestionRoutesToDocumentationInsteadOfFrameDecode() {
        InputUnderstanding understanding = service.understand("What is a GBT frame?", InputClass.QUERY);

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DOCUMENTATION);
    }

    @Test
    void bareApduPayloadStillUsesDeterministicApduDecode() {
        InputUnderstanding understanding = service.understand("C4020109060100010800FF", InputClass.QUERY);

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.APDU_ANALYSIS);
        assertThat(understanding.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(understanding.dlmsNormalization()).isNotNull();
        assertThat(understanding.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.APDU_HEX);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_APDU_DECODE);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.DETERMINISTIC_FAST_PATH);
    }

    @Test
    void frameWithExplainInContextUsesStructuredPlusAgenticMode() {
        InputUnderstanding understanding = service.understand(
                "7EA00A030383CD6F7E what does this do?",
                InputClass.QUERY
        );

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_FRAME_DECODE);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.STRUCTURED_PLUS_AGENTIC);
    }

    @Test
    void apduPromptWithReturnedObjectPhraseStaysOnStructuredDecodePath() {
        InputUnderstanding understanding = service.understand(
                "Decode APDU C4020109060100010800FF and explain what object was returned.",
                InputClass.QUERY
        );

        assertThat(understanding.intent()).isEqualTo(DlmsIntent.APDU_ANALYSIS);
        assertThat(understanding.strategyMetadata().selectedStrategy()).isEqualTo(StrategyKey.DLMS_APDU_DECODE);
        assertThat(understanding.dlmsNormalization()).isNotNull();
        assertThat(understanding.dlmsNormalization().kind()).isEqualTo(DlmsNormalizedKind.APDU_HEX);
        assertThat(understanding.orchestrationMode()).isEqualTo(OrchestrationMode.STRUCTURED_PLUS_AGENTIC);
    }
}
