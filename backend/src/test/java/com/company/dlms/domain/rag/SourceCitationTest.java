package com.company.dlms.domain.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCitationTest {

    @Test
    void confluenceMetadata_formatsCorrectly() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of(
                "doc_type", "confluence",
                "source_file", "182772268.html",
                "page_title", "Foo",
                "space_name", "SPL"
        ));

        assertThat(citation.formatted()).isEqualTo("Confluence \u2014 Foo (SPL)");
    }

    @Test
    void confluenceMetadata_noPageTitle_fallsBackToSourceFile() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of(
                "doc_type", "confluence",
                "source_file", "182772268.html",
                "space_name", "SPL"
        ));

        assertThat(citation.formatted()).isEqualTo("Confluence \u2014 182772268.html (SPL)");
    }

    @Test
    void confluenceMetadata_inferredWhenDocTypeMissing() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of(
                "source_file", "Local-operations_408492990.html",
                "page_title", "Local operations",
                "space_name", "SPL"
        ));

        assertThat(citation.formatted()).isEqualTo("Confluence \u2014 Local operations (SPL)");
    }

    @Test
    void dlmsMetadata_formatsCorrectly() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of(
                "doc_type", "dlms",
                "section_title", "HDLC Frame Structure"
        ));

        assertThat(citation.formatted()).isEqualTo("DLMS Standard \u2014 \u00A7HDLC Frame Structure");
    }

    @Test
    void dlmsMetadata_noSectionTitle_formatsGracefully() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of("doc_type", "dlms"));

        assertThat(citation.formatted()).isEqualTo("DLMS Standard");
    }

    @Test
    void emptyMetadata_noException() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of());

        assertThat(citation.formatted()).isNotNull();
    }

    @Test
    void spaceWeight_parsedFromMetadata() {
        SourceCitation citation = SourceCitation.fromMetadata(Map.of(
                "doc_type", "confluence",
                "space_weight", 0.9
        ));

        assertThat(citation.spaceWeight()).isEqualTo(0.9);
    }
}
