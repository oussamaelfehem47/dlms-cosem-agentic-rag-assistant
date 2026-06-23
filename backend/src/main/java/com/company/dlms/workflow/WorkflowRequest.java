package com.company.dlms.workflow;

import com.company.dlms.domain.InputClass;
import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import com.company.dlms.domain.orchestration.OrchestrationMode;

import java.util.List;

public record WorkflowRequest(
        String sessionId,
        String conversationId,
        String rawInput,
        List<TurnArtifact> artifacts,
        String userRole,
        InputClass inputClass,
        DlmsIntent intentHint,
        StrategyMetadata strategyMetadata,
        SiconiaInputNormalization siconiaNormalization,
        DlmsInputNormalization dlmsNormalization,
        OrchestrationMode orchestrationMode
){
    public WorkflowRequest {
        rawInput = rawInput == null ? "" : rawInput;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public WorkflowRequest(String sessionId, String conversationId, String rawInput, String userRole) {
        this(sessionId, conversationId, rawInput, null, userRole, null, null, null, null, null, null);
    }

    public WorkflowRequest(String sessionId, String conversationId, String rawInput, String userRole, InputClass inputClass) {
        this(sessionId, conversationId, rawInput, null, userRole, inputClass, null, null, null, null, null);
    }

    public WorkflowRequest(
            String sessionId,
            String conversationId,
            String rawInput,
            String userRole,
            InputClass inputClass,
            SiconiaInputNormalization siconiaNormalization
    ) {
        this(sessionId, conversationId, rawInput, null, userRole, inputClass, null, null, siconiaNormalization, null, null);
    }

    public WorkflowRequest(
            String sessionId,
            String conversationId,
            String rawInput,
            List<TurnArtifact> artifacts,
            String userRole,
            InputClass inputClass
    ) {
        this(sessionId, conversationId, rawInput, artifacts, userRole, inputClass, null, null, null, null, null);
    }

    public String analysisInput() {
        if (siconiaNormalization != null && siconiaNormalization.normalizedInput() != null) {
            return siconiaNormalization.normalizedInput();
        }
        if (dlmsNormalization != null && dlmsNormalization.normalizedInput() != null) {
            return dlmsNormalization.normalizedInput();
        }
        return rawInput;
    }
}
