package com.company.dlms.infrastructure.reflection;

import com.company.dlms.domain.reflection.MessageFeedback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MessageFeedbackRepositoryTest {

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

    @Autowired private MessageFeedbackRepository repository;
    @MockBean  private EmbeddingModel embeddingModel;
    @MockBean  private com.company.dlms.infrastructure.llm.OllamaStreamingClient ollamaStreamingClient;

    @BeforeEach
    void setUp() {
        repository.deleteAll().block();
    }

    @Test
    void save_feedback_isRetrievable() {
        MessageFeedback fb = feedback("FRAME_DECODE", "like");
        repository.save(fb).block();

        List<MessageFeedback> found = repository.findByIntentAndFeedback("FRAME_DECODE", "like")
                .collectList().block();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).intent()).isEqualTo("FRAME_DECODE");
        assertThat(found.get(0).feedback()).isEqualTo("like");
    }

    @Test
    void findRecentDisliked_returnsNewestFirst() {
        Instant older = Instant.now().minusSeconds(120);
        Instant newer = Instant.now();

        repository.save(feedbackAt("DOCUMENTATION", "dislike", older)).block();
        repository.save(feedbackAt("FRAME_DECODE", "dislike", newer)).block();
        repository.save(feedback("FRAME_DECODE", "like")).block(); // should not appear

        List<MessageFeedback> results = repository.findRecentDisliked(10).collectList().block();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).createdAt()).isAfterOrEqualTo(results.get(1).createdAt());
    }

    private MessageFeedback feedback(String intent, String feedbackType) {
        return feedbackAt(intent, feedbackType, Instant.now());
    }

    private MessageFeedback feedbackAt(String intent, String feedbackType, Instant createdAt) {
        return new MessageFeedback(
                null, null, null, UUID.randomUUID(),
                intent, "HEX_FRAME", feedbackType,
                null, null, "test-model", createdAt
        );
    }
}
