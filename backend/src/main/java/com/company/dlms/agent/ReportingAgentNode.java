package com.company.dlms.agent;

import com.company.dlms.infrastructure.llm.PromptAssembler;
import com.company.dlms.workflow.WorkflowState;
import org.springframework.stereotype.Component;

@Component
public class ReportingAgentNode {

    private final PromptAssembler promptAssembler;

    public ReportingAgentNode(PromptAssembler promptAssembler) {
        this.promptAssembler = promptAssembler;
    }

    public WorkflowState process(WorkflowState state) {
        String prompt = promptAssembler.assemble(state);
        return state.withLlmPrompt(prompt);
    }
}

