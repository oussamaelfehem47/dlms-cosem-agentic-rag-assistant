package com.company.dlms.workflow;

import com.company.dlms.agent.ReflectionAgentNode;
import com.company.dlms.agent.RouterAgent;
import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.domain.RouterResult;
import com.company.dlms.agent.ReportingAgentNode;
import com.company.dlms.memory.SessionNarrativeService;
import com.company.dlms.memory.StmService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class DlmsWorkflowGraph {

    public static final String WORKFLOW_STATE_KEY = "workflowState";
    private static final String START = "__START__";
    private static final String END = "__END__";

    @Bean
    public CompiledGraph<AgentState> compiledDlmsWorkflowGraph(
            RouterAgent routerAgent,
            StmService stmService,
            SessionNarrativeService sessionNarrativeService,
            AgentDispatchNode agentDispatchNode,
            com.company.dlms.agent.AnomalyDetectionNode anomalyDetectionNode,
            ReportingAgentNode reportingAgentNode,
            ReflectionAgentNode reflectionAgentNode
    ) throws GraphStateException {
        AgentStateFactory<AgentState> factory = AgentState::new;
        StateGraph<AgentState> graph = new StateGraph<>(factory);

        graph.addNode("router", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            if (wf.strategyMetadata() != null && wf.intent() != null && wf.inputClass() != null) {
                return Map.of(WORKFLOW_STATE_KEY, wf);
            }
            RouterResult r = routerAgent.route(wf.analysisInput(), wf.inputClass(), wf.dlmsNormalization());
            WorkflowState next = wf.toBuilder()
                    .inputClass(r.inputClass())
                    .intent(r.intent())
                    .build();
            return Map.of(WORKFLOW_STATE_KEY, next);
        }));

        graph.addNode("load_stm", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            WorkflowState loaded = stmService.loadStmSync(wf);
            // US3: Capture pre-decode snapshot for anomaly detection
            WorkflowState next = loaded.toBuilder()
                    .stmSnapshot(StmSnapshot.from(loaded))
                    .build();
            return Map.of(WORKFLOW_STATE_KEY, next);
        }));

        graph.addNode("load_narrative", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            WorkflowState next = sessionNarrativeService.loadNarrativeSync(wf);
            return Map.of(WORKFLOW_STATE_KEY, next);
        }));

        graph.addNode("dispatch", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            // dispatch() also handles retrieval-ready preprocessing for retrieval-only intents,
            // including SECURITY_EXPLAIN via SecurityAgentNode and documentation/OBIS via RetrievalAgentNode.
            WorkflowState next = agentDispatchNode.dispatch(wf);
            return Map.of(WORKFLOW_STATE_KEY, next != null ? next : wf);
        }));

        // Retrieval is handled inside AgentDispatchNode.dispatch(); this node remains a pass-through
        // kept in the graph so the graph topology matches monitoring expectations
        graph.addNode("retrieval", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            return Map.of(WORKFLOW_STATE_KEY, wf);
        }));

        graph.addNode("anomaly_detection", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            WorkflowState next = anomalyDetectionNode.process(wf);
            return Map.of(WORKFLOW_STATE_KEY, next != null ? next : wf);
        }));

        graph.addNode("reporting", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            WorkflowState next = reportingAgentNode.process(wf);
            return Map.of(WORKFLOW_STATE_KEY, next != null ? next : wf);
        }));

        graph.addNode("reflection", AsyncNodeAction.node_async((NodeAction<AgentState>) state -> {
            WorkflowState wf = requireWorkflowState(state);
            WorkflowState next = reflectionAgentNode.process(wf);
            return Map.of(WORKFLOW_STATE_KEY, next != null ? next : wf);
        }));

        graph.addEdge(START, "router");
        graph.addEdge("router", "load_stm");
        graph.addEdge("load_stm", "load_narrative");
        graph.addEdge("load_narrative", "dispatch");
        graph.addEdge("dispatch", "retrieval");
        graph.addEdge("retrieval", "anomaly_detection");
        graph.addEdge("anomaly_detection", "reporting");
        graph.addEdge("reporting", "reflection");
        graph.addEdge("reflection", END);

        return graph.compile();
    }

    private static WorkflowState requireWorkflowState(AgentState agentState) {
        Object value = agentState.data().get(WORKFLOW_STATE_KEY);
        if (value instanceof WorkflowState wf) {
            return wf;
        }
        throw new IllegalStateException("Missing WorkflowState in AgentState under key " + WORKFLOW_STATE_KEY);
    }
}
