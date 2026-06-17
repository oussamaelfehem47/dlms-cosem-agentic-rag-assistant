package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class HybridAgenticPlannerService {

    private static final Logger log = LoggerFactory.getLogger(HybridAgenticPlannerService.class);

    private static final Duration PLANNER_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> SESSION_TOOLS = List.of("get_session_memory", "get_session_history");
    private static final List<String> DOCS_TOOLS = List.of("search_docs");
    private static final List<String> STRUCTURED_AGENTIC_TOOLS = List.of(
            "search_docs",
            "get_session_memory",
            "get_session_history",
            "detect_anomalies"
    );

    private final com.company.dlms.infrastructure.llm.OllamaStreamingClient ollamaStreamingClient;
    private final com.company.dlms.infrastructure.llm.PromptAssembler promptAssembler;
    private final AgentDispatchNode agentDispatchNode;
    private final ObjectMapper objectMapper;

    public HybridAgenticPlannerService(
            com.company.dlms.infrastructure.llm.OllamaStreamingClient ollamaStreamingClient,
            com.company.dlms.infrastructure.llm.PromptAssembler promptAssembler,
            AgentDispatchNode agentDispatchNode,
            ObjectMapper objectMapper
    ) {
        this.ollamaStreamingClient = Objects.requireNonNull(ollamaStreamingClient, "ollamaStreamingClient");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler");
        this.agentDispatchNode = Objects.requireNonNull(agentDispatchNode, "agentDispatchNode");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Mono<WorkflowState> applyIfNeeded(@NonNull WorkflowState state) {
        OrchestrationMode mode = state.orchestrationMode();
        if (mode == null
                || mode == OrchestrationMode.DETERMINISTIC_FAST_PATH
                || mode == OrchestrationMode.AMBIGUOUS_SAFE_FALLBACK) {
            return Mono.just(state);
        }

        List<String> allowedTools = allowedTools(state);
        WorkflowState plannerState = state.withPlannerUsed(true);
        if (allowedTools.isEmpty()) {
            return Mono.just(recoverWithoutPlannerResult(plannerState, "No internal tools were available for this orchestration mode."));
        }

        String plannerSystemPrompt = promptAssembler.plannerSystemPrompt(plannerState);
        String plannerPrompt = promptAssembler.plannerPrompt(plannerState, allowedTools);

        return safePlannerStream(plannerSystemPrompt, plannerPrompt)
                .collectList()
                .map(tokens -> String.join("", tokens))
                .map(this::parsePlannerDecision)
                .map(optionalDecision -> optionalDecision
                        .map(decision -> applyPlannerDecision(plannerState, allowedTools, decision))
                        .orElseGet(() -> recoverWithoutPlannerResult(
                                plannerState,
                                "Planner output was not valid JSON."
                        )))
                .timeout(PLANNER_TIMEOUT)
                .onErrorResume(ex -> {
                    log.warn("Hybrid planner failed for sessionId={}: {}", state.sessionId(), ex.getMessage());
                    return Mono.just(recoverWithoutPlannerResult(
                            plannerState,
                            "Planner execution failed: " + simplify(ex)
                    ));
                });
    }

    private Flux<String> safePlannerStream(String systemPrompt, String prompt) {
        Flux<String> stream = ollamaStreamingClient.stream(systemPrompt, prompt);
        return stream == null ? Flux.empty() : stream;
    }

    private List<String> allowedTools(WorkflowState state) {
        OrchestrationMode mode = state.orchestrationMode();
        StrategyKey selectedStrategy = state.strategyMetadata() == null
                ? StrategyKey.UNKNOWN
                : state.strategyMetadata().selectedStrategy();

        if (mode == OrchestrationMode.STRUCTURED_PLUS_AGENTIC) {
            return STRUCTURED_AGENTIC_TOOLS;
        }
        if (mode == OrchestrationMode.NATURAL_LANGUAGE_AGENTIC) {
            if (selectedStrategy == StrategyKey.SESSION_RECALL) {
                return SESSION_TOOLS;
            }
            if (state.intent() == DlmsIntent.DOCUMENTATION || state.intent() == DlmsIntent.SECURITY_EXPLAIN) {
                return DOCS_TOOLS;
            }
            return List.of("get_session_memory", "get_session_history", "search_docs");
        }
        return List.of();
    }

    private WorkflowState applyPlannerDecision(
            WorkflowState state,
            List<String> allowedTools,
            PlannerDecision decision
    ) {
        String action = decision.action() == null ? "" : decision.action().trim().toLowerCase(Locale.ROOT);
        if ("finish".equals(action)) {
            return recoverAfterEarlyFinish(state);
        }
        if (!"call_tool".equals(action)) {
            return recoverWithoutPlannerResult(state, "Planner returned unsupported action: " + decision.action());
        }
        if (decision.tool() == null || !allowedTools.contains(decision.tool())) {
            return recoverWithoutPlannerResult(
                    state,
                    "Planner requested a disallowed tool: " + (decision.tool() == null ? "(none)" : decision.tool())
            );
        }
        return agentDispatchNode.executeTool(state, decision.tool(), decision.query(), decision.rationale());
    }

    private WorkflowState recoverAfterEarlyFinish(WorkflowState state) {
        StrategyKey selectedStrategy = state.strategyMetadata() == null
                ? StrategyKey.UNKNOWN
                : state.strategyMetadata().selectedStrategy();
        if (selectedStrategy == StrategyKey.SESSION_RECALL) {
            return agentDispatchNode.executeTool(
                    state.withPlannerFallbackReason("Planner finished without a tool; session recall used stored context directly."),
                    "get_session_memory",
                    null,
                    "session recall fallback"
            );
        }
        if ((state.intent() == DlmsIntent.DOCUMENTATION || state.intent() == DlmsIntent.SECURITY_EXPLAIN)
                && (state.retrievalResults() == null || state.retrievalResults().isEmpty())) {
            return agentDispatchNode.executeTool(
                    state.withPlannerFallbackReason("Planner finished without retrieval; documentation search ran as a deterministic safety fallback."),
                    "search_docs",
                    state.rawInput(),
                    state.intent() == DlmsIntent.SECURITY_EXPLAIN ? "security fallback" : "documentation fallback"
            );
        }
        return state;
    }

    private WorkflowState recoverWithoutPlannerResult(WorkflowState state, String reason) {
        WorkflowState withReason = state.withPlannerFallbackReason(reason);
        StrategyKey selectedStrategy = withReason.strategyMetadata() == null
                ? StrategyKey.UNKNOWN
                : withReason.strategyMetadata().selectedStrategy();

        if (selectedStrategy == StrategyKey.SESSION_RECALL) {
            return agentDispatchNode.executeTool(withReason, "get_session_memory", null, "session recall fallback");
        }
        if ((withReason.intent() == DlmsIntent.DOCUMENTATION || withReason.intent() == DlmsIntent.SECURITY_EXPLAIN)
                && (withReason.retrievalResults() == null || withReason.retrievalResults().isEmpty())) {
            return agentDispatchNode.executeTool(
                    withReason,
                    "search_docs",
                    withReason.rawInput(),
                    withReason.intent() == DlmsIntent.SECURITY_EXPLAIN ? "security fallback" : "documentation fallback"
            );
        }
        return withReason;
    }

    private Optional<PlannerDecision> parsePlannerDecision(String rawOutput) {
        String json = extractJsonObject(stripPlannerNoise(rawOutput));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String action = textValue(root, "action");
            String tool = textValue(root, "tool");
            String query = textValue(root, "query");
            String rationale = textValue(root, "rationale");
            return Optional.of(new PlannerDecision(action, tool, query, rationale));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String stripPlannerNoise(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }
        String cleaned = rawOutput
                .replaceAll("(?is)<think>.*?</think>", "")
                .replace("```json", "")
                .replace("```", "")
                .trim();
        return cleaned;
    }

    private String extractJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return value.substring(start, end + 1);
    }

    private String textValue(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String simplify(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown planner error";
        }
        return throwable.getMessage();
    }

    private record PlannerDecision(
            String action,
            String tool,
            String query,
            String rationale
    ) {}
}
