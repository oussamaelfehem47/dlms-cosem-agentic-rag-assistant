package com.company.dlms.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CasualQueryClassifierTest {

    @Test
    void extendedCourtesyPhraseCountsAsCasualNonTechnical() {
        assertThat(CasualQueryClassifier.isCasualNonTechnicalQuery("thank you for your help")).isTrue();
        assertThat(CasualQueryClassifier.isCasualNonTechnicalQuery("thanks a lot")).isTrue();
        assertThat(CasualQueryClassifier.isCasualNonTechnicalQuery("thank you very much")).isTrue();
        assertThat(CasualQueryClassifier.isCasualNonTechnicalQuery("thanks for the help")).isTrue();
    }

    @Test
    void technicalSignalOverridesCourtesyWording() {
        assertThat(CasualQueryClassifier.isCasualNonTechnicalQuery("thank you, explain HDLC")).isFalse();
        assertThat(CasualQueryClassifier.isCasualNonTechnicalQuery("thanks, what is AARQ?")).isFalse();
    }

    @Test
    void capabilityVariantsIncludingActuallyAreRecognized() {
        assertThat(CasualQueryClassifier.isAssistantCapabilityQuestion("what can you do?")).isTrue();
        assertThat(CasualQueryClassifier.isAssistantCapabilityQuestion("what you can actually do ?")).isTrue();
        assertThat(CasualQueryClassifier.isAssistantCapabilityQuestion("what can you actually do")).isTrue();
    }

    @Test
    void questionPhrasingCatchesNaturalLanguageProtocolQuestions() {
        assertThat(CasualQueryClassifier.isQuestionPhrasing("What are the differences between AARQ and AARE?")).isTrue();
        assertThat(CasualQueryClassifier.isQuestionPhrasing("How does HDLC framing work?")).isTrue();
        assertThat(CasualQueryClassifier.isQuestionPhrasing("Explain GMAC in DLMS")).isTrue();
    }
}
