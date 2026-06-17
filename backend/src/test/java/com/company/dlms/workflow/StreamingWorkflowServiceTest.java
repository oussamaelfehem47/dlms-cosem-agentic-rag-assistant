package com.company.dlms.workflow;
 
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.answer.GroundedAnswerContext;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.domain.siconia.AffectedComponent;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.AlarmSeverity;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.domain.siconia.SiconiaProcessingMetadata;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.infrastructure.llm.MathMarkupFilter;
import com.company.dlms.infrastructure.llm.OllamaStreamingClient;
import com.company.dlms.infrastructure.llm.PromptAssembler;
import com.company.dlms.infrastructure.llm.ThinkTagFilter;
import com.company.dlms.infrastructure.security.OutputFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
 
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
 
@ExtendWith(MockitoExtension.class)
class StreamingWorkflowServiceTest {
 
    @Mock
    private WorkflowOrchestrator orchestrator;
    @Mock
    private AgentDispatchNode agentDispatchNode;
    @Mock
    private PromptAssembler promptAssembler;
    @Mock
    private OllamaStreamingClient ollamaStreamingClient;
 
    private StreamingWorkflowService service;
    private ObjectMapper objectMapper = new ObjectMapper();
 
    @BeforeEach
    void setUp() {
        service = new StreamingWorkflowService(
                orchestrator,
                new HybridAgenticPlannerService(
                        ollamaStreamingClient,
                        promptAssembler,
                        agentDispatchNode,
                        objectMapper
                ),
                new GroundedAnswerBuilder(new FollowUpResolver()),
                new FollowUpResolver(),
                promptAssembler,
                new com.company.dlms.infrastructure.llm.GroundedFactBundleBuilder(),
                new com.company.dlms.infrastructure.llm.GroundedAnswerQualityGate("lfm2.5-thinking"),
                ollamaStreamingClient,
                new ThinkTagFilter(),
                new MathMarkupFilter(),
                new OutputFilter(),
                objectMapper
        );
    }
 
    @Test
    void decodeOrderIsDecodeThenTokensThenDone() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "7EA023210313A5E57E", "viewer", InputClass.HEX_FRAME);
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA023210313A5E57E")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.HEX_FRAME)
                .build();
 
        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just("tok1 ", "tok2"));
 
        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::event))
                .expectNext("decode")
                .expectNextMatches(e -> e.equals("token"))
                .thenConsumeWhile(e -> e.equals("token"))
                .expectNext("done")
                .verifyComplete();
    }
 
    @Test
    void siconiaStartsWithAnalysis() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "ERROR", "viewer", InputClass.LOG_BLOCK);
        WorkflowState state = WorkflowState.empty("s1", "c1", "ERROR")
                .toBuilder()
                .intent(DlmsIntent.SICONIA_TROUBLESHOOT)
                .inputClass(InputClass.LOG_BLOCK)
                .build();
 
        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just("tok"));
 
        StepVerifier.create(service.streamSiconia(request).map(ServerSentEvent::event))
                .expectNext("analysis", "token", "done")
                .verifyComplete();
    }
 
    @Test
    void blockedOutputEmitsFilteredThenDoneWithoutTokens() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "7EA023210313A5E57E", "viewer", InputClass.HEX_FRAME);
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA023210313A5E57E")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.HEX_FRAME)
                .build();
 
        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just("exploit this vulnerability"));
 
        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::event))
                .expectNext("decode", "filtered", "done")
                .verifyComplete();
    }

    @Test
    void casualGreetingStreamsWithoutSourcesFooter() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "hello", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "hello")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("Hi. I can help with DLMS/COSEM questions"))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void capabilityQuestionUsesDeterministicCapabilityReplyWithoutSources() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "what can you do?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "what can you do?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("I can help with DLMS/COSEM questions");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("Sources:");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void retrievalResultsAppendDeterministicSourcesBeforeDone() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "What is Local operations?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is Local operations?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "Local operations content",
                                        new SourceCitation(
                                                "confluence",
                                                "Local-operations_408492990.html",
                                                0,
                                                "General",
                                                "Local operations",
                                                "SPL",
                                                1.0,
                                                "Confluence — Local operations (SPL)"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just("answer"));

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"DOCUMENTATION\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"t\":\"answer\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("Sources: Confluence — Local operations (SPL)"))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void casualGreetingSuppressesSourcesEvenIfRetrievalResultsExist() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "thanks", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "thanks")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "Local operations content",
                                        new SourceCitation(
                                                "confluence",
                                                "Local-operations_408492990.html",
                                                0,
                                                "General",
                                                "Local operations",
                                                "SPL",
                                                1.0,
                                                "Confluence â€” Local operations (SPL)"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("You're welcome. I can help with DLMS/COSEM questions"))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void extendedCourtesyPhraseAlsoSuppressesSources() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "thank you for your help", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "thank you for your help")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "Standards text",
                                        new SourceCitation(
                                                "dlms",
                                                "green-book.pdf",
                                                0,
                                                "General",
                                                null,
                                                null,
                                                1.0,
                                                "DLMS Standard — §General"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("You're welcome"))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void directApduDecodeUsesDeterministicResponseWithoutLlmDrift() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Decode APDU C4020109060100010800FF", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode APDU C4020109060100010800FF")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(new com.company.dlms.domain.decoder.DecodeResult(
                        null,
                        com.company.dlms.domain.decoder.ApduType.GET_RESPONSE,
                        null,
                        List.of(),
                        false,
                        "C4020109060100010800FF",
                        List.of(),
                        new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                                com.company.dlms.domain.decoder.DlmsNormalizedKind.APDU_HEX,
                                com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_HEURISTIC,
                                List.of(),
                                "Recovered APDU payload from wrapped prose input"
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"APDU_ANALYSIS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("What it means: The payload decodes deterministically as GET_RESPONSE, which is the server response to a prior GET_REQUEST, without an HDLC envelope.");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("valid HDLC frame");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("SICONIA");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void decodeEventNormalizesAxdrTreeForUiRendering() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Decode APDU C4020109060100010800FF", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode APDU C4020109060100010800FF")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(new DecodeResult(
                        null,
                        com.company.dlms.domain.decoder.ApduType.GET_RESPONSE,
                        new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                        List.of(),
                        false,
                        "C4020109060100010800FF",
                        List.of()
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.empty());

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = objectMapper.readValue(data, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> decodeResult = (Map<String, Object>) payload.get("decodeResult");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> axdrTree = (Map<String, Object>) decodeResult.get("axdrTree");
                        org.assertj.core.api.Assertions.assertThat(axdrTree.get("type")).isEqualTo("structure");
                        org.assertj.core.api.Assertions.assertThat(axdrTree).containsKey("children");
                        org.assertj.core.api.Assertions.assertThat(axdrTree).doesNotContainKey("elements");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> children = (List<Map<String, Object>>) axdrTree.get("children");
                        org.assertj.core.api.Assertions.assertThat(children).hasSize(1);
                        org.assertj.core.api.Assertions.assertThat(children.getFirst().get("type")).isEqualTo("octet-string");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .thenConsumeWhile(data -> !data.equals("{}"))
                .expectNext("{}")
                .verifyComplete();
    }

    @Test
    void directAxdrDecodeUsesDeterministicResponseWithoutInventedMeaning() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Explain AXDR payload 020109060100010800FF", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain AXDR payload 020109060100010800FF")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(new com.company.dlms.domain.decoder.DecodeResult(
                        null,
                        com.company.dlms.domain.decoder.ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "020109060100010800FF",
                        List.of(),
                        new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                                com.company.dlms.domain.decoder.DlmsNormalizedKind.AXDR_HEX,
                                com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_HEURISTIC,
                                List.of(),
                                "Recovered AXDR payload from wrapped prose input"
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"APDU_ANALYSIS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("What it means: The payload decodes as a raw AXDR value in raw AXDR form without an APDU or HDLC envelope.");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("data transfer completion");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void validControlFrameUsesDeterministicStructuredResponse() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "7EA00A030383CD6F7E", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA00A030383CD6F7E")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .decodeResult(new com.company.dlms.domain.decoder.DecodeResult(
                        new com.company.dlms.domain.decoder.HdlcFrame(
                                com.company.dlms.domain.decoder.FrameType.U_FRAME,
                                com.company.dlms.domain.decoder.UFrameType.SNRM,
                                null,
                                1,
                                1,
                                null,
                                true,
                                new byte[0]
                        ),
                        com.company.dlms.domain.decoder.ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA00A030383CD6F7E",
                        List.of(),
                        new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                                com.company.dlms.domain.decoder.DlmsNormalizedKind.FRAME_HEX,
                                com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT,
                                List.of(),
                                null
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"FRAME_DECODE\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("What it means: This is an HDLC Set Normal Response Mode (SNRM) control frame");
                    org.assertj.core.api.Assertions.assertThat(data).contains("Why it matters: SNRM is the standard HDLC link-setup request used to place the link into normal response mode");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void structuredPlusAgenticDecodeWithEmptySearchDocsDowngradesToDeterministicOnly() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "7EA00A030383CD6F7E what does this do?", "viewer", InputClass.QUERY);
        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(
                        FrameType.U_FRAME,
                        com.company.dlms.domain.decoder.UFrameType.SNRM,
                        null,
                        1,
                        1,
                        null,
                        true,
                        new byte[0]
                ),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of(),
                new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                        com.company.dlms.domain.decoder.DlmsNormalizedKind.FRAME_HEX,
                        com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null
                )
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA00A030383CD6F7E what does this do?")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .orchestrationMode(OrchestrationMode.STRUCTURED_PLUS_AGENTIC)
                .plannerUsed(true)
                .toolTrace(List.of(new ToolTraceEntry(
                        "search_docs",
                        "No supporting documentation snippets were recovered.",
                        false,
                        "RETRIEVAL"
                )))
                .decodeResult(decodeResult)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"orchestrationMode\":\"STRUCTURED_PLUS_AGENTIC\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"plannerUsed\":true");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"explanationMode\":\"DETERMINISTIC_ONLY\"");
                })
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("What it means: This is an HDLC Set Normal Response Mode (SNRM) control frame");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("SRR");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("ACK");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("server response");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();

        verify(ollamaStreamingClient, never()).stream("system", "prompt");
    }

    @Test
    void directGetResponseDeterministicFallbackUsesResponseAndActiveEnergyWording() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "C4020109060100010800FF", "viewer", InputClass.QUERY);
        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                List.of(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)),
                false,
                "C4020109060100010800FF",
                List.of(),
                new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                        com.company.dlms.domain.decoder.DlmsNormalizedKind.APDU_HEX,
                        com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null
                )
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "C4020109060100010800FF")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(decodeResult)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.empty());

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"APDU_ANALYSIS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("server response to a prior GET_REQUEST");
                    org.assertj.core.api.Assertions.assertThat(data).contains("Active energy import total");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("active power");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void structuredApduPromptWithReturnedObjectPhraseDoesNotGetSessionRecallOverride() {
        WorkflowRequest request = new WorkflowRequest(
                "s1",
                "c1",
                "Decode APDU C4020109060100010800FF and explain what object was returned.",
                "viewer",
                InputClass.QUERY
        );
        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(new AxdrOctetString(new byte[]{0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}))),
                List.of(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)),
                false,
                "C4020109060100010800FF",
                List.of(),
                new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                        com.company.dlms.domain.decoder.DlmsNormalizedKind.APDU_HEX,
                        com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered APDU payload from wrapped prose input"
                )
        );
        WorkflowState state = WorkflowState.empty(
                        "s1",
                        "c1",
                        "Decode APDU C4020109060100010800FF and explain what object was returned."
                )
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(decodeResult)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"APDU_ANALYSIS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("GET_RESPONSE");
                    org.assertj.core.api.Assertions.assertThat(data).contains("1.0.1.8.0.255");
                    org.assertj.core.api.Assertions.assertThat(data).contains("Active energy import total");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("last OBIS code in this conversation");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void invalidFcsFallbackMentionsDetectedAnomaliesWhenPresent() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "7EA006030363E9737E", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA006030363E9737E")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .anomalies(List.of("FC-001 frame counter regression relative to previous session state"))
                .decodeResult(new DecodeResult(
                        new HdlcFrame(
                                FrameType.U_FRAME,
                                com.company.dlms.domain.decoder.UFrameType.UNKNOWN,
                                null,
                                1,
                                1,
                                null,
                                false,
                                new byte[0]
                        ),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA006030363E9737E",
                        List.of("FCS invalid")
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"FRAME_DECODE\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("checksum");
                    org.assertj.core.api.Assertions.assertThat(data).contains("FC-001");
                    org.assertj.core.api.Assertions.assertThat(data.toLowerCase()).contains("replay");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void tentativeSFrameSpeculationFallsBackToGenericOuterRoleMeaning() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Decode this malformed frame", "viewer", InputClass.QUERY);
        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(
                        FrameType.S_FRAME,
                        null,
                        com.company.dlms.domain.decoder.SFrameType.RR,
                        1,
                        35651712,
                        null,
                        false,
                        new byte[0]
                ),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E",
                List.of("Unexpected information field on supervisory frame", "FCS invalid")
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode this malformed frame")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .decodeResult(decodeResult)
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_FRAME_DECODE,
                        decodeResult,
                        List.of(),
                        List.of(),
                        0.91,
                        true
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.just(
                "The server acknowledged readiness for more I-frames, which could indicate a previous unsent request or acknowledgment."
        ));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"DETERMINISTIC_DECODE\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("RR means Receive Ready and carries receive-ready flow-control meaning at the HDLC layer");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("server acknowledged");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("previous unsent request");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void singleCommunicationAlarmFallbackAddsOperationalContext() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "0x1342", "viewer", InputClass.QUERY);
        SiconiaResult result = new SiconiaResult(
                null,
                List.of(new AlarmDecodeResult(
                        "0x1342",
                        AlarmSeverity.HIGH,
                        "SICONIA DCU comm failure",
                        "Check DCU-HES link, verify credentials",
                        AffectedComponent.HES
                )),
                null,
                "ALARM_CODE",
                new SiconiaProcessingMetadata(InputClass.ALARM_CODE, ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "0x1342")
                .toBuilder()
                .intent(DlmsIntent.SICONIA_TROUBLESHOOT)
                .inputClass(InputClass.QUERY)
                .siconiaResult(result)
                .groundedAnswerContext(GroundedAnswerContext.deterministicSiconia(
                        StrategyKey.SICONIA_ALARM_ANALYSIS,
                        result,
                        List.of(),
                        List.of(),
                        0.88,
                        false
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt")).thenReturn(Flux.empty());

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"DETERMINISTIC_SICONIA\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("Alarm 0x1342 is HIGH on HES");
                    org.assertj.core.api.Assertions.assertThat(data).contains("DCU-to-HES communication");
                    org.assertj.core.api.Assertions.assertThat(data).contains("downstream meter traffic");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void aareDiagnosticQuestionUsesGroundedSecurityResponse() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "AARE association rejected, diagnostic 6 - what does this usually mean?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "AARE association rejected, diagnostic 6 - what does this usually mean?")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .inputClass(InputClass.QUERY)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "AARE diagnostic context",
                                        new SourceCitation(
                                                "dlms",
                                                "green-book.pdf",
                                                0,
                                                "General",
                                                null,
                                                null,
                                                1.0,
                                                "DLMS Standard — §9.2.2.2.2.3 Low Level Security (LLS) authentication"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("Diagnostic 6 indicates a failure."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"SECURITY_EXPLAIN\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_SECURITY\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"answerSelectedStrategy\":\"SECURITY_EXPLAIN\"");
                })
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("An AARE diagnostic is returned by the server when the application association is rejected or constrained.");
                    org.assertj.core.api.Assertions.assertThat(data).contains("application context");
                    org.assertj.core.api.Assertions.assertThat(data).contains("authentication");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("Sources: DLMS Standard"))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void hlsAuthenticationQuestionUsesGroundedSecurityResponse() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Explain HLS authentication in DLMS/COSEM and when it should be used.", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain HLS authentication in DLMS/COSEM and when it should be used.")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("HLS uses challenge-response authentication."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"SECURITY_EXPLAIN\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_SECURITY\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"answerSelectedStrategy\":\"SECURITY_EXPLAIN\"");
                })
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("HLS is DLMS/COSEM high-level security based on challenge-response proof");
                    org.assertj.core.api.Assertions.assertThat(data).contains("GMAC");
                    org.assertj.core.api.Assertions.assertThat(data).contains("AES-GCM-128");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("###");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("Sources:");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void hdlcFrameStructureQuestionUsesGroundedDocumentationResponse() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Explain HDLC frame structure", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain HDLC frame structure")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("HDLC is a frame format."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"DOCUMENTATION\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_DOCS\"");
                    org.assertj.core.api.Assertions.assertThat(data).contains("\"answerSelectedStrategy\":\"DOCUMENTATION\"");
                })
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("An HDLC frame is a link-layer envelope bounded by opening and closing flag bytes.");
                    org.assertj.core.api.Assertions.assertThat(data).contains("flag bytes");
                    org.assertj.core.api.Assertions.assertThat(data).contains("control field");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("type3");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("###");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("Sources:");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void aarqVsAareQuestionFallsBackToGroundedAssociationSummary() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "What is the difference between AARQ and AARE in DLMS?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is the difference between AARQ and AARE in DLMS?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("AARQ initiates requests, AARE confirms responses."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_DOCS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("\"This Clause");
                    org.assertj.core.api.Assertions.assertThat(data).contains("AARQ is the Association Request APDU sent by the client");
                    org.assertj.core.api.Assertions.assertThat(data).contains("AARE is the Association Response APDU returned by the server");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void greenBookQuestionStripsConfidenceLeakAndFallsBackToGroundedSummary() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "What is the DLMS Green Book?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is the DLMS Green Book?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-2",
                                        "The Green Book defines the system architecture, terms, and transport profiles for DLMS/COSEM.",
                                        new SourceCitation("dlms", "green-book.pdf", 0, "General", null, null, 1.0, "DLMS Standard â€” Â§Green Book")
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("The DLMS Green Book defines the standard reference. Confidence:0.68."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_DOCS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("\"10 |");
                    org.assertj.core.api.Assertions.assertThat(data).contains("The DLMS Green Book defines the DLMS/COSEM architecture");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("Confidence:");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("Sources: DLMS Standard"))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void securitySuiteOneQuestionFallsBackToGroundedSecuritySuiteSummary() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "What is DLMS security suite 1?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is DLMS security suite 1?")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("Suite1 protects traffic."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_SECURITY\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("\"Function:");
                    org.assertj.core.api.Assertions.assertThat(data).contains("DLMS security suite 1 provides both authentication and encryption.");
                    org.assertj.core.api.Assertions.assertThat(data).contains("AES-GCM-128");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("suite1");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void replayProtectionQuestionFallsBackToGroundedCounterSummary() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "How does replay protection work in DLMS?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "How does replay protection work in DLMS?")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("Replay protection uses checks."));

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_SECURITY\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("\"ng the");
                    org.assertj.core.api.Assertions.assertThat(data).contains("frame or invocation counter");
                    org.assertj.core.api.Assertions.assertThat(data).contains("monotonically increasing");
                    org.assertj.core.api.Assertions.assertThat(data).contains("replay attempts");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void retrievalAnswersStripLeadingQuotedDocumentFragmentsEvenWhenGeneratedAnswerIsOtherwiseAccepted() {
        StreamingWorkflowService qwenService = new StreamingWorkflowService(
                orchestrator,
                new HybridAgenticPlannerService(
                        ollamaStreamingClient,
                        promptAssembler,
                        agentDispatchNode,
                        objectMapper
                ),
                new GroundedAnswerBuilder(new FollowUpResolver()),
                new FollowUpResolver(),
                promptAssembler,
                new com.company.dlms.infrastructure.llm.GroundedFactBundleBuilder(),
                new com.company.dlms.infrastructure.llm.GroundedAnswerQualityGate("qwen2.5:3b"),
                ollamaStreamingClient,
                new ThinkTagFilter(),
                new MathMarkupFilter(),
                new OutputFilter(),
                objectMapper
        );

        WorkflowRequest request = new WorkflowRequest("s1", "c1", "What is the DLMS Green Book?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "What is the DLMS Green Book?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("\"10 | DLMS/COSEM Architecture and Protocols, the Green Book Edition 10.\" The DLMS Green Book defines the DLMS/COSEM architecture, terminology, conformance concepts, and communication profiles."));

        StepVerifier.create(qwenService.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"RETRIEVAL_DOCS\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("The DLMS Green Book defines the DLMS/COSEM architecture");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("\"10 |");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void primitiveNullAxdrGetsExplicitVisibleSummary() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "00", "viewer", InputClass.QUERY);
        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.UNKNOWN,
                new com.company.dlms.domain.decoder.AxdrNull(),
                List.of(),
                false,
                "00",
                List.of(),
                new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                        com.company.dlms.domain.decoder.DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null
                )
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(decodeResult)
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_AXDR_DECODE,
                        decodeResult,
                        List.of(),
                        List.of(),
                        0.96,
                        false
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"DETERMINISTIC_DECODE\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("The top-level AXDR value is null-data with no embedded scalar content."))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void primitiveBooleanAxdrGetsExplicitVisibleSummary() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "03 01", "viewer", InputClass.QUERY);
        DecodeResult decodeResult = new DecodeResult(
                null,
                ApduType.UNKNOWN,
                new com.company.dlms.domain.decoder.AxdrBoolean(true),
                List.of(),
                false,
                "0301",
                List.of(),
                new com.company.dlms.domain.decoder.DlmsProcessingMetadata(
                        com.company.dlms.domain.decoder.DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null
                )
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "03 01")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .inputClass(InputClass.QUERY)
                .decodeResult(decodeResult)
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_AXDR_DECODE,
                        decodeResult,
                        List.of(),
                        List.of(),
                        0.96,
                        false
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"answerMode\":\"DETERMINISTIC_DECODE\""))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("The top-level AXDR value is boolean true."))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void boxedMathMarkupIsSanitizedBeforeStreamingToTheUi() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Explain Local operations", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain Local operations")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");
        when(ollamaStreamingClient.stream("system", "prompt"))
                .thenReturn(Flux.just("\\boxed{Local operations are run locally for troubleshooting.}"));

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"DOCUMENTATION\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("Local operations are run locally for troubleshooting.");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("\\boxed");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void frameDecodeErrorsWithoutStructuredResultStillUseDeterministicFallback() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "Decode this HDLC frame: INVALID", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode this HDLC frame: INVALID")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .errors(List.of("Non-hex characters in input"))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamDecode(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"FRAME_DECODE\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("What happened: The deterministic parser could not decode a valid HDLC frame");
                    org.assertj.core.api.Assertions.assertThat(data).contains("Parser detail: Non-hex characters in input.");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void previousFrameTypeQuestionUsesSessionNarrativeInsteadOfDocumentationGuess() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "what was the frame type of the frame I just sent?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .associationState("ASSOCIATING")
                .narrativeContext(List.of(
                        new com.company.dlms.domain.SessionEvent(
                                "s1",
                                java.time.Instant.parse("2026-05-26T10:00:00Z"),
                                1,
                                "U_FRAME (SNRM)",
                                "COMPLETE",
                                "ASSOCIATING",
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ))
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "General frame documentation",
                                        new SourceCitation(
                                                "dlms",
                                                "blue-book.pdf",
                                                0,
                                                "General",
                                                null,
                                                null,
                                                1.0,
                                                "DLMS Standard — §Frame documentation"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("SNRM");
                    org.assertj.core.api.Assertions.assertThat(data).contains("U-frame");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void previousFrameTypeQuestionStillUsesSessionNarrativeWhenIntentArrivesAsFrameDecode() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "what was the frame type of the frame I just sent?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .errors(List.of("Odd-length hex input"))
                .narrativeContext(List.of(
                        new com.company.dlms.domain.SessionEvent(
                                "s1",
                                java.time.Instant.parse("2026-05-26T10:00:00Z"),
                                1,
                                "U_FRAME (SNRM)",
                                "COMPLETE",
                                "ASSOCIATING",
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"FRAME_DECODE\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("SNRM");
                    org.assertj.core.api.Assertions.assertThat(data).contains("U-frame");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("Odd-length hex input");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void meaningFollowUpUsesLatestDeterministicDecodeInsteadOfRetrieval() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "what does that mean?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "what does that mean?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.U_FRAME, com.company.dlms.domain.decoder.UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA00A030383CD6F7E",
                        List.of()
                ))
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "General HDLC documentation",
                                        new SourceCitation(
                                                "dlms",
                                                "blue-book.pdf",
                                                0,
                                                "General",
                                                null,
                                                null,
                                                1.0,
                                                "DLMS Standard — §HDLC framing"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("Set Normal Response Mode");
                    org.assertj.core.api.Assertions.assertThat(data).contains("HDLC connection establishment");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void failureReasonFollowUpUsesLatestDeterministicDecodeInsteadOfRetrieval() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "why did it fail?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "why did it fail?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, false, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA006030363E9737E",
                        List.of()
                ))
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "General checksum troubleshooting",
                                        new SourceCitation(
                                                "dlms",
                                                "blue-book.pdf",
                                                0,
                                                "General",
                                                null,
                                                null,
                                                1.0,
                                                "DLMS Standard — §Checksum"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("checksum did not match");
                    org.assertj.core.api.Assertions.assertThat(data).contains("Re-capture or retransmit");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }

    @Test
    void meaningFollowUpUsesLatestSiconiaResultInsteadOfNoContextReply() {
        WorkflowRequest request = new WorkflowRequest("s1", "c1", "what does that mean?", "viewer", InputClass.QUERY);
        WorkflowState state = WorkflowState.empty("s1", "c1", "what does that mean?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .inputClass(InputClass.QUERY)
                .siconiaResult(new SiconiaResult(
                        null,
                        List.of(new AlarmDecodeResult(
                                "0x1342",
                                AlarmSeverity.HIGH,
                                "SICONIA DCU comm failure",
                                "Check DCU-HES link, verify credentials",
                                AffectedComponent.HES
                        )),
                        null,
                        "ALARM_CODE",
                        new SiconiaProcessingMetadata(InputClass.ALARM_CODE, ParseProvenance.STRUCTURED_DIRECT, List.of(), null)
                ))
                .build();

        when(orchestrator.executeRaw(request)).thenReturn(Mono.just(state));
        when(promptAssembler.assemble(any())).thenReturn("prompt");
        when(promptAssembler.systemPrompt(any())).thenReturn("system");

        StepVerifier.create(service.streamChat(request).map(ServerSentEvent::data))
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).contains("\"intent\":\"UNKNOWN\""))
                .assertNext(data -> {
                    org.assertj.core.api.Assertions.assertThat(data).contains("Alarm 0x1342 is HIGH on HES");
                    org.assertj.core.api.Assertions.assertThat(data).contains("SICONIA DCU comm failure");
                    org.assertj.core.api.Assertions.assertThat(data).doesNotContain("do not have previous structured result");
                })
                .assertNext(data -> org.assertj.core.api.Assertions.assertThat(data).isEqualTo("{}"))
                .verifyComplete();
    }
}
