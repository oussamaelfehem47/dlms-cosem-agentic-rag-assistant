package com.company.dlms.api.admin;

import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReflectionControllerTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15")
            .withDatabaseName("dlms_assistant")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("dlms.security.jwt.secret", () -> "test_secret_must_be_at_least_32_characters_long_for_hs256");
        registry.add("dlms.security.session.encryption-key", () -> "dlmsassistant16c");
        registry.add("dlms.security.audit.secret", () -> "test-audit-hmac-key-at-least-32chars!!");
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:11434");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema.sql");
    }

    @Autowired private WebTestClient webTestClient;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @MockBean  private EmbeddingModel embeddingModel;
    @MockBean  private com.company.dlms.infrastructure.llm.OllamaStreamingClient ollamaStreamingClient;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll().block();

        User admin = userRepository.save(
                User.create("admin@test.com", "adminuser",
                        passwordEncoder.encode("AdminPass1!"), Role.ADMIN)
        ).block();
        adminToken = jwtService.generateAccessToken(admin.userId().toString(), Role.ADMIN);

        User viewer = userRepository.save(
                User.create("viewer@test.com", "vieweruser",
                        passwordEncoder.encode("ViewerPass1!"), Role.VIEWER)
        ).block();
        viewerToken = jwtService.generateAccessToken(viewer.userId().toString(), Role.VIEWER);
    }

    // ── GET /stats ────────────────────────────────────────────────────────

    @Test
    void getStats_asAdmin_returns200WithExpectedFields() {
        webTestClient.get()
                .uri("/api/admin/reflection/stats")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mcpFailureRate").exists()
                .jsonPath("$.warnings").exists()
                .jsonPath("$.intentDistribution").exists()
                .jsonPath("$.totalExecutions").exists()
                .jsonPath("$.feedbackDatasetSize").exists()
                .jsonPath("$.dislikedResponseCount").exists()
                .jsonPath("$.activeAdaptations").exists();
    }

    @Test
    void getStats_asViewer_returns403() {
        webTestClient.get()
                .uri("/api/admin/reflection/stats")
                .header("Authorization", "Bearer " + viewerToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void getStats_withoutToken_returns401() {
        webTestClient.get()
                .uri("/api/admin/reflection/stats")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── POST /feedback ────────────────────────────────────────────────────

    @Test
    void submitFeedback_asViewer_returns200() {
        webTestClient.post()
                .uri("/api/admin/reflection/feedback")
                .header("Authorization", "Bearer " + viewerToken)
                .header("Content-Type", "application/json")
                .bodyValue("{\"feedback\":\"like\",\"intent\":\"FRAME_DECODE\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void submitFeedback_asAdmin_returns200() {
        webTestClient.post()
                .uri("/api/admin/reflection/feedback")
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .bodyValue("{\"feedback\":\"dislike\",\"intent\":\"DOCUMENTATION\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void submitFeedback_withoutToken_returns401() {
        webTestClient.post()
                .uri("/api/admin/reflection/feedback")
                .header("Content-Type", "application/json")
                .bodyValue("{\"feedback\":\"like\",\"intent\":\"FRAME_DECODE\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── GET /feedback/disliked ────────────────────────────────────────────

    @Test
    void getDislikedFeedback_asAdmin_returns200WithList() {
        webTestClient.get()
                .uri("/api/admin/reflection/feedback/disliked?limit=10")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray();
    }

    @Test
    void getDislikedFeedback_asViewer_returns403() {
        webTestClient.get()
                .uri("/api/admin/reflection/feedback/disliked")
                .header("Authorization", "Bearer " + viewerToken)
                .exchange()
                .expectStatus().isForbidden();
    }
}
