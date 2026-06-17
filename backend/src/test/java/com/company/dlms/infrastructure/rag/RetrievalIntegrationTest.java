package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.IntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest
class RetrievalIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15")
                    .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("dlms.security.jwt.secret", () -> "test_secret_must_be_at_least_32_characters_long_for_hs256");
        registry.add("dlms.security.session.encryption-key", () -> "dlmsassistant16c");
        registry.add("dlms.security.audit.secret", () -> "test-audit-hmac-key-at-least-32chars!!");
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:11434");
    }

    @MockBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // Mock embedding model to return a fixed vector
        float[] vector = new float[384];
        vector[0] = 0.5f;
        when(embeddingModel.embed(anyString())).thenReturn(vector);
    }

    @Test
    void testFullRetrievalFlow() {
        // 1. Insert test data via JDBC (pgvector requires specific string format)
        String content = "HDLC frame structure is defined in Blue Book";
        StringBuilder vecStr = new StringBuilder("[0.5");
        for (int i = 1; i < 384; i++) vecStr.append(",0");
        vecStr.append("]");

        jdbcTemplate.execute("INSERT INTO embeddings_dlms_knowledge (content, embedding, metadata, source_file, content_hash) " +
                "VALUES ('" + content + "', '" + vecStr + "', '{\"doc_type\":\"dlms\", \"source_file\":\"blue.pdf\", \"page_number\":42, \"section_title\":\"HDLC\"}', 'blue.pdf', 'hash1')");

        // 2. Search using the high-level RetrievalService
        StepVerifier.create(retrievalService.retrieve("HDLC frame structure", IntentType.FRAME_DECODE, 3))
                .assertNext(result -> {
                    assertThat(result.chunk().content()).isEqualTo(content);
                    assertThat(result.chunk().citation()).isNotNull();
                    assertThat(result.chunk().citation().sourceFile()).isEqualTo("blue.pdf");
                    assertThat(result.chunk().citation().sectionTitle()).isEqualTo("HDLC");
                    assertThat(result.combinedScore()).isGreaterThan(0);
                })
                .verifyComplete();
    }
}
