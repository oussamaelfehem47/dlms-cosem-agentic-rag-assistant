package com.company.dlms.agent;

import com.company.dlms.agent.siconia.AlarmDecoder;
import com.company.dlms.agent.siconia.LogClassifier;
import com.company.dlms.agent.siconia.XmlTraceParser;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.siconia.*;
import com.company.dlms.infrastructure.mcp.McpClient;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SiconiaAgentNodeMcpTest {

    private McpDispatcher mcpDispatcher;
    private SiconiaAgentNode node;
    private ObjectMapper objectMapper;
    private SessionEventService sessionEventService;
    private com.company.dlms.memory.SessionNarrativeService sessionNarrativeService;

    @BeforeEach
    void setUp() {
        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        objectMapper = new ObjectMapper();
        sessionEventService = new SessionEventService();
        sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        
        when(sessionNarrativeService.appendEvent(any())).thenReturn(Mono.empty());
        
        node = new SiconiaAgentNode(
                new XmlTraceParser(), 
                new AlarmDecoder(), 
                new LogClassifier(), 
                mcpDispatcher, 
                sessionEventService, 
                sessionNarrativeService
        );
    }

    // ── ALARM_CODE tests ──────────────────────────────────────────────────────

    @Test
    void alarmCode_mcpSuccess_returnsMcpResult_mcpUsedTrue() throws Exception {
        JsonNode mcpJson = objectMapper.createObjectNode()
                .put("code", "0x0008")
                .put("severity", "CRITICAL")
                .put("root_cause", "WAN link down")
                .put("remediation", "Check WAN cable")
                .put("affected_component", "WAN");

        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.success(tool, mcpJson)));
        node = new SiconiaAgentNode(new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, sessionEventService, sessionNarrativeService);

        WorkflowState state = WorkflowState.empty("s1", "c1", "0x0008")
                .withInputClass(InputClass.ALARM_CODE);
        WorkflowState out = node.process(state);

        assertNotNull(out.siconiaResult());
        assertNotNull(out.siconiaResult().alarmResults());
        assertFalse(out.siconiaResult().alarmResults().isEmpty());
        assertEquals("0x0008", out.siconiaResult().alarmResults().getFirst().code());
    }

    @Test
    void alarmCode_mcpFailure_callsJavaDecoder() {
        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.failure(tool, "Connection refused")));
        node = new SiconiaAgentNode(new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, sessionEventService, sessionNarrativeService);

        WorkflowState state = WorkflowState.empty("s1", "c1", "0x0008")
                .withInputClass(InputClass.ALARM_CODE);
        WorkflowState out = node.process(state);

        assertNotNull(out.siconiaResult());
        assertNotNull(out.siconiaResult().alarmResults());
    }

    // ── XML_TRACE tests ───────────────────────────────────────────────────────

    @Test
    void xmlTrace_mcpSuccess_returnsMcpResult_mcpUsedTrue() throws Exception {
        JsonNode mcpJson = objectMapper.createObjectNode()
                .put("sessions", 2)
                .put("total_events", 10)
                .put("alarm_count", 1)
                .putArray("parse_errors");

        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.success(tool, mcpJson)));
        node = new SiconiaAgentNode(new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, sessionEventService, sessionNarrativeService);

        WorkflowState state = WorkflowState.empty("s1", "c1", "<trace/>")
                .withInputClass(InputClass.XML_TRACE);
        WorkflowState out = node.process(state);

        assertNotNull(out.siconiaResult());
        assertNotNull(out.siconiaResult().xmlTrace());
    }

    @Test
    void xmlTrace_mcpFailure_callsJavaParser() {
        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.failure(tool, "Timeout")));
        node = new SiconiaAgentNode(new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, sessionEventService, sessionNarrativeService);

        WorkflowState state = WorkflowState.empty("s1", "c1", "<trace/>")
                .withInputClass(InputClass.XML_TRACE);
        WorkflowState out = node.process(state);

        assertNotNull(out.siconiaResult());
        assertNotNull(out.siconiaResult().xmlTrace());
    }

    // ── LOG_BLOCK tests ───────────────────────────────────────────────────────

    @Test
    void logBlock_mcpSuccess_returnsMcpResult_mcpUsedTrue() throws Exception {
        // Python tool returns: {"total_lines": N, "layers": {"WAN": 5, ...}, "severities": {"ERROR": 2, ...}}
        ObjectNode layers = objectMapper.createObjectNode();
        layers.put("WAN", 30);
        layers.put("PLC", 20);

        ObjectNode severities = objectMapper.createObjectNode();
        severities.put("ERROR", 2);
        severities.put("WARN", 10);
        severities.put("INFO", 38);

        ObjectNode mcpJson = objectMapper.createObjectNode();
        mcpJson.put("total_lines", 50);
        mcpJson.put("error_count", 5);
        mcpJson.set("layers", layers);
        mcpJson.set("severities", severities);

        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.success(tool, mcpJson)));
        node = new SiconiaAgentNode(new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, sessionEventService, sessionNarrativeService);

        WorkflowState state = WorkflowState.empty("s1", "c1", "WAN timeout")
                .withInputClass(InputClass.LOG_BLOCK);
        WorkflowState out = node.process(state);

        assertNotNull(out.siconiaResult());
        assertNotNull(out.siconiaResult().logAnalysis());
        assertEquals(LogLayer.WAN, out.siconiaResult().logAnalysis().dominantLayer());
        assertEquals(LogSeverity.ERROR, out.siconiaResult().logAnalysis().highestSeverity());
        assertEquals(50, out.siconiaResult().logAnalysis().lineCount());
    }

    @Test
    void logBlock_mcpFailure_callsJavaClassifier() {
        mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        node = new SiconiaAgentNode(new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, sessionEventService, sessionNarrativeService);

        WorkflowState state = WorkflowState.empty("s1", "c1", "WAN timeout")
                .withInputClass(InputClass.LOG_BLOCK);
        WorkflowState out = node.process(state);

        assertNotNull(out.siconiaResult());
        assertNotNull(out.siconiaResult().logAnalysis());
    }

    private static McpDispatcher dispatcherReturning(BiFunction<String, Map<String, Object>, Mono<McpResult>> handler) {
        return new McpDispatcher(new McpClient(WebClient.builder().baseUrl("http://localhost").build(), new ObjectMapper())) {
            @Override
            public Mono<McpResult> dispatch(String tool, Map<String, Object> params) {
                return handler.apply(tool, params);
            }
        };
    }
}
