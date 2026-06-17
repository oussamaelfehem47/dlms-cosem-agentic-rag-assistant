package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private final RetrievalRouter router = Mockito.mock(RetrievalRouter.class);
    private final HybridRetrievalService hybridRetrievalService = Mockito.mock(HybridRetrievalService.class);
    private final RetrievalService retrievalService = new RetrievalService(router, hybridRetrievalService);

    @Test
    void retrieve_callsCorrectCollectionsAndMerges() {
        String query = "hdlc";
        IntentType intent = IntentType.DOCUMENTATION;
        
        // Mock Router: Documentation hits both tables
        when(router.route(intent, query)).thenReturn(List.of("dlms", "confluence"));

        // Mock Search Results for dlms
        RetrievalResult res1 = new RetrievalResult(new DocumentChunk("1", "dlms 1", null), 0.9, 0.9, 0.8);
        when(hybridRetrievalService.search(eq(query), eq("dlms"), anyInt())).thenReturn(Flux.just(res1));

        // Mock Search Results for confluence
        RetrievalResult res2 = new RetrievalResult(new DocumentChunk("2", "confluence 1", null), 0.95, 0.9, 0.9);
        when(hybridRetrievalService.search(eq(query), eq("confluence"), anyInt())).thenReturn(Flux.just(res2));

        StepVerifier.create(retrievalService.retrieve(query, intent, 5))
                .assertNext(first -> assertThat(first.chunk().id()).isEqualTo("2")) // Higher score first
                .assertNext(second -> assertThat(second.chunk().id()).isEqualTo("1"))
                .verifyComplete();
    }

    @Test
    void retrieve_boostsConfluenceForSiconiaTroubleshootQueries() {
        String query = "What is Ansible Inventory in SICONIA?";
        IntentType intent = IntentType.SICONIA_TROUBLESHOOT;

        when(router.route(intent, query)).thenReturn(List.of("embeddings_dlms_knowledge", "embeddings_confluence_knowledge"));

        RetrievalResult dlms = new RetrievalResult(
                new DocumentChunk(
                        "dlms-1",
                        "General operations guidance from DLMS documentation",
                        new SourceCitation("dlms", "green-book.pdf", 1, "Operations", "", "", 1.0,
                                "DLMS Standard - Operations")
                ),
                0.80,
                0.80,
                0.80
        );
        RetrievalResult confluence = new RetrievalResult(
                new DocumentChunk(
                        "conf-1",
                        "Ansible inventory explanation for SICONIA-CICD",
                        new SourceCitation("confluence", "1.-Ansible-Inventory_182775783.html", 0, "General",
                                "Ansible Inventory", "SICCICD", 0.9,
                                "Confluence - Ansible Inventory (SICCICD)")
                ),
                0.75,
                0.75,
                0.75
        );

        when(hybridRetrievalService.search(eq(query), eq("embeddings_dlms_knowledge"), anyInt()))
                .thenReturn(Flux.just(dlms));
        when(hybridRetrievalService.search(eq(query), eq("embeddings_confluence_knowledge"), anyInt()))
                .thenReturn(Flux.just(confluence));

        StepVerifier.create(retrievalService.retrieve(query, intent, 5))
                .assertNext(first -> assertThat(first.chunk().id()).isEqualTo("conf-1"))
                .assertNext(second -> assertThat(second.chunk().id()).isEqualTo("dlms-1"))
                .verifyComplete();
    }

    @Test
    void retrieve_boostsConfluenceTitleMatchesForDocumentationOpsQueries() {
        String query = "DC Clock Synchronization";
        IntentType intent = IntentType.DOCUMENTATION;

        when(router.route(intent, query)).thenReturn(List.of("embeddings_dlms_knowledge", "embeddings_confluence_knowledge"));

        RetrievalResult dlms = new RetrievalResult(
                new DocumentChunk(
                        "dlms-1",
                        "Clock handling according to DLMS standard timing sections",
                        new SourceCitation("dlms", "blue-book.pdf", 1, "Clock Objects", "", "", 1.0,
                                "DLMS Standard - Clock Objects")
                ),
                0.90,
                0.90,
                0.90
        );
        RetrievalResult confluence = new RetrievalResult(
                new DocumentChunk(
                        "conf-1",
                        "Operational guidance for synchronizing DC clocks",
                        new SourceCitation("confluence", ".DC-Clock-Synchronization-vV0_416383671.html", 0, "General",
                                ".DC Clock Synchronization vV0", "SPL", 1.0,
                                "Confluence - .DC Clock Synchronization vV0 (SPL)")
                ),
                0.78,
                0.78,
                0.78
        );

        when(hybridRetrievalService.search(eq(query), eq("embeddings_dlms_knowledge"), anyInt()))
                .thenReturn(Flux.just(dlms));
        when(hybridRetrievalService.search(eq(query), eq("embeddings_confluence_knowledge"), anyInt()))
                .thenReturn(Flux.just(confluence));

        StepVerifier.create(retrievalService.retrieve(query, intent, 5))
                .assertNext(first -> assertThat(first.chunk().id()).isEqualTo("conf-1"))
                .assertNext(second -> assertThat(second.chunk().id()).isEqualTo("dlms-1"))
                .verifyComplete();
    }

    @Test
    void retrieve_keepsDlmsFirstForProtocolQueriesWithoutOpsSignals() {
        String query = "What is OBIS 1.0.1.8.0.255?";
        IntentType intent = IntentType.DOCUMENTATION;

        when(router.route(intent, query)).thenReturn(List.of("embeddings_dlms_knowledge", "embeddings_confluence_knowledge"));

        RetrievalResult dlms = new RetrievalResult(
                new DocumentChunk(
                        "dlms-1",
                        "OBIS 1.0.1.8.0.255 is active energy import total.",
                        new SourceCitation("dlms", "blue-book.pdf", 1, "OBIS Codes", "", "", 1.0,
                                "DLMS Standard - OBIS Codes")
                ),
                0.91,
                0.91,
                0.91
        );
        RetrievalResult confluence = new RetrievalResult(
                new DocumentChunk(
                        "conf-1",
                        "Operational page that mentions energy workflows.",
                        new SourceCitation("confluence", "ops.html", 0, "General", "Meter activation", "SPL", 1.0,
                                "Confluence - Meter activation (SPL)")
                ),
                0.80,
                0.80,
                0.80
        );

        when(hybridRetrievalService.search(eq(query), eq("embeddings_dlms_knowledge"), anyInt()))
                .thenReturn(Flux.just(dlms));
        when(hybridRetrievalService.search(eq(query), eq("embeddings_confluence_knowledge"), anyInt()))
                .thenReturn(Flux.just(confluence));

        StepVerifier.create(retrievalService.retrieve(query, intent, 5))
                .assertNext(first -> assertThat(first.chunk().id()).isEqualTo("dlms-1"))
                .assertNext(second -> assertThat(second.chunk().id()).isEqualTo("conf-1"))
                .verifyComplete();
    }

    @Test
    void retrieve_standardsHeavyDocumentationQueriesUseDlmsOnly() {
        String query = "What is the DLMS Green Book?";
        IntentType intent = IntentType.DOCUMENTATION;

        when(router.route(intent, query)).thenReturn(List.of("embeddings_dlms_knowledge"));

        RetrievalResult dlms = new RetrievalResult(
                new DocumentChunk(
                        "dlms-1",
                        "The Green Book defines the DLMS/COSEM architecture and communication profiles.",
                        new SourceCitation("dlms", "green-book.pdf", 2, "Referenced documents", "", "", 1.0,
                                "DLMS Standard - Referenced documents")
                ),
                0.94,
                0.94,
                0.90
        );

        when(hybridRetrievalService.search(eq(query), eq("embeddings_dlms_knowledge"), anyInt()))
                .thenReturn(Flux.just(dlms));

        StepVerifier.create(retrievalService.retrieve(query, intent, 5))
                .assertNext(first -> assertThat(first.chunk().id()).isEqualTo("dlms-1"))
                .verifyComplete();

        verify(hybridRetrievalService, never()).search(eq(query), eq("embeddings_confluence_knowledge"), anyInt());
    }
}
