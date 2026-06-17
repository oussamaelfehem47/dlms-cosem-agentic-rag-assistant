package com.company.dlms.api;

import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import com.company.dlms.infrastructure.llm.OllamaStreamingClient;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.UserRepository;
import com.company.dlms.workflow.WorkflowRequest;
import com.company.dlms.domain.InputClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15")
            .withDatabaseName("dlms_assistant")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());

        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("dlms.security.jwt.secret", () -> "test_secret_must_be_at_least_32_characters_long_for_hs256");
        registry.add("dlms.security.session.encryption-key", () -> "dlmsassistant16c");
        registry.add("dlms.security.audit.secret", () -> "test-audit-hmac-key-at-least-32chars!!");
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:11434");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema.sql");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @SpyBean
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private OllamaStreamingClient ollamaStreamingClient;

    @MockBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll().block();
        when(ollamaStreamingClient.stream(anyString())).thenReturn(Flux.just("token"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[384]);
    }

    @Test
    void api_withoutToken_decodeStream_shouldReturn401() {
        webTestClient.post()
                .uri("/api/decode/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "0011223344556677", "VIEWER", InputClass.HEX_FRAME))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void api_withoutToken_chatStream_shouldReturn401() {
        webTestClient.post()
                .uri("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "Explain AXDR payload 1907E80416010E1E0000003C00", "VIEWER", InputClass.QUERY))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void api_withValidViewerToken_decodeStream_shouldReturn200() {
        User user = userRepository.save(
                User.create("viewer@company.com", "vieweruser",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        String token = jwtService.generateAccessToken(user.userId().toString(), Role.VIEWER);

        String hex = "A00A020100E6E600C4020109060100010800FF9D8E7E";

        webTestClient.post()
                .uri("/api/decode/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", hex, "VIEWER", InputClass.HEX_FRAME))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void api_withValidViewerToken_chatStream_shouldReturn200() {
        User user = userRepository.save(
                User.create("viewer-chat@company.com", "viewerchat",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        String token = jwtService.generateAccessToken(user.userId().toString(), Role.VIEWER);

        webTestClient.post()
                .uri("/api/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "Explain AXDR payload 1907E80416010E1E0000003C00", "VIEWER", InputClass.QUERY))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void api_withValidViewerToken_chatStream_checksCurrentUserOncePerRequest() {
        when(ollamaStreamingClient.stream(anyString())).thenReturn(Flux.just("token-1", "token-2", "token-3"));

        User user = userRepository.save(
                User.create("viewer-once@company.com", "vieweronce",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        String token = jwtService.generateAccessToken(user.userId().toString(), Role.VIEWER);

        clearInvocations(userRepository);

        webTestClient.post()
                .uri("/api/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new WorkflowRequest("s-stream", "c-stream", "Explain AXDR payload 1907E80416010E1E0000003C00", "VIEWER", InputClass.QUERY))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        verify(userRepository, times(1)).findByUserIdAndActiveTrue(user.userId());
    }

    @Test
    void api_withViewerToken_workflowEndpoint_shouldReturn403() {
        User user = userRepository.save(
                User.create("viewer2@company.com", "vieweruser2",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        String token = jwtService.generateAccessToken(user.userId().toString(), Role.VIEWER);

        webTestClient.post()
                .uri("/api/workflow/test")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void api_withDeactivatedUserToken_shouldReturn401() {
        User user = userRepository.save(
                User.create("viewer3@company.com", "vieweruser3",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        String token = jwtService.generateAccessToken(user.userId().toString(), Role.VIEWER);

        userRepository.save(new User(
                user.userId(),
                user.email(),
                user.username(),
                user.passwordHash(),
                user.role(),
                false,
                user.createdAt()
        )).block();

        webTestClient.post()
                .uri("/api/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkflowRequest("s1", "c1", "Explain AXDR payload 1907E80416010E1E0000003C00", "VIEWER", InputClass.QUERY))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void api_withStaleEngineerToken_usesCurrentDatabaseRole() {
        User user = userRepository.save(
                User.create("engineer@company.com", "engineeruser",
                        passwordEncoder.encode("Pass1234!"), Role.ENGINEER)
        ).block();
        String token = jwtService.generateAccessToken(user.userId().toString(), Role.ENGINEER);

        userRepository.save(new User(
                user.userId(),
                user.email(),
                user.username(),
                user.passwordHash(),
                Role.VIEWER,
                true,
                user.createdAt()
        )).block();

        webTestClient.post()
                .uri("/api/workflow/test")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void health_shouldBePublic() {
        webTestClient.get()
                .uri("/api/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
