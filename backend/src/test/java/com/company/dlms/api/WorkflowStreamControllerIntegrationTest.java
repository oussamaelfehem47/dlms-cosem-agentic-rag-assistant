package com.company.dlms.api;

import com.company.dlms.agent.dlms.DlmsInputNormalizer;
import com.company.dlms.agent.siconia.SiconiaInputNormalizer;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.DlmsProcessingMetadata;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.orchestration.StrategyCandidate;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.infrastructure.llm.MathMarkupFilter;
import com.company.dlms.infrastructure.llm.GroundedAnswerQualityGate;
import com.company.dlms.infrastructure.llm.GroundedFactBundleBuilder;
import com.company.dlms.infrastructure.llm.OllamaStreamingClient;
import com.company.dlms.infrastructure.llm.PromptAssembler;
import com.company.dlms.infrastructure.llm.ThinkTagFilter;
import com.company.dlms.infrastructure.security.AuditService;
import com.company.dlms.infrastructure.security.JwtAuthFilter;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.OutputFilter;
import com.company.dlms.agent.RouterAgent;
import com.company.dlms.workflow.InputUnderstandingService;
import com.company.dlms.workflow.FollowUpResolver;
import com.company.dlms.workflow.GroundedAnswerBuilder;
import com.company.dlms.workflow.HybridAgenticPlannerService;
import com.company.dlms.workflow.MultiArtifactTurnOrchestrator;
import com.company.dlms.workflow.StreamingWorkflowService;
import com.company.dlms.workflow.TurnArtifactExtractionService;
import com.company.dlms.workflow.TurnSynthesisPlannerService;
import com.company.dlms.workflow.WorkflowOrchestrator;
import com.company.dlms.workflow.WorkflowRequest;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(
    controllers = WorkflowStreamController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration.class
    }
)
@Import({
    InputValidator.class,
    StreamingWorkflowService.class,
    ThinkTagFilter.class,
    MathMarkupFilter.class,
    GroundedFactBundleBuilder.class,
    GroundedAnswerQualityGate.class,
    OutputFilter.class,
    ObjectMapper.class,
    SiconiaInputNormalizer.class,
    DlmsInputNormalizer.class,
    RouterAgent.class,
    InputUnderstandingService.class,
    FollowUpResolver.class,
    GroundedAnswerBuilder.class,
    TurnArtifactExtractionService.class,
    TurnSynthesisPlannerService.class,
    MultiArtifactTurnOrchestrator.class
})
class WorkflowStreamControllerIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private WorkflowOrchestrator orchestrator;
    @MockBean
    private PromptAssembler promptAssembler;
    @MockBean
    private OllamaStreamingClient ollamaStreamingClient;
    @MockBean
    private HybridAgenticPlannerService hybridAgenticPlannerService;
    @MockBean
    private AuditService auditService;
    @MockBean
    private JwtAuthFilter jwtAuthFilter;
    @MockBean
    private JwtService jwtService;

    @BeforeEach
    void setupSecurityPassthrough() {
        // Configure the mock filter to pass through all requests with an ENGINEER security context
        // so the RBAC path rules in SecurityConfig are satisfied.
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-user-id", null,
                List.of(new SimpleGrantedAuthority("ROLE_ENGINEER")));
        when(jwtAuthFilter.filter(any(ServerWebExchange.class), any(WebFilterChain.class)))
                .thenAnswer(inv -> {
                    WebFilterChain chain = inv.getArgument(1);
                    ServerWebExchange exchange = inv.getArgument(0);
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                });
        when(hybridAgenticPlannerService.applyIfNeeded(any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void unifiedChatEndpointUsesBackendUnderstandingAndEmitsDecodeFirstForWrappedAxdr() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain AXDR payload 1907E80416010E1E0000003C00")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .strategyMetadata(new StrategyMetadata(
                        StrategyKey.DLMS_AXDR_DECODE,
                        StrategyKey.DLMS_AXDR_DECODE.label(),
                        0.88,
                        false,
                        false,
                        List.of(new StrategyCandidate(
                                StrategyKey.DLMS_AXDR_DECODE,
                                StrategyKey.DLMS_AXDR_DECODE.label(),
                                0.88,
                                "Recovered an AXDR payload candidate from wrapped prose input.",
                                true,
                                false,
                                InputClass.QUERY.name(),
                                DlmsNormalizedKind.AXDR_HEX.name(),
                                ParseProvenance.STRUCTURED_HEURISTIC.name(),
                                "1907E80416010E1E0000003C00",
                                List.of()
                        )),
                        List.of()
                ))
                .build();

        when(orchestrator.executeRaw(any())).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just("tok"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post()
                .uri("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "Explain AXDR payload 1907E80416010E1E0000003C00", "viewer", InputClass.QUERY))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<>() {});

        List<ServerSentEvent<String>> events = result.getResponseBody().collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().event()).isEqualTo("decode");
        assertThat(events.getFirst().data()).contains("strategyMetadata");
        assertThat(events.getFirst().data()).contains("DLMS_AXDR_DECODE");
    }

    @Test
    void decodeEndpointOrderIsDecodeThenTokenThenDone() {
        stubBase(InputClass.HEX_FRAME, DlmsIntent.FRAME_DECODE, Flux.just("tok"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post()
                .uri("/api/decode/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "7EA023210313A5E57E", "viewer", InputClass.HEX_FRAME))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<>() {});

        List<String> events = result.getResponseBody().map(ServerSentEvent::event).collectList().block();
        assertThat(events).containsExactly("decode", "token", "done");
    }

    @Test
    void siconiaEndpointFirstEventIsAnalysis() {
        stubBase(InputClass.LOG_BLOCK, DlmsIntent.SICONIA_TROUBLESHOOT, Flux.just("tok"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post()
                .uri("/api/siconia/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "log entry", "viewer", InputClass.LOG_BLOCK))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<>() {});

        List<String> events = result.getResponseBody().map(ServerSentEvent::event).collectList().block();
        assertThat(events.getFirst()).isEqualTo("analysis");
    }

    @Test
    void thinkTagsFromModelNeverReachClient() {
        stubBase(InputClass.HEX_FRAME, DlmsIntent.FRAME_DECODE, Flux.just("<think>hidden</think>", "visible"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post()
                .uri("/api/decode/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "7EA023210313A5E57E", "viewer", InputClass.HEX_FRAME))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<>() {});

        List<String> payloads = result.getResponseBody().map(ServerSentEvent::data).collectList().block();
        assertThat(payloads.toString()).doesNotContain("<think>").doesNotContain("hidden");
    }

    @Test
    void hexInModelOutputIsRedacted() {
        stubBase(InputClass.HEX_FRAME, DlmsIntent.FRAME_DECODE,
                Flux.just("key=0123456789abcdef0123456789abcdef"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post()
                .uri("/api/decode/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "7EA023210313A5E57E", "viewer", InputClass.HEX_FRAME))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<>() {});

        List<String> payloads = result.getResponseBody().map(ServerSentEvent::data).collectList().block();
        assertThat(payloads.toString()).contains("[REDACTED-KEY]");
    }

    @Test
    void decodeEventIncludesDlmsProcessingMetadata() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode this HDLC frame: 7EA00A030383CD6F7E")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.HEX_FRAME)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.U_FRAME, null, null, 1, 1, new byte[0], true, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA00A030383CD6F7E",
                        List.of(),
                        new DlmsProcessingMetadata(
                                DlmsNormalizedKind.FRAME_HEX,
                                ParseProvenance.STRUCTURED_HEURISTIC,
                                List.of(),
                                "Recovered embedded HDLC frame from wrapped prose input"
                        )
                ))
                .build();

        when(orchestrator.executeRaw(any())).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just("tok"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post()
                .uri("/api/decode/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "Decode this HDLC frame: 7EA00A030383CD6F7E", "viewer", InputClass.QUERY))
                .exchange()
                .expectStatus().isOk()
                .returnResult(new org.springframework.core.ParameterizedTypeReference<>() {});

        List<String> payloads = result.getResponseBody().map(ServerSentEvent::data).collectList().block();
        assertThat(payloads).isNotEmpty();
        assertThat(payloads.getFirst()).contains("decodeProcessingMetadata");
        assertThat(payloads.getFirst()).contains("STRUCTURED_HEURISTIC");
    }

    private void stubBase(InputClass inputClass, DlmsIntent intent, Flux<String> modelFlux) {
        WorkflowState state = WorkflowState.empty("s1", "c1", "input")
                .toBuilder()
                .intent(intent)
                .inputClass(inputClass)
                .build();

        when(orchestrator.executeRaw(any())).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(modelFlux);
    }
}
