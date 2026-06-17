package com.company.dlms.workflow;

import com.company.dlms.agent.DecoderAgentNode;
import com.company.dlms.agent.ProfileAgentNode;
import com.company.dlms.agent.ReportingAgentNode;
import com.company.dlms.agent.RetrievalAgentNode;
import com.company.dlms.agent.SecurityAgentNode;
import com.company.dlms.agent.SiconiaAgentNode;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDispatchNodeTest {

    @Mock private DecoderAgentNode decoderAgentNode;
    @Mock private RetrievalAgentNode retrievalAgentNode;
    @Mock private SiconiaAgentNode siconiaAgentNode;
    @Mock private SecurityAgentNode securityAgentNode;
    @Mock private ProfileAgentNode profileAgentNode;
    @Mock private ReportingAgentNode reportingAgentNode;

    private AgentDispatchNode agentDispatchNode;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        agentDispatchNode = new AgentDispatchNode(
                decoderAgentNode,
                retrievalAgentNode,
                siconiaAgentNode,
                securityAgentNode,
                profileAgentNode,
                reportingAgentNode,
                new FollowUpResolver()
        );
    }

    @Test
    void dispatch_siconiaQuery_bypassesStructuredAnalysisAndUsesRetrievalOnly() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is Local operations in SICONIA?")
                .toBuilder()
                .intent(DlmsIntent.SICONIA_TROUBLESHOOT)
                .inputClass(InputClass.QUERY)
                .build();
        WorkflowState retrieved = state.withLlmPrompt("retrieved docs");

        when(retrievalAgentNode.process(state)).thenReturn(retrieved);

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(retrieved);
        verify(siconiaAgentNode, never()).process(state);
        verify(retrievalAgentNode).process(state);
    }

    @Test
    void dispatch_alarmCode_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "0x142")
                .toBuilder()
                .intent(DlmsIntent.SICONIA_TROUBLESHOOT)
                .inputClass(InputClass.ALARM_CODE)
                .build();
        WorkflowState analyzed = state.withExplanation("alarm analyzed");

        when(siconiaAgentNode.process(state)).thenReturn(analyzed);

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(analyzed);
        verify(siconiaAgentNode).process(state);
        verify(retrievalAgentNode, never()).process(analyzed);
    }

    @Test
    void dispatch_unknownGreetingQuery_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "hello")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
    }

    @Test
    void dispatch_unknownCourtesyQuery_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "thanks")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
    }

    @Test
    void dispatch_unknownTechnicalHelpQuery_stillUsesRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "help with hdlc")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();
        WorkflowState retrieved = state.withLlmPrompt("retrieved docs");

        when(retrievalAgentNode.process(state)).thenReturn(retrieved);

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(retrieved);
        verify(retrievalAgentNode).process(state);
    }

    @Test
    void dispatch_unknownCapabilityQuestion_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what can you do?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
    }

    @Test
    void dispatch_previousFrameRecallQuestion_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .orchestrationMode(OrchestrationMode.NATURAL_LANGUAGE_AGENTIC)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
        verify(decoderAgentNode, never()).process(state);
    }

    @Test
    void dispatch_genericMeaningFollowUp_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "what does that mean?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
        verify(decoderAgentNode, never()).process(state);
    }

    @Test
    void dispatch_failureReasonFollowUp_skipsRetrieval() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "why did it fail?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
        verify(decoderAgentNode, never()).process(state);
    }

    @Test
    void dispatch_documentationInNaturalLanguageAgenticModeDefersRetrievalToPlanner() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is AARQ in DLMS?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .orchestrationMode(OrchestrationMode.NATURAL_LANGUAGE_AGENTIC)
                .build();

        WorkflowState out = agentDispatchNode.dispatch(state);

        assertThat(out).isSameAs(state);
        verify(retrievalAgentNode, never()).process(state);
    }

    @Test
    void executeTool_searchDocsUsesRetrievalNodeAndAppendsTrace() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is AARQ in DLMS?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .build();
        WorkflowState searched = state.toBuilder().retrievalResults(List.of()).build();

        when(retrievalAgentNode.process(org.mockito.ArgumentMatchers.any())).thenReturn(searched);

        WorkflowState out = agentDispatchNode.executeTool(state, "search_docs", "What is AARQ in DLMS?", "documentation");

        assertThat(out.toolTrace()).isNotEmpty();
        assertThat(out.toolTrace().getFirst().toolName()).isEqualTo("search_docs");
        verify(retrievalAgentNode).process(org.mockito.ArgumentMatchers.any());
    }
}
