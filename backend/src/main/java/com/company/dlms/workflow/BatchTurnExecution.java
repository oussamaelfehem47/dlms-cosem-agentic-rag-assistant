package com.company.dlms.workflow;

import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.rag.RetrievalResult;

import java.io.Serializable;
import java.util.List;

public record BatchTurnExecution(
        String sessionId,
        String conversationId,
        TurnArtifactExtraction extraction,
        List<WorkflowState> artifactStates,
        String summaryText,
        StrategyMetadata strategyMetadata,
        OrchestrationMode orchestrationMode,
        boolean plannerUsed,
        List<ToolTraceEntry> toolTrace,
        String plannerFallbackReason,
        List<RetrievalResult> retrievalResults
) implements Serializable {}
