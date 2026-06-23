package com.company.dlms.api;

import com.company.dlms.domain.Message;
import com.company.dlms.infrastructure.ConversationService;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.SecurityConfig;
import com.company.dlms.infrastructure.security.JwtAuthFilter;
import com.company.dlms.infrastructure.security.SuppressWwwAuthenticateFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@WebFluxTest(ConversationController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, SuppressWwwAuthenticateFilter.class})
class ConversationControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private JwtService jwtService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONV_ID = UUID.randomUUID();

    private SecurityContext viewerCtx() {
        return new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
    }

    private SecurityContext engineerCtx() {
        return new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ENGINEER"))));
    }

    @Test
    void createConversation_withValidJwt_returns201() {
        ConversationResponse resp = new ConversationResponse(CONV_ID, "Test", Instant.now(), Instant.now(), 0);
        when(conversationService.createConversation(any(UUID.class), anyString()))
                .thenReturn(Mono.just(resp));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(viewerCtx().getAuthentication()))
                .post().uri("/api/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Test"))
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody(ConversationResponse.class)
                .value(r -> { assert r != null; assert CONV_ID.equals(r.id()); });
    }

    @Test
    void listConversations_asViewer_returnsOwnConversations() {
        ConversationResponse resp = new ConversationResponse(CONV_ID, "Test", Instant.now(), Instant.now(), 0);
        when(conversationService.getConversationsByUser(USER_ID))
                .thenReturn(Flux.just(resp));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(viewerCtx().getAuthentication()))
                .get().uri("/api/conversations")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ConversationResponse.class)
                .hasSize(1);
    }

    @Test
    void saveMessage_returns201() {
        String toolTraceJson = """
                [{"toolName":"search_docs","summary":"Retrieved DLMS references","authoritative":false,"provenance":"RAG"}]
                """.trim();
        Message saved = Message.create(CONV_ID, "user", "query", "DOCUMENTATION",
                "test input", null, null, "NATURAL_LANGUAGE_AGENTIC", Boolean.TRUE, toolTraceJson,
                "No strong structured candidate found", "answer", null, false, null, null);
        when(conversationService.saveMessage(eq(CONV_ID), eq(USER_ID), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull(), isNull(),
                eq("NATURAL_LANGUAGE_AGENTIC"), eq(Boolean.TRUE), eq(toolTraceJson),
                eq("No strong structured candidate found"), anyString(), isNull(),
                eq(false), isNull(), isNull()))
                .thenReturn(Mono.just(saved));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(engineerCtx().getAuthentication()))
                .post().uri("/api/conversations/{id}/messages", CONV_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", "user", "rawInput", "test input",
                        "inputClass", "query", "intent", "DOCUMENTATION", "explanation", "answer",
                        "orchestrationMode", "NATURAL_LANGUAGE_AGENTIC",
                        "plannerUsed", true,
                        "toolTraceJson", toolTraceJson,
                        "plannerFallbackReason", "No strong structured candidate found"))
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.intent").isEqualTo("DOCUMENTATION")
                .jsonPath("$.orchestration_mode").isEqualTo("NATURAL_LANGUAGE_AGENTIC")
                .jsonPath("$.planner_used").isEqualTo(true)
                .jsonPath("$.planner_fallback_reason").isEqualTo("No strong structured candidate found")
                .jsonPath("$.tool_trace[0].toolName").isEqualTo("search_docs")
                .jsonPath("$.session_id").isEmpty();
    }

    @Test
    void getConversation_asEngineer_returnsDetail() {
        MessageResponse message = new MessageResponse(
                UUID.randomUUID(),
                "assistant",
                "query",
                "DOCUMENTATION",
                "What is Ansible Inventory?",
                null,
                null,
                "NATURAL_LANGUAGE_AGENTIC",
                Boolean.TRUE,
                """
                [{"toolName":"search_docs","summary":"Retrieved Ansible references","authoritative":false,"provenance":"RAG"}]
                """.trim(),
                "No structured payload candidate was present",
                "Answer",
                "session-2",
                false,
                null,
                null,
                Instant.now()
        );
        ConversationDetailResponse detail = new ConversationDetailResponse(
                CONV_ID, "Test", Instant.now(), Instant.now(), 1, List.of(message));
        when(conversationService.getConversationWithMessages(CONV_ID, USER_ID))
                .thenReturn(Mono.just(detail));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(engineerCtx().getAuthentication()))
                .get().uri("/api/conversations/{id}", CONV_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.messages[0].session_id").isEqualTo("session-2")
                .jsonPath("$.messages[0].orchestration_mode").isEqualTo("NATURAL_LANGUAGE_AGENTIC")
                .jsonPath("$.messages[0].planner_used").isEqualTo(true)
                .jsonPath("$.messages[0].tool_trace[0].toolName").isEqualTo("search_docs");
    }

    @Test
    void getConversation_nonOwned_returns403() {
        when(conversationService.getConversationWithMessages(CONV_ID, USER_ID))
                .thenReturn(Mono.error(new ResponseStatusException(FORBIDDEN, "Access denied")));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(engineerCtx().getAuthentication()))
                .get().uri("/api/conversations/{id}", CONV_ID)
                .exchange()
                .expectStatus().isForbidden();
    }
}
