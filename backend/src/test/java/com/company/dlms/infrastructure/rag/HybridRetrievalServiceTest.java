package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    @Mock private PgVectorStore dlmsVectorStore;
    @Mock private PgVectorStore confluenceVectorStore;
    @Mock private BM25Service bm25Service;

    private HybridRetrievalService hybridService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Using fixed weights for easy testing: 0.7 vector, 0.3 BM25
        hybridService = new HybridRetrievalService(dlmsVectorStore, confluenceVectorStore, bm25Service, 0.7, 0.3);
    }

    @Test
    void search_combinesScoresCorrectly() {
        String query = "hdlc";
        String collection = "embeddings_dlms_knowledge";

        // Mock Vector Result (Distance 0.2 -> Score 0.8)
        Document doc = new Document("id-1", "content", Map.of("distance", 0.2));
        when(dlmsVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        // Mock BM25 Result (Score 0.6)
        DocumentChunk chunk = new DocumentChunk("id-1", "content", null);
        BM25Result bm25Res = new BM25Result(chunk, 0.6);
        when(bm25Service.search(query, collection, 10)).thenReturn(Mono.just(List.of(bm25Res)));

        // Combined = (0.8 * 0.7) + (0.6 * 0.3) = 0.56 + 0.18 = 0.74
        StepVerifier.create(hybridService.search(query, collection, 10))
                .assertNext(result -> {
                    assertThat(result.chunk().id()).isEqualTo("id-1");
                    assertThat(result.vectorScore()).isEqualTo(0.8);
                    assertThat(result.bm25Score()).isEqualTo(0.6);
                    assertThat(result.combinedScore()).isEqualTo(0.74);
                })
                .verifyComplete();
    }

    @Test
    void search_ranksByCombinedScoreDescending() {
        String query = "test";
        
        // Doc A: v=0.5, b=0.5 -> c=0.5
        Document docA = new Document("A", "content A", Map.of("distance", 0.5));
        // Doc B: v=0.9, b=0.1 -> c= (0.9*0.7)+(0.1*0.3) = 0.63+0.03 = 0.66
        Document docB = new Document("B", "content B", Map.of("distance", 0.1));

        when(dlmsVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB));
        
        DocumentChunk chunkA = new DocumentChunk("A", "content A", null);
        DocumentChunk chunkB = new DocumentChunk("B", "content B", null);
        
        when(bm25Service.search(query, "dlms", 10)).thenReturn(Mono.just(List.of(
                new BM25Result(chunkA, 0.5),
                new BM25Result(chunkB, 0.1)
        )));

        StepVerifier.create(hybridService.search(query, "dlms", 10))
                .assertNext(first -> assertThat(first.chunk().id()).isEqualTo("B"))
                .assertNext(second -> assertThat(second.chunk().id()).isEqualTo("A"))
                .verifyComplete();
    }

    @Test
    void search_appliesSpaceWeightToCombinedScore() {
        String query = "operations";

        Document doc = new Document("id-1", "content", Map.of(
                "distance", 0.2,
                "doc_type", "confluence",
                "source_file", "123.html",
                "page_title", "Operations Runbook",
                "space_name", "SPL",
                "space_weight", 0.5
        ));
        when(confluenceVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        DocumentChunk chunk = new DocumentChunk(
                "id-1",
                "content",
                SourceCitation.fromMetadata(Map.of(
                        "doc_type", "confluence",
                        "source_file", "123.html",
                        "page_title", "Operations Runbook",
                        "space_name", "SPL",
                        "space_weight", 0.5
                ))
        );
        when(bm25Service.search(query, "embeddings_confluence_knowledge", 10))
                .thenReturn(Mono.just(List.of(new BM25Result(chunk, 0.6))));

        StepVerifier.create(hybridService.search(query, "embeddings_confluence_knowledge", 10))
                .assertNext(result -> {
                    assertThat(result.chunk().id()).isEqualTo("id-1");
                    assertThat(result.combinedScore()).isEqualTo(0.37);
                })
                .verifyComplete();
    }

    @Test
    void search_prefersBm25CitationMetadataWhenVectorChunkIsSparse() {
        String query = "operations";
        String collection = "embeddings_confluence_knowledge";

        Document doc = new Document("id-1", "Local operations", Map.of("distance", 0.2));
        when(confluenceVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        DocumentChunk bm25Chunk = new DocumentChunk(
                "id-1",
                "Local operations",
                SourceCitation.fromMetadata(Map.of(
                        "doc_type", "confluence",
                        "source_file", "Local-operations_408492990.html",
                        "page_title", "Local operations",
                        "space_name", "SPL",
                        "space_weight", 1.0
                ))
        );
        when(bm25Service.search(query, collection, 10))
                .thenReturn(Mono.just(List.of(new BM25Result(bm25Chunk, 0.6))));

        StepVerifier.create(hybridService.search(query, collection, 10))
                .assertNext(result -> {
                    assertThat(result.chunk().citation().docType()).isEqualTo("confluence");
                    assertThat(result.chunk().citation().pageTitle()).isEqualTo("Local operations");
                    assertThat(result.chunk().citation().spaceName()).isEqualTo("SPL");
                    assertThat(result.chunk().citation().formatted()).isEqualTo("Confluence — Local operations (SPL)");
                })
                .verifyComplete();
    }
    @Test
    void search_treatsBlankConfluenceDocTypeAsConfluenceFallback() {
        String query = "operations";
        String collection = "embeddings_confluence_knowledge";

        Document doc = new Document("id-1", "Local operations", Map.of(
                "distance", 0.2,
                "doc_type", ""
        ));
        when(confluenceVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(bm25Service.search(query, collection, 10)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(hybridService.search(query, collection, 10))
                .assertNext(result -> assertThat(result.chunk().citation().formatted()).startsWith("Confluence"))
                .verifyComplete();
    }

    @Test
    void search_mergesVectorAndBm25ResultsByContentWhenIdsDiffer() {
        String query = "ansible inventory";
        String collection = "embeddings_confluence_knowledge";
        String sharedContent = "An inventory defines a collection of hosts that Ansible manages.";

        Document doc = new Document("vec-1", sharedContent, Map.of(
                "distance", 0.1,
                "doc_type", ""
        ));
        when(confluenceVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        DocumentChunk bm25Chunk = new DocumentChunk(
                "bm25-1",
                sharedContent,
                SourceCitation.fromMetadata(Map.of(
                        "doc_type", "confluence",
                        "source_file", "1.-Ansible-Inventory_182775783.html",
                        "page_title", "1. Ansible Inventory",
                        "space_name", "SICCICD",
                        "space_weight", 0.9
                ))
        );
        when(bm25Service.search(query, collection, 10))
                .thenReturn(Mono.just(List.of(new BM25Result(bm25Chunk, 0.8))));

        StepVerifier.create(hybridService.search(query, collection, 10))
                .assertNext(result -> {
                    assertThat(result.chunk().citation().formatted()).startsWith("Confluence");
                    assertThat(result.chunk().citation().formatted()).contains("1. Ansible Inventory");
                    assertThat(result.chunk().citation().formatted()).contains("(SICCICD)");
                    assertThat(result.vectorScore()).isGreaterThan(0.0);
                    assertThat(result.bm25Score()).isGreaterThan(0.0);
                })
                .verifyComplete();
    }
}
