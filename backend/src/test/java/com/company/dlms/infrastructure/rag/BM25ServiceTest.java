package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.SourceCitation;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BM25ServiceTest {

    private BM25Service bm25Service;

    @BeforeEach
    void setUp() {
        // Passing null for R2dbcEntityTemplate as we test search logic with manual indexing.
        bm25Service = new BM25Service(null);
    }

    @Test
    void search_withEmptyIndex_returnsEmptyList() {
        StepVerifier.create(bm25Service.search("test", 5))
                .assertNext(results -> assertThat(results).isEmpty())
                .verifyComplete();
    }

    @Test
    void search_returnsRankedResults() {
        DocumentChunk chunk1 = new DocumentChunk(
                "1",
                "DLMS protocol frame",
                new SourceCitation("dlms", "file1.pdf", 1, "Title", "", "", 1.0, "DLMS Standard - Title")
        );
        DocumentChunk chunk2 = new DocumentChunk(
                "2",
                "HDLC structure",
                new SourceCitation("dlms", "file2.pdf", 2, "Title2", "", "", 1.0, "DLMS Standard - Title2")
        );

        bm25Service.indexDocuments(List.of(chunk1, chunk2));

        StepVerifier.create(bm25Service.search("protocol", 5))
                .assertNext(results -> {
                    assertThat(results).isNotEmpty();
                    assertThat(results.get(0).chunk().id()).isEqualTo("1");
                    assertThat(results.get(0).score()).isGreaterThan(0.0);
                })
                .verifyComplete();
    }

    @Test
    void scoresAreNormalized() {
        DocumentChunk chunk1 = new DocumentChunk("1", "test test test", null);
        DocumentChunk chunk2 = new DocumentChunk("2", "test", null);

        bm25Service.indexDocuments(List.of(chunk1, chunk2));

        StepVerifier.create(bm25Service.search("test", 5))
                .assertNext(results -> results.forEach(res -> assertThat(res.score()).isBetween(0.0, 1.0)))
                .verifyComplete();
    }

    @Test
    void search_preservesConfluenceCitationMetadata() {
        DocumentChunk chunk = new DocumentChunk(
                "1",
                "Operations runbook",
                new SourceCitation("confluence", "123.html", 0, "General", "Operations Runbook", "SPL", 0.9,
                        "Confluence - Operations Runbook (SPL)")
        );

        bm25Service.indexDocuments(List.of(chunk), "embeddings_confluence_knowledge");

        StepVerifier.create(bm25Service.search("Operations", "embeddings_confluence_knowledge", 5))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).chunk().citation().pageTitle()).isEqualTo("Operations Runbook");
                    assertThat(results.get(0).chunk().citation().spaceName()).isEqualTo("SPL");
                    assertThat(results.get(0).chunk().citation().spaceWeight()).isEqualTo(0.9);
                })
                .verifyComplete();
    }

    @Test
    void search_confluenceTitleOnlyQuery_matchesPageTitle() {
        DocumentChunk chunk = new DocumentChunk(
                "1",
                "This page explains static host groups and managed hosts.",
                new SourceCitation("confluence", "123.html", 0, "General", "Ansible Inventory", "SICCICD", 0.9,
                        "Confluence - Ansible Inventory (SICCICD)")
        );

        bm25Service.indexDocuments(List.of(chunk), "embeddings_confluence_knowledge");

        StepVerifier.create(bm25Service.search("Ansible Inventory", "embeddings_confluence_knowledge", 5))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).chunk().id()).isEqualTo("1");
                    assertThat(results.get(0).chunk().citation().pageTitle()).isEqualTo("Ansible Inventory");
                })
                .verifyComplete();
    }

    @Test
    void search_confluenceNormalizedTitleQuery_matchesDecoratedPageTitle() {
        DocumentChunk chunk = new DocumentChunk(
                "1",
                "This page explains how to keep device clocks aligned.",
                new SourceCitation("confluence", "456.html", 0, "General", ".DC Clock Synchronization vV0", "SPL", 1.0,
                        "Confluence - .DC Clock Synchronization vV0 (SPL)")
        );

        bm25Service.indexDocuments(List.of(chunk), "embeddings_confluence_knowledge");

        StepVerifier.create(bm25Service.search("DC Clock Synchronization", "embeddings_confluence_knowledge", 5))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).chunk().id()).isEqualTo("1");
                    assertThat(results.get(0).chunk().citation().pageTitle()).isEqualTo(".DC Clock Synchronization vV0");
                })
                .verifyComplete();
    }

    @Test
    void search_dlmsCollectionDoesNotUsePageTitleAsSearchableField() {
        DocumentChunk chunk = new DocumentChunk(
                "1",
                "Protocol framing reference for application association.",
                new SourceCitation("dlms", "blue-book.pdf", 1, "Association", "OBIS Companion", "", 1.0,
                        "DLMS Standard - Association")
        );

        bm25Service.indexDocuments(List.of(chunk), "embeddings_dlms_knowledge");

        StepVerifier.create(bm25Service.search("OBIS Companion", "embeddings_dlms_knowledge", 5))
                .assertNext(results -> assertThat(results).isEmpty())
                .verifyComplete();
    }

    @Test
    void parseMetadata_supportsR2dbcJsonWrapper() {
        Map<String, Object> metadata = bm25Service.parseMetadata(Json.of("""
                {"doc_type":"confluence","page_title":"1. Ansible Inventory","space_name":"SICCICD","source_file":"1.-Ansible-Inventory_182775783.html","space_weight":0.9}
                """), "chunk-1");

        assertThat(metadata).containsEntry("doc_type", "confluence");
        assertThat(metadata).containsEntry("page_title", "1. Ansible Inventory");
        assertThat(metadata).containsEntry("space_name", "SICCICD");
    }

    @Test
    void search_filtersResultsByCollection() {
        DocumentChunk dlmsChunk = new DocumentChunk(
                "1",
                "operations troubleshooting",
                new SourceCitation("dlms", "file1.pdf", 1, "Operations", "", "", 1.0, "DLMS Standard - Operations")
        );
        DocumentChunk confluenceChunk = new DocumentChunk(
                "2",
                "operations troubleshooting",
                new SourceCitation("confluence", "123.html", 0, "General", "Operations Runbook", "SPL", 1.0,
                        "Confluence - Operations Runbook (SPL)")
        );

        bm25Service.indexDocuments(List.of(dlmsChunk), "embeddings_dlms_knowledge");
        bm25Service.indexDocuments(List.of(confluenceChunk), "embeddings_confluence_knowledge");

        StepVerifier.create(bm25Service.search("operations", "embeddings_confluence_knowledge", 5))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).chunk().id()).isEqualTo("2");
                    assertThat(results.get(0).chunk().citation().docType()).isEqualTo("confluence");
                })
                .verifyComplete();
    }
}
