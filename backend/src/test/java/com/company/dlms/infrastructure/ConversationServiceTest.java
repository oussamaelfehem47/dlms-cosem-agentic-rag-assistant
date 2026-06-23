package com.company.dlms.infrastructure;

import com.company.dlms.api.ConversationDetailResponse;
import com.company.dlms.api.MessageResponse;
import com.company.dlms.domain.Conversation;
import com.company.dlms.domain.Message;
import com.company.dlms.infrastructure.db.ConversationRepository;
import com.company.dlms.infrastructure.db.MessageRepository;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Captor
    private ArgumentCaptor<Message> messageCaptor;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository);
    }

    @Test
    void saveMessage_preservesIntentOnPersistedMessage() {
        Conversation conversation = new Conversation(CONVERSATION_ID, USER_ID, "Conversation", Instant.now());
        Message savedMessage = Message.create(
                CONVERSATION_ID,
                "assistant",
                "query",
                "DOCUMENTATION",
                "What is the DLMS Green Book?",
                null,
                null,
                null,
                null,
                null,
                null,
                "Answer",
                "session-1",
                false,
                null,
                null
        );

        when(conversationRepository.findByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(Mono.just(conversation));
        when(conversationRepository.existsById(CONVERSATION_ID)).thenReturn(Mono.just(true));
        when(messageRepository.save(any(Message.class))).thenReturn(Mono.just(savedMessage));

        StepVerifier.create(conversationService.saveMessage(
                        CONVERSATION_ID,
                        USER_ID,
                        "assistant",
                        "query",
                        "DOCUMENTATION",
                        "What is the DLMS Green Book?",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Answer",
                        "session-1",
                        false,
                        null,
                        null
                ))
                .assertNext(message -> assertThat(message.intent()).isEqualTo("DOCUMENTATION"))
                .verifyComplete();

        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().intent()).isEqualTo("DOCUMENTATION");
    }

    @Test
    void saveMessage_preservesGroundedExplanationStrategyAndSessionId() {
        Conversation conversation = new Conversation(CONVERSATION_ID, USER_ID, "Conversation", Instant.now());
        String strategyMetadataJson = """
                {"selectedStrategy":"DLMS_FRAME_DECODE","selectedLabel":"HDLC frame decode","confidence":0.93,"ambiguous":false,"tentative":false,"candidates":[],"warnings":[]}
                """.trim();
        String toolTraceJson = """
                [{"toolName":"decode_frame","summary":"Decoded HDLC control frame","authoritative":true,"provenance":"JAVA"}]
                """.trim();
        Message savedMessage = Message.create(
                CONVERSATION_ID,
                "assistant",
                "hex_frame",
                "FRAME_DECODE",
                "7EA00A030383CD6F7E",
                "{\"apduType\":\"UNKNOWN\"}",
                strategyMetadataJson,
                "DETERMINISTIC_FAST_PATH",
                Boolean.FALSE,
                toolTraceJson,
                "Deterministic decode branch selected",
                "What happened: The deterministic parser decoded a valid HDLC control frame.",
                "session-snrn",
                false,
                "GROUNDED_LLM",
                "MCP"
        );

        when(conversationRepository.findByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(Mono.just(conversation));
        when(conversationRepository.existsById(CONVERSATION_ID)).thenReturn(Mono.just(true));
        when(messageRepository.save(any(Message.class))).thenReturn(Mono.just(savedMessage));

        StepVerifier.create(conversationService.saveMessage(
                        CONVERSATION_ID,
                        USER_ID,
                        "assistant",
                        "hex_frame",
                        "FRAME_DECODE",
                        "7EA00A030383CD6F7E",
                        "{\"apduType\":\"UNKNOWN\"}",
                        strategyMetadataJson,
                        "DETERMINISTIC_FAST_PATH",
                        Boolean.FALSE,
                        toolTraceJson,
                        "Deterministic decode branch selected",
                        "What happened: The deterministic parser decoded a valid HDLC control frame.",
                        "session-snrn",
                        false,
                        "GROUNDED_LLM",
                        "MCP"
                ))
                .assertNext(message -> {
                    assertThat(message.explanation()).contains("deterministic parser decoded");
                    assertThat(message.sessionId()).isEqualTo("session-snrn");
                    assertThat(message.explanationMode()).isEqualTo("GROUNDED_LLM");
                    assertThat(message.toolProvenance()).isEqualTo("MCP");
                    assertThat(message.orchestrationMode()).isEqualTo("DETERMINISTIC_FAST_PATH");
                    assertThat(message.plannerUsed()).isFalse();
                    assertThat(message.toolTraceJson()).isNotNull();
                    assertThat(message.toolTraceJson().asString()).contains("decode_frame");
                    assertThat(message.plannerFallbackReason()).isEqualTo("Deterministic decode branch selected");
                    assertThat(message.strategyMetadataJson()).isNotNull();
                    assertThat(message.strategyMetadataJson().asString()).contains("DLMS_FRAME_DECODE");
                })
                .verifyComplete();

        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().explanation()).contains("deterministic parser decoded");
        assertThat(messageCaptor.getValue().sessionId()).isEqualTo("session-snrn");
        assertThat(messageCaptor.getValue().explanationMode()).isEqualTo("GROUNDED_LLM");
        assertThat(messageCaptor.getValue().toolProvenance()).isEqualTo("MCP");
        assertThat(messageCaptor.getValue().orchestrationMode()).isEqualTo("DETERMINISTIC_FAST_PATH");
        assertThat(messageCaptor.getValue().plannerUsed()).isFalse();
        assertThat(messageCaptor.getValue().toolTraceJson()).isNotNull();
        assertThat(messageCaptor.getValue().toolTraceJson().asString()).contains("decode_frame");
        assertThat(messageCaptor.getValue().plannerFallbackReason()).isEqualTo("Deterministic decode branch selected");
        assertThat(messageCaptor.getValue().strategyMetadataJson()).isNotNull();
        assertThat(messageCaptor.getValue().strategyMetadataJson().asString()).contains("DLMS_FRAME_DECODE");
    }

    @Test
    void getConversationWithMessages_mapsPersistedIntentIntoResponse() {
        Instant createdAt = Instant.parse("2026-05-15T09:00:00Z");
        Conversation conversation = new Conversation(CONVERSATION_ID, USER_ID, "Conversation", createdAt);
        Message persistedMessage = new Message(
                UUID.randomUUID(),
                CONVERSATION_ID,
                "assistant",
                "query",
                "DOCUMENTATION",
                "What is Ansible Inventory?",
                null,
                null,
                null,
                null,
                null,
                null,
                "Ansible Inventory defines host configurations for playbooks.",
                "session-2",
                false,
                null,
                null,
                createdAt.plusSeconds(30)
        );

        when(conversationRepository.findByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(Mono.just(conversation));
        when(conversationRepository.existsById(CONVERSATION_ID)).thenReturn(Mono.just(true));
        when(messageRepository.findByConversationIdOrderByTimestampAsc(CONVERSATION_ID))
                .thenReturn(Flux.just(persistedMessage));

        StepVerifier.create(conversationService.getConversationWithMessages(CONVERSATION_ID, USER_ID))
                .assertNext(response -> {
                    assertThat(response).isInstanceOf(ConversationDetailResponse.class);
                    assertThat(response.messages()).hasSize(1);
                    assertThat(response.messages().get(0).intent()).isEqualTo("DOCUMENTATION");
                    assertThat(response.messages().get(0).sessionId()).isEqualTo("session-2");
                })
                .verifyComplete();
    }

    @Test
    void getConversationWithMessages_roundTripsStrategyMetadataAndExplanation() {
        Instant createdAt = Instant.parse("2026-05-15T09:00:00Z");
        Conversation conversation = new Conversation(CONVERSATION_ID, USER_ID, "Conversation", createdAt);
        String strategyMetadataJson = """
                {"selectedStrategy":"SICONIA_XML_ANALYSIS","selectedLabel":"SICONIA XML analysis","confidence":0.91,"ambiguous":false,"tentative":true,"candidates":[],"warnings":["Recovered from wrapped prose"]}
                """.trim();
        String toolTraceJson = """
                [{"toolName":"analyze_xml","summary":"Recovered structured alarm data from XML","authoritative":true,"provenance":"MCP"}]
                """.trim();
        Message persistedMessage = new Message(
                UUID.randomUUID(),
                CONVERSATION_ID,
                "assistant",
                "xml_trace",
                "SICONIA_TROUBLESHOOT",
                "<Event />",
                Json.of("{\"inputClass\":\"XML_TRACE\"}"),
                Json.of(strategyMetadataJson),
                "STRUCTURED_PLUS_AGENTIC",
                Boolean.TRUE,
                Json.of(toolTraceJson),
                "Wrapped XML recovered before explanation",
                "What it means: Alarm 0x1342 is HIGH on HES.",
                "session-xml",
                false,
                "GROUNDED_LLM",
                "MCP",
                createdAt.plusSeconds(30)
        );

        when(conversationRepository.findByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(Mono.just(conversation));
        when(conversationRepository.existsById(CONVERSATION_ID)).thenReturn(Mono.just(true));
        when(messageRepository.findByConversationIdOrderByTimestampAsc(CONVERSATION_ID))
                .thenReturn(Flux.just(persistedMessage));

        StepVerifier.create(conversationService.getConversationWithMessages(CONVERSATION_ID, USER_ID))
                .assertNext(response -> {
                    assertThat(response.messages()).hasSize(1);
                    MessageResponse message = response.messages().get(0);
                    assertThat(message.intent()).isEqualTo("SICONIA_TROUBLESHOOT");
                    assertThat(message.sessionId()).isEqualTo("session-xml");
                    assertThat(message.explanation()).contains("Alarm 0x1342 is HIGH on HES");
                    assertThat(message.strategyMetadata()).contains("SICONIA_XML_ANALYSIS");
                    assertThat(message.strategyMetadata()).contains("\"tentative\":true");
                    assertThat(message.explanationMode()).isEqualTo("GROUNDED_LLM");
                    assertThat(message.toolProvenance()).isEqualTo("MCP");
                    assertThat(message.orchestrationMode()).isEqualTo("STRUCTURED_PLUS_AGENTIC");
                    assertThat(message.plannerUsed()).isTrue();
                    assertThat(message.toolTrace()).contains("analyze_xml");
                    assertThat(message.plannerFallbackReason()).isEqualTo("Wrapped XML recovered before explanation");
                })
                .verifyComplete();
    }

    @Test
    void getConversationWithMessages_roundTripsArtifactResultsJson() {
        Instant createdAt = Instant.parse("2026-05-15T09:00:00Z");
        Conversation conversation = new Conversation(CONVERSATION_ID, USER_ID, "Conversation", createdAt);
        String artifactResultsJson = """
                [{"artifactId":"artifact-1","index":0,"source":"ATTACHMENT","filename":"frame.hex","rawInput":"7EA00A030383CD6F7E","inputClass":"HEX_FRAME","intent":"FRAME_DECODE","explanation":"Frame decoded."}]
                """.trim();
        Message persistedMessage = new Message(
                UUID.randomUUID(),
                CONVERSATION_ID,
                "assistant",
                "query",
                "UNKNOWN",
                "Decode these",
                null,
                Json.of(artifactResultsJson),
                null,
                "STRUCTURED_PLUS_AGENTIC",
                Boolean.TRUE,
                null,
                null,
                "Batch explanation",
                "session-batch",
                false,
                null,
                null,
                createdAt.plusSeconds(30)
        );

        when(conversationRepository.findByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(Mono.just(conversation));
        when(conversationRepository.existsById(CONVERSATION_ID)).thenReturn(Mono.just(true));
        when(messageRepository.findByConversationIdOrderByTimestampAsc(CONVERSATION_ID))
                .thenReturn(Flux.just(persistedMessage));

        StepVerifier.create(conversationService.getConversationWithMessages(CONVERSATION_ID, USER_ID))
                .assertNext(response -> {
                    assertThat(response.messages()).hasSize(1);
                    MessageResponse message = response.messages().get(0);
                    assertThat(message.artifactResults()).contains("artifact-1");
                    assertThat(message.artifactResults()).contains("frame.hex");
                    assertThat(message.orchestrationMode()).isEqualTo("STRUCTURED_PLUS_AGENTIC");
                })
                .verifyComplete();
    }
}
