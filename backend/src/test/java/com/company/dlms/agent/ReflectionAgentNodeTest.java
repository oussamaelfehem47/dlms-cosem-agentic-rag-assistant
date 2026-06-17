package com.company.dlms.agent;

import com.company.dlms.infrastructure.reflection.ReflectionService;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReflectionAgentNodeTest {

    @Mock
    private ReflectionService reflectionService;

    @InjectMocks
    private ReflectionAgentNode reflectionAgentNode;

    @Test
    void process_returnsStateUnchanged() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "input");
        when(reflectionService.recordExecution(any())).thenReturn(Mono.empty());

        WorkflowState result = reflectionAgentNode.process(state);

        assertThat(result).isSameAs(state);
    }

    @Test
    void process_callsRecordExecution() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "input");
        when(reflectionService.recordExecution(any())).thenReturn(Mono.empty());

        reflectionAgentNode.process(state);

        verify(reflectionService, times(1)).recordExecution(state);
    }

    @Test
    void process_returnsStateUnchanged_whenServiceThrows() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "input");
        when(reflectionService.recordExecution(any()))
                .thenThrow(new RuntimeException("DB unreachable"));

        WorkflowState result = reflectionAgentNode.process(state);

        assertThat(result).isSameAs(state);
    }
}
