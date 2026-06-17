package com.company.dlms.agent;

import com.company.dlms.workflow.WorkflowState;

public interface AgentNode {
    WorkflowState process(WorkflowState state);
}
