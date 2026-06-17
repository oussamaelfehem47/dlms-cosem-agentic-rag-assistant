package com.company.dlms.agent;

import com.company.dlms.agent.siconia.AlarmDecoder;
import com.company.dlms.agent.siconia.LogClassifier;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;
import com.company.dlms.agent.siconia.SiconiaInputNormalizer;
import com.company.dlms.agent.siconia.XmlTraceParser;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.infrastructure.mcp.McpClient;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiconiaAgentNodeTest {

    @Test
    void xml_trace_state_routes_to_xml_trace_parser() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);
        WorkflowState state = baseState("<trace><event type=\"ALARM\" code=\"0x0001\" timestamp=\"t\" deviceId=\"d\" errorCode=\"e\"/></trace>")
                .withInputClass(InputClass.XML_TRACE);

        WorkflowState out = node.process(state);
        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().xmlTrace()).isNotNull();
        // Alarm codes found in XML events are now decoded automatically
        assertThat(out.siconiaResult().alarmResults()).isNotNull();
        assertThat(out.siconiaResult().alarmResults()).isNotEmpty();
        assertThat(out.siconiaResult().alarmResults().getFirst().code()).isEqualTo("0x0001");
        assertThat(out.siconiaResult().alarmResults().getFirst().rootCause()).isEqualTo("Power failure");
        assertThat(out.siconiaResult().logAnalysis()).isNull();
    }

    @Test
    void alarm_code_state_routes_to_alarm_decoder() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);
        WorkflowState state = baseState("0x0008").withInputClass(InputClass.ALARM_CODE);

        WorkflowState out = node.process(state);
        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().alarmResults()).isNotNull();
        assertThat(out.siconiaResult().alarmResults()).isNotEmpty();
        assertThat(out.siconiaResult().xmlTrace()).isNull();
        assertThat(out.siconiaResult().logAnalysis()).isNull();
    }

    @Test
    void log_block_state_routes_to_log_classifier() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);
        WorkflowState state = baseState("WAN timeout").withInputClass(InputClass.LOG_BLOCK);

        WorkflowState out = node.process(state);
        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().logAnalysis()).isNotNull();
        assertThat(out.siconiaResult().xmlTrace()).isNull();
        assertThat(out.siconiaResult().alarmResults()).isNull();
    }

    @Test
    void malformed_xml_is_handled_by_parser_state_has_no_new_errors() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);
        WorkflowState state = baseState("<trace><event type=\"ALARM\"></trace>")
                .withInputClass(InputClass.XML_TRACE);

        WorkflowState out = node.process(state);
        assertThat(out.errors()).isEmpty();
        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().xmlTrace()).isNotNull();
        assertThat(out.siconiaResult().xmlTrace().parseErrors()).isNotEmpty();
    }

    @Test
    void runtime_exception_in_parser_sets_state_error_and_never_throws() {
        XmlTraceParser throwingParser = new XmlTraceParser() {
            @Override
            public com.company.dlms.domain.siconia.SiconiaXmlTrace parse(String xml) {
                throw new RuntimeException("boom");
            }
        };
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(throwingParser, new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);
        WorkflowState state = baseState("<trace/>").withInputClass(InputClass.XML_TRACE);

        WorkflowState out = node.process(state);
        assertThat(out.errors()).isNotEmpty();
        assertThat(out.errors().getFirst()).contains("boom");
    }

    @Test
    void xmlTraceResultCarriesHeuristicProvenanceFromNormalization() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);

        String wrapped = "Please inspect this trace:\n<Event timestamp=\"2024-01-15T10:30:00Z\"><Alarm code=\"0x1342\" severity=\"critical\"/><Source device=\"DCU-01\"/></Event>";
        SiconiaInputNormalization normalization = new SiconiaInputNormalizer().normalize(wrapped, InputClass.QUERY);
        WorkflowState state = baseState(wrapped)
                .toBuilder()
                .inputClass(InputClass.XML_TRACE)
                .siconiaNormalization(normalization)
                .build();

        WorkflowState out = node.process(state);

        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().processingMetadata()).isNotNull();
        assertThat(out.siconiaResult().processingMetadata().provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
        assertThat(out.siconiaResult().processingMetadata().normalizedInputClass()).isEqualTo(InputClass.XML_TRACE);
        assertThat(out.siconiaResult().xmlTrace().events()).hasSize(1);
    }

    @Test
    void mixedXmlEventsOnlyDecodeAlarmEventsIntoAlarmResults() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);

        String xml = """
                <trace>
                  <event type="ALARM" code="0x1342" deviceId="DCU-007"/>
                  <event type="CONNECT" deviceId="DCU-007" timestamp="2024-04-22T10:01:00"/>
                </trace>
                """;

        WorkflowState out = node.process(baseState(xml).withInputClass(InputClass.XML_TRACE));

        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().xmlTrace()).isNotNull();
        assertThat(out.siconiaResult().xmlTrace().events()).hasSize(2);
        assertThat(out.siconiaResult().alarmResults()).isNotNull();
        assertThat(out.siconiaResult().alarmResults()).hasSize(1);
        assertThat(out.siconiaResult().alarmResults().getFirst().code()).isEqualTo("0x1342");
    }

    @Test
    void unknownButValidXmlFallsBackToRawInterpretationMetadata() {
        McpDispatcher mcpDispatcher = dispatcherReturning((tool, params) -> Mono.just(McpResult.unavailable(tool)));
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        when(sessionNarrativeService.appendEvent(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        SiconiaAgentNode node = new SiconiaAgentNode(
                new XmlTraceParser(), new AlarmDecoder(), new LogClassifier(), mcpDispatcher, new SessionEventService(), sessionNarrativeService);

        String xml = "<Envelope><Payload><foo bar=\"baz\"/></Payload></Envelope>";
        WorkflowState state = baseState(xml)
                .toBuilder()
                .inputClass(InputClass.XML_TRACE)
                .siconiaNormalization(new SiconiaInputNormalizer().normalize(xml, InputClass.XML_TRACE))
                .build();

        WorkflowState out = node.process(state);

        assertThat(out.siconiaResult()).isNotNull();
        assertThat(out.siconiaResult().processingMetadata()).isNotNull();
        assertThat(out.siconiaResult().processingMetadata().provenance()).isEqualTo(ParseProvenance.RAW_FALLBACK);
        assertThat(out.siconiaResult().processingMetadata().warnings()).isNotEmpty();
    }

    @Test
    void queryStyleSiconiaDocumentationInputIsNotNormalizedAsAlarmCode() {
        SiconiaInputNormalization normalization = new SiconiaInputNormalizer()
                .normalize("What is Local operations in SICONIA?", InputClass.QUERY);

        assertThat(normalization).isNull();
    }

    @Test
    void namedAlarmTokenInMeaningQueryStillNormalizesHeuristically() {
        SiconiaInputNormalization normalization = new SiconiaInputNormalizer()
                .normalize("what does DCU_COMM_FAIL mean", InputClass.QUERY);

        assertThat(normalization).isNotNull();
        assertThat(normalization.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(normalization.normalizedInput()).isEqualTo("DCU_COMM_FAIL");
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }

    private static WorkflowState baseState(String rawInput) {
        return WorkflowState.empty("s1", "c1", rawInput);
    }

    private static McpDispatcher dispatcherReturning(BiFunction<String, java.util.Map<String, Object>, Mono<McpResult>> handler) {
        return new McpDispatcher(new McpClient(WebClient.builder().baseUrl("http://localhost").build(), new ObjectMapper())) {
            @Override
            public Mono<McpResult> dispatch(String tool, java.util.Map<String, Object> params) {
                return handler.apply(tool, params);
            }
        };
    }
}
