package com.company.dlms.agent;

import com.company.dlms.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ErrorNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(ErrorNode.class);

    @Override
    public WorkflowState process(WorkflowState state) {
        String errorSummary = state.errors() == null || state.errors().isEmpty()
                ? "unknown error"
                : String.join("; ", state.errors());
        log.warn("ErrorNode stub ran sessionId={} errors={}", state.sessionId(), errorSummary);
        return state.withExplanation("ERROR_NODE_STUB: " + errorSummary);
    }
}
