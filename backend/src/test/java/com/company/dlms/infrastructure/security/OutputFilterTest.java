package com.company.dlms.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputFilterTest {

    private final OutputFilter outputFilter = new OutputFilter();

    @Test
    void redacts32CharHexString() {
        OutputFilter.FilterResult result = outputFilter.filter("key=0123456789abcdef0123456789abcdef");
        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("key=[REDACTED-KEY]");
    }

    @Test
    void redacts64CharHexString() {
        String hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        OutputFilter.FilterResult result = outputFilter.filter("secret=" + hex64);
        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("secret=[REDACTED-KEY]");
    }

    @Test
    void cleanResponseIsUnchangedAndNotFiltered() {
        OutputFilter.FilterResult result = outputFilter.filter("Normal decode explanation");
        assertThat(result.filtered()).isFalse();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("Normal decode explanation");
    }

    @Test
    void exploitPatternIsBlocked() {
        OutputFilter.FilterResult result = outputFilter.filter("please exploit this vulnerability now");
        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isTrue();
        assertThat(result.reason()).contains("BLOCKED");
    }

    @Test
    void benignSecurityExplanationIsAllowed() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "Replay protection uses a frame counter, and HLS uses challenge-response authentication."
        );

        assertThat(result.filtered()).isFalse();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).contains("challenge-response");
    }

    @Test
    void benignPayloadTermIsAllowed() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "The APDU payload contains the DLMS application-layer fields."
        );

        assertThat(result.filtered()).isFalse();
        assertThat(result.blocked()).isFalse();
    }

    @Test
    void mixedCleanTextAndHexRedactsOnlyHex() {
        String input = "Keep this text, key=0123456789abcdef0123456789abcdef, done.";
        OutputFilter.FilterResult result = outputFilter.filter(input);
        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("Keep this text, key=[REDACTED-KEY], done.");
    }

    @Test
    void stripsConfidenceLeakFromVisibleAnswer() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "The DLMS Green Book defines the architecture. Confidence:0.68."
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("The DLMS Green Book defines the architecture.");
    }

    @Test
    void normalizesSecuritySuiteSpacing() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "DLMS security suite1 uses AES-GCM-128."
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("DLMS security suite 1 uses AES-GCM-128.");
    }

    @Test
    void normalizesMissingSpaceBeforeBitLengthQualifier() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "DLMS security suite1 employs SHA-256 with128-bit keys."
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("DLMS security suite 1 employs SHA-256 with 128-bit keys.");
    }

    @Test
    void normalizesMojibakeCitationArtifacts() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "DLMS Standard ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚§Green Book"
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).contains("DLMS Standard");
        assertThat(result.content()).contains("Green Book");
        assertThat(result.content()).doesNotContain("Ãƒ", "Ã¢â‚¬", "Ã‚Â§");
    }

    @Test
    void restoresLiteralEscapedNewlinesToRealLineBreaks() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "Line one.\\nLine two.\\n\\nSources: DLMS Standard\\nGreen Book"
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("Line one.\nLine two.\n\nSources: DLMS Standard\nGreen Book");
    }

    @Test
    void separatesGluedSourcesFooterIntoDedicatedBlock() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "The server returns AARE for the outcome of the association attempt.Sources: DLMS Standard ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Ãƒâ€šÃ‚§11.1 General"
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).startsWith(
                "The server returns AARE for the outcome of the association attempt.\n\nSources: DLMS Standard"
        );
        assertThat(result.content()).contains("11.1 General");
        assertThat(result.content()).doesNotContain("attempt.Sources:");
    }

    @Test
    void removesInlineNumberedSourcesResidueFromAnswerBody() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "Replay protection uses a frame counter.\nSources:\n1\nDLMS Standard — §11.1 General\n2\nDLMS Standard — §General"
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("Replay protection uses a frame counter.");
    }

    @Test
    void flattensMarkdownHeadingsAndStripsBoldMarkers() {
        OutputFilter.FilterResult result = outputFilter.filter(
                "### Association Rejection Diagnostics\n**Practical Troubleshooting Checks**\nCheck authentication."
        );

        assertThat(result.filtered()).isTrue();
        assertThat(result.blocked()).isFalse();
        assertThat(result.content()).isEqualTo("Association Rejection Diagnostics:\nPractical Troubleshooting Checks\nCheck authentication.");
    }
}
