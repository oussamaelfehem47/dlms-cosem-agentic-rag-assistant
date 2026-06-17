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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminControllerTest {

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

    @Autowired private WebTestClient webTestClient;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private DatabaseClient databaseClient;
    @MockBean private EmbeddingModel embeddingModel;
    @MockBean private com.company.dlms.infrastructure.llm.OllamaStreamingClient ollamaStreamingClient;

    private User adminUser;
    private String adminToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll().block();
        databaseClient.sql("DELETE FROM message_feedback").then().block();
        databaseClient.sql("DELETE FROM messages").then().block();
        databaseClient.sql("DELETE FROM conversations").then().block();
        databaseClient.sql("DELETE FROM stm_entries").then().block();

        adminUser = userRepository.save(
                User.create("admin@test.com", "adminuser",
                        passwordEncoder.encode("AdminPass1!"), Role.ADMIN)
        ).block();
        adminToken = jwtService.generateAccessToken(adminUser.userId().toString(), Role.ADMIN);
        when(ollamaStreamingClient.stream(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("token"));
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new float[384]);
    }

    @Test
    void listUsers_asAdmin_returns200WithActiveAndInactiveUsers() {
        User created = userRepository.save(
                User.create("inactive@test.com", "inactive-user",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        User inactive = userRepository.save(
                new User(
                        created.userId(),
                        created.email(),
                        created.username(),
                        created.passwordHash(),
                        created.role(),
                        false,
                        java.time.Instant.parse("2026-06-03T10:15:30Z")
                )
        ).block();

        webTestClient.get()
                .uri("/api/admin/users")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AdminController.UserSummaryResponse.class)
                .value(users -> {
                    assertThat(users).isNotEmpty();
                    assertThat(users)
                            .anySatisfy(user -> {
                                assertThat(user.userId()).isEqualTo(inactive.userId());
                                assertThat(user.active()).isFalse();
                                assertThat(user.createdAt()).isEqualTo(inactive.createdAt());
                            });
                });
    }

    @Test
    void listUsers_asEngineer_returns403() {
        User engineer = userRepository.save(
                User.create("eng@test.com", "enguser",
                        passwordEncoder.encode("Pass1234!"), Role.ENGINEER)
        ).block();
        String engToken = jwtService.generateAccessToken(engineer.userId().toString(), Role.ENGINEER);

        webTestClient.get()
                .uri("/api/admin/users")
                .header("Authorization", "Bearer " + engToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void deactivateUser_asAdmin_returns204() {
        User target = userRepository.save(
                User.create("target@test.com", "targetuser",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();

        webTestClient.delete()
                .uri("/api/admin/users/" + target.userId())
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isNoContent();

        User updated = userRepository.findById(target.userId()).block();
        assertThat(updated.active()).isFalse();
    }

    @Test
    void activateUser_asAdmin_returns200AndReactivatesTarget() {
        User created = userRepository.save(
                User.create("inactive2@test.com", "inactive2",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        User inactive = userRepository.save(
                new User(
                        created.userId(),
                        created.email(),
                        created.username(),
                        created.passwordHash(),
                        created.role(),
                        false,
                        created.createdAt()
                )
        ).block();

        webTestClient.post()
                .uri("/api/admin/users/" + inactive.userId() + "/activate")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AdminController.UserSummaryResponse.class)
                .value(response -> {
                    assertThat(response.userId()).isEqualTo(inactive.userId());
                    assertThat(response.active()).isTrue();
                });

        User updated = userRepository.findById(inactive.userId()).block();
        assertThat(updated.active()).isTrue();
    }

    @Test
    void updateRole_asAdmin_returns200AndPersistsNewRole() {
        User target = userRepository.save(
                User.create("viewer@test.com", "viewer-user",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();

        webTestClient.patch()
                .uri("/api/admin/users/" + target.userId() + "/role")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of("role", "ENGINEER"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AdminController.UserSummaryResponse.class)
                .value(response -> assertThat(response.role()).isEqualTo("engineer"));

        User updated = userRepository.findById(target.userId()).block();
        assertThat(updated.role()).isEqualTo(Role.ENGINEER);
    }

    @Test
    void hardDeleteUser_asAdmin_removesUserAndOwnedArtifacts() {
        User target = userRepository.save(
                User.create("delete@test.com", "delete-user",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String sessionId = "delete-session-1";

        databaseClient.sql("INSERT INTO conversations (conversation_id, user_id, title) VALUES (:conversationId, :userId, :title)")
                .bind("conversationId", conversationId)
                .bind("userId", target.userId())
                .bind("title", "Delete me")
                .then()
                .block();
        databaseClient.sql("INSERT INTO messages (message_id, conversation_id, role, raw_input, session_id) VALUES (:messageId, :conversationId, :role, :rawInput, :sessionId)")
                .bind("messageId", messageId)
                .bind("conversationId", conversationId)
                .bind("role", "user")
                .bind("rawInput", "hello")
                .bind("sessionId", sessionId)
                .then()
                .block();
        databaseClient.sql("""
                INSERT INTO stm_entries (session_id, hdlc_client_sap, hdlc_server_sap, updated_at)
                VALUES (:sessionId, :clientSap, :serverSap, NOW())
                """)
                .bind("sessionId", sessionId)
                .bind("clientSap", "1")
                .bind("serverSap", "16")
                .then()
                .block();
        databaseClient.sql("""
                INSERT INTO message_feedback
                  (id, message_id, conversation_id, user_id, intent, input_class, feedback, prompt_snapshot, response_snapshot, model_name)
                VALUES
                  (:id, :messageId, :conversationId, :userId, :intent, :inputClass, :feedback, :promptSnapshot, :responseSnapshot, :modelName)
                """)
                .bind("id", UUID.randomUUID())
                .bind("messageId", messageId)
                .bind("conversationId", conversationId)
                .bind("userId", target.userId())
                .bind("intent", "FRAME_DECODE")
                .bind("inputClass", "query")
                .bind("feedback", "dislike")
                .bind("promptSnapshot", "prompt")
                .bind("responseSnapshot", "response")
                .bind("modelName", "lfm2.5-thinking")
                .then()
                .block();

        webTestClient.delete()
                .uri("/api/admin/users/" + target.userId() + "/hard")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isNoContent();

        assertThat(userRepository.findById(target.userId()).block()).isNull();
        assertThat(countByUserId("SELECT COUNT(*) FROM conversations WHERE user_id = :userId", target.userId())).isZero();
        assertThat(countByConversationId("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId", conversationId)).isZero();
        assertThat(countByUserId("SELECT COUNT(*) FROM message_feedback WHERE user_id = :userId", target.userId())).isZero();
        assertThat(countBySessionId("SELECT COUNT(*) FROM stm_entries WHERE session_id = :sessionId", sessionId)).isZero();
    }

    @Test
    void hardDeleteUser_lastAdmin_returns400() {
        webTestClient.delete()
                .uri("/api/admin/users/" + adminUser.userId() + "/hard")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(userRepository.findById(adminUser.userId()).block()).isNotNull();
    }

    @Test
    void deactivateUser_ownAccount_returns400() {
        webTestClient.delete()
                .uri("/api/admin/users/" + adminUser.userId())
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private long countByUserId(String sql, UUID userId) {
        return databaseClient.sql(sql)
                .bind("userId", userId)
                .map((row, metadata) -> {
                    Number value = row.get(0, Number.class);
                    return value != null ? value.longValue() : 0L;
                })
                .one()
                .blockOptional()
                .orElse(0L);
    }

    private long countByConversationId(String sql, UUID conversationId) {
        return databaseClient.sql(sql)
                .bind("conversationId", conversationId)
                .map((row, metadata) -> {
                    Number value = row.get(0, Number.class);
                    return value != null ? value.longValue() : 0L;
                })
                .one()
                .blockOptional()
                .orElse(0L);
    }

    private long countBySessionId(String sql, String sessionId) {
        return databaseClient.sql(sql)
                .bind("sessionId", sessionId)
                .map((row, metadata) -> {
                    Number value = row.get(0, Number.class);
                    return value != null ? value.longValue() : 0L;
                })
                .one()
                .blockOptional()
                .orElse(0L);
    }
}
