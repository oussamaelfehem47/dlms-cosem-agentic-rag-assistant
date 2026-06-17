package com.company.dlms.agent;

import com.company.dlms.infrastructure.reflection.ReflectionService;
import com.company.dlms.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

@Component
public class ReflectionAgentNode {

    private static final Logger log = LoggerFactory.getLogger(ReflectionAgentNode.class);

    private final ReflectionService reflectionService;

    public ReflectionAgentNode(ReflectionService reflectionService) {
        this.reflectionService = reflectionService;
    }

    public WorkflowState process(WorkflowState state) {
        try {
            reflectionService.recordExecution(state)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            v -> {},
                            ex -> log.warn("reflection write failed: {}", ex.getMessage())
                    );
        } catch (Exception ex) {
            log.warn("reflection agent error: {}", ex.getMessage());
        }
        return state;
    }
}
