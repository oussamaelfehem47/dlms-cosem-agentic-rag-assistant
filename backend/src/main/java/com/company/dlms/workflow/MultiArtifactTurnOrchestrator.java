package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MultiArtifactTurnOrchestrator {

    private final InputUnderstandingService inputUnderstandingService;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final HybridAgenticPlannerService hybridAgenticPlannerService;
    private final TurnSynthesisPlannerService turnSynthesisPlannerService;

    public MultiArtifactTurnOrchestrator(
            InputUnderstandingService inputUnderstandingService,
            WorkflowOrchestrator workflowOrchestrator,
            HybridAgenticPlannerService hybridAgenticPlannerService,
            TurnSynthesisPlannerService turnSynthesisPlannerService
    ) {
        this.inputUnderstandingService = Objects.requireNonNull(inputUnderstandingService, "inputUnderstandingService");
        this.workflowOrchestrator = Objects.requireNonNull(workflowOrchestrator, "workflowOrchestrator");
        this.hybridAgenticPlannerService = Objects.requireNonNull(hybridAgenticPlannerService, "hybridAgenticPlannerService");
        this.turnSynthesisPlannerService = Objects.requireNonNull(turnSynthesisPlannerService, "turnSynthesisPlannerService");
    }

    public Mono<BatchTurnExecution> execute(
            WorkflowRequest request,
            TurnArtifactExtraction extraction
    ) {
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();

        return Flux.fromIterable(extraction.artifacts())
                .concatMap(artifact -> executeArtifact(request, artifact, extraction, sessionId, conversationId))
                .collectList()
                .flatMap(states -> turnSynthesisPlannerService.synthesize(extraction, states)
                        .map(summary -> buildExecution(request, extraction, states, summary, sessionId, conversationId)));
    }

    private Mono<WorkflowState> executeArtifact(
            WorkflowRequest request,
            TurnArtifact artifact,
            TurnArtifactExtraction extraction,
            String sessionId,
            String conversationId
    ) {
        String artifactRawInput = buildArtifactRawInput(artifact, extraction);
        InputClass hintedInputClass = artifact.hintedInputClass() == null ? InputClass.QUERY : artifact.hintedInputClass();
        InputUnderstanding understanding = inputUnderstandingService.understand(artifactRawInput, hintedInputClass);
        WorkflowRequest artifactRequest = new WorkflowRequest(
                sessionId,
                conversationId,
                artifactRawInput,
                List.of(),
                request.userRole(),
                understanding.inputClass(),
                understanding.intent(),
                understanding.strategyMetadata(),
                understanding.siconiaNormalization(),
                understanding.dlmsNormalization(),
                understanding.orchestrationMode()
        );
        return workflowOrchestrator.executeRaw(artifactRequest)
                .flatMap(hybridAgenticPlannerService::applyIfNeeded);
    }

    private String buildArtifactRawInput(TurnArtifact artifact, TurnArtifactExtraction extraction) {
        if (extraction.artifacts().size() == 1 && extraction.turnInstruction() != null && !extraction.turnInstruction().isBlank()) {
            return artifact.text().trim() + "\n\n" + extraction.turnInstruction().trim();
        }
        return artifact.text().trim();
    }

    private BatchTurnExecution buildExecution(
            WorkflowRequest request,
            TurnArtifactExtraction extraction,
            List<WorkflowState> states,
            TurnSynthesisPlannerService.TurnSynthesisResult summary,
            String sessionId,
            String conversationId
    ) {
        boolean plannerUsed = states.stream().anyMatch(WorkflowState::plannerUsed)
                || summary.plannerUsed();
        OrchestrationMode mode = extraction.turnInstruction() == null || extraction.turnInstruction().isBlank()
                ? OrchestrationMode.DETERMINISTIC_FAST_PATH
                : OrchestrationMode.STRUCTURED_PLUS_AGENTIC;
        StrategyMetadata metadata = new StrategyMetadata(
                StrategyKey.UNKNOWN,
                "Multi-artifact turn",
                0.98,
                false,
                false,
                List.of(),
                List.of()
        );
        return new BatchTurnExecution(
                sessionId,
                conversationId,
                extraction,
                states,
                summary.text(),
                metadata,
                mode,
                plannerUsed,
                turnSynthesisPlannerService.aggregateToolTrace(states),
                null,
                states.stream()
                        .flatMap(state -> state.retrievalResults() == null ? java.util.stream.Stream.empty() : state.retrievalResults().stream())
                        .toList()
        );
    }
}
