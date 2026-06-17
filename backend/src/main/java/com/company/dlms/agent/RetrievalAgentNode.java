package com.company.dlms.agent;

import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;

import com.company.dlms.infrastructure.rag.RetrievalService;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.infrastructure.mcp.McpTools;

@Component
public class RetrievalAgentNode {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAgentNode.class);

    private static final Duration RETRIEVAL_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern OBIS_NOTATION_PATTERN =
            Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    private final RetrievalService retrievalService;
    private final McpDispatcher mcpDispatcher;
    private final ObisResolver obisResolver;

    public RetrievalAgentNode(RetrievalService retrievalService, McpDispatcher mcpDispatcher, ObisResolver obisResolver) {
        this.retrievalService = retrievalService;
        this.mcpDispatcher = mcpDispatcher;
        this.obisResolver = obisResolver;
    }

    public WorkflowState process(WorkflowState state) {
        log.debug("RetrievalAgentNode running sessionId={} intent={}", state.sessionId(), state.intent());

        if (state.rawInput() == null || state.rawInput().isBlank()) {
            return state;
        }

        IntentType intent = mapIntent(state.intent());

        try {
            List<RetrievalResult> ragResults = retrievalService.retrieve(state.rawInput(), intent, 10)
                    .collectList()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(RETRIEVAL_TIMEOUT);

            if (ragResults == null) {
                log.warn("Retrieval timed out after {}ms for sessionId={}", RETRIEVAL_TIMEOUT.toMillis(), state.sessionId());
                return state.addError("Retrieval timed out");
            }

            List<RetrievalResult> finalResults = new ArrayList<>(ragResults);
            boolean mcpUsed = false;

            if (state.intent() == DlmsIntent.OBIS_LOOKUP) {
                String obisCode = extractObisCode(state.rawInput());
                if (obisCode != null) {
                    ObisResolution resolution = obisResolver.resolve(obisCode, state.sessionId())
                            .subscribeOn(Schedulers.boundedElastic())
                            .block(Duration.ofSeconds(5));
                    if (resolution != null) {
                        List<RetrievalResult> obisScoped = new ArrayList<>();
                        obisScoped.add(toObisRetrievalResult(resolution));
                        // Keep only chunks that mention the exact OBIS code to avoid unrelated semantic drift.
                        finalResults.stream()
                                .filter(r -> r.chunk() != null && r.chunk().content() != null
                                        && r.chunk().content().contains(obisCode))
                                .limit(2)
                                .forEach(obisScoped::add);
                        finalResults = obisScoped;
                        log.info("OBIS resolution injected for {} via {}", obisCode, resolution.tierUsed());
                    }
                }
            }

            // [PHASE 8] US3: Enriched Documentation Queries via MCP Confluence Search
            if (state.intent() == DlmsIntent.DOCUMENTATION) {
                McpResult mcp = mcpDispatcher.dispatch(McpTools.CONFLUENCE_SEARCH, Map.of("query", state.rawInput(), "limit", 3))
                        .subscribeOn(Schedulers.boundedElastic())
                        .block(Duration.ofSeconds(10));

                if (mcp != null && mcp.success()) {
                    List<RetrievalResult> confluenceResults = mapConfluenceResults(mcp.result());
                    if (confluenceResults != null && !confluenceResults.isEmpty()) {
                        finalResults.addAll(0, confluenceResults);
                        mcpUsed = true;
                    }
                }
            }

            logFinalCitations(state, finalResults, mcpUsed);

            return state.toBuilder()
                    .retrievalResults(finalResults)
                    .mcpUsed(mcpUsed)
                    .build();
        } catch (Exception e) {
            log.error("Retrieval failed for sessionId={}", state.sessionId(), e);
            return state.addError("Retrieval failed: " + e.getMessage());
        }
    }

    private List<RetrievalResult> mapConfluenceResults(JsonNode root) {
        try {
            List<RetrievalResult> out = new ArrayList<>();
            JsonNode results = root.path("results");
            if (results.isArray()) {
                results.forEach(node -> {
                    String pageId = node.path("page_id").asText();
                    String title = node.path("title").asText();
                    String space = node.path("space").asText("GENERAL");
                    String excerpt = node.path("excerpt").asText();

                    DocumentChunk chunk = new DocumentChunk(
                            "mcp-confluence-" + pageId,
                            excerpt,
                            new SourceCitation("confluence", title, 0, "", title, space, 1.0,
                                    "Confluence — " + title + " (" + space + ")")
                    );
                    out.add(new RetrievalResult(chunk, 1.0, 1.0, 0.0));
                });
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to map Confluence search results: {}", e.getMessage());
            return null;
        }
    }

    private IntentType mapIntent(DlmsIntent intent) {
        if (intent == null) return IntentType.UNKNOWN;
        try {
            return IntentType.valueOf(intent.name());
        } catch (IllegalArgumentException e) {
            return IntentType.UNKNOWN;
        }
    }

    private String extractObisCode(String input) {
        if (input == null) return null;
        Matcher matcher = OBIS_NOTATION_PATTERN.matcher(input);
        return matcher.find() ? matcher.group() : null;
    }

    private RetrievalResult toObisRetrievalResult(ObisResolution resolution) {
        String tier = resolution.tierUsed() == null ? ResolutionTier.STRUCTURAL.name() : resolution.tierUsed().name();
        String description = (resolution.description() == null || resolution.description().isBlank())
                ? "Unknown OBIS meaning"
                : resolution.description();
        String content = "OBIS RESOLUTION:\n" +
                "OBIS " + resolution.obis() + " = " + description +
                (resolution.ic() != null ? " (IC " + resolution.ic() + ")" : "") +
                "\nSource tier: " + tier;

        DocumentChunk chunk = new DocumentChunk(
                "obis-" + resolution.obis() + "-" + tier.toLowerCase(),
                content,
                new SourceCitation("obis-resolver", "OBIS Resolver", 0, tier, "", "", 1.0,
                        "OBIS Resolver (" + tier + ")")
        );
        return new RetrievalResult(chunk, 1.0, 1.0, 0.0);
    }

    private void logFinalCitations(WorkflowState state, List<RetrievalResult> finalResults, boolean mcpUsed) {
        if (!log.isDebugEnabled()) {
            return;
        }

        List<String> citations = finalResults == null ? List.of() : finalResults.stream()
                .map(RetrievalResult::chunk)
                .map(chunk -> chunk != null ? chunk.citation() : null)
                .map(citation -> citation != null ? citation.formatted() : "(missing citation)")
                .toList();
        log.debug("RetrievalAgentNode final citations sessionId={} intent={} mcpUsed={} citations={}",
                state.sessionId(),
                state.intent(),
                mcpUsed,
                citations);
    }
}
