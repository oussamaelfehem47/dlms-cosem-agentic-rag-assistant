package com.company.dlms.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalLanguageRoutingNormalizerTest {

    @Test
    void correctsWhitelistedRoutingTypos() {
        String normalized = NaturalLanguageRoutingNormalizer.normalize(
                "hat OBIS code was in the last repsonse?"
        );

        assertThat(normalized).isEqualTo("what OBIS code was in the last response?");
    }

    @Test
    void correctsQuestionAndSecurityTyposWithoutTouchingDomainTerms() {
        String normalized = NaturalLanguageRoutingNormalizer.normalize(
                "How deos replay protection work in DLMS?"
        );

        assertThat(normalized).isEqualTo("How does replay protection work in DLMS?");
    }

    @Test
    void doesNotRewriteHexObisAlarmXmlOrLogLikeInput() {
        assertThat(NaturalLanguageRoutingNormalizer.normalize("7EA00A030383CD6F7E"))
                .isEqualTo("7EA00A030383CD6F7E");
        assertThat(NaturalLanguageRoutingNormalizer.normalize("1.0.1.8.0.255"))
                .isEqualTo("1.0.1.8.0.255");
        assertThat(NaturalLanguageRoutingNormalizer.normalize("0x1342"))
                .isEqualTo("0x1342");
        assertThat(NaturalLanguageRoutingNormalizer.normalize("<trace><event type=\"ALARM\"/></trace>"))
                .isEqualTo("<trace><event type=\"ALARM\"/></trace>");
        assertThat(NaturalLanguageRoutingNormalizer.normalize("2024-03-20 10:00:01 [WAN] ERROR: Connection timeout"))
                .isEqualTo("2024-03-20 10:00:01 [WAN] ERROR: Connection timeout");
    }
}
