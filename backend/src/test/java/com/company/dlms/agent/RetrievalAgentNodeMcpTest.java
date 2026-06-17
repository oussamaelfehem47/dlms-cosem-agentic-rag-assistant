package com.company.dlms.agent;

import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.infrastructure.rag.RetrievalService;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalAgentNodeMcpTest {

    private RetrievalService retrievalService;
    private McpDispatcher mcpDispatcher;
    private ObisResolver obisResolver;
    private RetrievalAgentNode node;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        retrievalService = mock(RetrievalService.class);
        mcpDispatcher = mock(McpDispatcher.class);
        obisResolver = mock(ObisResolver.class);
        objectMapper = new ObjectMapper();
        node = new RetrievalAgentNode(retrievalService, mcpDispatcher, obisResolver);
    }

    @Test
    void documentationIntent_mcpSuccess_prependsMcpResults_mcpUsedTrue() throws Exception {
        RetrievalResult ragResult = new RetrievalResult(
                new DocumentChunk("rag-1", "RAG content", null), 0.8, 0.8, 0.7);
        when(retrievalService.retrieve(anyString(), any(IntentType.class), anyInt()))
                .thenReturn(Flux.just(ragResult));

        ObjectNode resultItem = objectMapper.createObjectNode();
        resultItem.put("page_id", "12345");
        resultItem.put("title", "DLMS Handbook");
        resultItem.put("space", "ENG");
        resultItem.put("excerpt", "Confluence excerpt");

        ObjectNode mcpJson = objectMapper.createObjectNode();
        mcpJson.put("available", true);
        mcpJson.put("result_count", 1);
        mcpJson.set("results", objectMapper.createArrayNode().add(resultItem));

        when(mcpDispatcher.dispatch(eq("confluence.search"), anyMap()))
                .thenReturn(Mono.just(McpResult.success("confluence.search", mcpJson)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "DLMS guide")
                .withIntent(DlmsIntent.DOCUMENTATION);
        WorkflowState out = node.process(state);

        assertNotNull(out.retrievalResults());
        assertEquals(2, out.retrievalResults().size(), "Should have MCP + RAG results");
        assertEquals("mcp-confluence-12345", out.retrievalResults().get(0).chunk().id());
        assertEquals("rag-1", out.retrievalResults().get(1).chunk().id());
        assertTrue(out.mcpUsed());
    }

    @Test
    void documentationIntent_mcpFailure_returnsRagOnly_mcpUsedFalse() {
        RetrievalResult ragResult = new RetrievalResult(
                new DocumentChunk("rag-1", "RAG content", null), 0.8, 0.8, 0.7);
        when(retrievalService.retrieve(anyString(), any(IntentType.class), anyInt()))
                .thenReturn(Flux.just(ragResult));

        when(mcpDispatcher.dispatch(eq("confluence.search"), anyMap()))
                .thenReturn(Mono.just(McpResult.failure("confluence.search", "Connection refused")));

        WorkflowState state = WorkflowState.empty("s1", "c1", "DLMS guide")
                .withIntent(DlmsIntent.DOCUMENTATION);
        WorkflowState out = node.process(state);

        assertNotNull(out.retrievalResults());
        assertEquals(1, out.retrievalResults().size());
        assertEquals("rag-1", out.retrievalResults().get(0).chunk().id());
        assertFalse(out.mcpUsed());
    }

    @Test
    void documentationIntent_mcpUnavailable_returnsRagOnly_mcpUsedFalse() {
        RetrievalResult ragResult = new RetrievalResult(
                new DocumentChunk("rag-1", "RAG content", null), 0.8, 0.8, 0.7);
        when(retrievalService.retrieve(anyString(), any(IntentType.class), anyInt()))
                .thenReturn(Flux.just(ragResult));

        when(mcpDispatcher.dispatch(eq("confluence.search"), anyMap()))
                .thenReturn(Mono.just(McpResult.unavailable("confluence.search")));

        WorkflowState state = WorkflowState.empty("s1", "c1", "DLMS guide")
                .withIntent(DlmsIntent.DOCUMENTATION);
        WorkflowState out = node.process(state);

        assertNotNull(out.retrievalResults());
        assertEquals(1, out.retrievalResults().size());
        assertFalse(out.mcpUsed());
    }

    @Test
    void frameDecodeIntent_confluenceMcpNotCalled() {
        RetrievalResult ragResult = new RetrievalResult(
                new DocumentChunk("rag-1", "RAG content", null), 0.8, 0.8, 0.7);
        when(retrievalService.retrieve(anyString(), any(IntentType.class), anyInt()))
                .thenReturn(Flux.just(ragResult));

        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA0")
                .withIntent(DlmsIntent.FRAME_DECODE);
        WorkflowState out = node.process(state);

        verify(mcpDispatcher, never()).dispatch(eq("confluence.search"), anyMap());
        assertNotNull(out.retrievalResults());
        assertEquals(1, out.retrievalResults().size());
        assertFalse(out.mcpUsed());
    }

    @Test
    void obisLookup_resolverResultIsPrepended() {
        RetrievalResult ragResult = new RetrievalResult(
                new DocumentChunk("rag-1", "RAG content", null), 0.8, 0.8, 0.7);
        when(retrievalService.retrieve(anyString(), any(IntentType.class), anyInt()))
                .thenReturn(Flux.just(ragResult));
        when(obisResolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution(
                        "1.0.1.8.0.255",
                        "Active energy import, total",
                        3,
                        null,
                        null,
                        ResolutionTier.STRUCTURAL
                )));

        WorkflowState state = WorkflowState.empty("s1", "c1", "What is OBIS 1.0.1.8.0.255?")
                .withIntent(DlmsIntent.OBIS_LOOKUP);
        WorkflowState out = node.process(state);

        assertNotNull(out.retrievalResults());
        assertTrue(out.retrievalResults().size() >= 1);
        assertTrue(out.retrievalResults().get(0).chunk().content().contains("OBIS 1.0.1.8.0.255"));
    }

    @Test
    void siconiaTroubleshoot_usesLocalConfluenceRagResults_withoutMcp() {
        RetrievalResult ragResult = new RetrievalResult(
                new DocumentChunk(
                        "rag-1",
                        "Local operations for SICONIA",
                        new SourceCitation("confluence", "Local-operations_408492990.html", 0, "General",
                                "Local operations", "SPL", 1.0,
                                "Confluence — Local operations (SPL)")
                ),
                0.92,
                0.92,
                0.90
        );
        when(retrievalService.retrieve(anyString(), any(IntentType.class), anyInt()))
                .thenReturn(Flux.just(ragResult));

        WorkflowState state = WorkflowState.empty("s1", "c1", "What is Local operations in SICONIA?")
                .withIntent(DlmsIntent.SICONIA_TROUBLESHOOT);
        WorkflowState out = node.process(state);

        verify(mcpDispatcher, never()).dispatch(eq("confluence.search"), anyMap());
        assertNotNull(out.retrievalResults());
        assertEquals(1, out.retrievalResults().size());
        assertEquals("Confluence — Local operations (SPL)", out.retrievalResults().get(0).chunk().citation().formatted());
        assertFalse(out.mcpUsed());
    }
}
