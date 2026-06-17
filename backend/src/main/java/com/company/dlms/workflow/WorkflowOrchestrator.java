package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import com.company.dlms.infrastructure.security.EncryptionService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WorkflowOrchestrator {

    private static final Duration WORKFLOW_TIMEOUT = Duration.ofSeconds(30);

    private final CompiledGraph<AgentState> compiledGraph;
    private final EncryptionService encryptionService;

    public WorkflowOrchestrator(CompiledGraph<AgentState> compiledGraph, EncryptionService encryptionService) {
        this.compiledGraph = compiledGraph;
        this.encryptionService = encryptionService;
    }

    public Mono<WorkflowResult> execute(WorkflowRequest request) {
        return executeRaw(request).map(this::toResult);
    }
 
    public Mono<WorkflowState> executeRaw(WorkflowRequest request) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        final String finalSessionId = sessionId;
        final String finalConversationId = conversationId;

        // Constitution IV: RBAC at agent dispatch level. Read role from SecurityContext reactively.
        // Falls back to request.userRole() when no security context (tests, public paths).
        Mono<String> roleMono = ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith("ROLE_"))
                        .map(a -> a.substring(5))
                        .findFirst()
                        .orElse("VIEWER"))
                .defaultIfEmpty(request.userRole() != null ? request.userRole() : "VIEWER");

        return roleMono.flatMap(role -> {
            // T030: encrypt rawInput at the workflow entry boundary (AES-128-CBC).
            // The graph processes the plaintext; encrypted form is used for any persistence call sites.
            String encryptedRaw = encryptionService.encrypt(request.rawInput());

            WorkflowState initial = WorkflowState.empty(finalSessionId, finalConversationId, request.rawInput())
                    .toBuilder()
                    .userRole(role)
                    .siconiaNormalization(request.siconiaNormalization())
                    .dlmsNormalization(request.dlmsNormalization())
                    .inputClass(request.inputClass())
                    .intent(request.intentHint())
                    .strategyMetadata(request.strategyMetadata())
                    .orchestrationMode(request.orchestrationMode())
                    .startTimeMs(System.currentTimeMillis())
                    .build();

            return Mono.fromCallable(() -> compiledGraph.invoke(Map.of(DlmsWorkflowGraph.WORKFLOW_STATE_KEY, initial)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(WORKFLOW_TIMEOUT)
                    .map(optional -> optional.orElseThrow(() -> new IllegalStateException("Workflow graph produced no final state")))
                    .map(this::extractWorkflowState);
        });
    }

    private WorkflowState extractWorkflowState(AgentState agentState) {
        Object value = agentState.data().get(DlmsWorkflowGraph.WORKFLOW_STATE_KEY);
        if (value instanceof WorkflowState wf) {
            return wf;
        }
        throw new IllegalStateException("Missing WorkflowState in final AgentState");
    }

    private WorkflowResult toResult(WorkflowState state) {
        DlmsIntent intent = state.intent();
        InputClass inputClass = state.inputClass();
        String explanation = state.explanation();
        boolean outputFiltered = state.outputFiltered();
        boolean mcpUsed = state.mcpUsed();
        List<String> anomalies = state.anomalies() == null ? List.of() : state.anomalies();
        List<String> errors = state.errors() == null ? List.of() : state.errors();

        return new WorkflowResult(
                state.sessionId(),
                intent,
                inputClass,
                explanation,
                outputFiltered,
                mcpUsed,
                anomalies,
                errors
        );
    }
}
