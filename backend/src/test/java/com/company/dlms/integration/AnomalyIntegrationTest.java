package com.company.dlms.integration;

import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.workflow.WorkflowOrchestrator;
import com.company.dlms.workflow.WorkflowRequest;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
public class AnomalyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15")
                    .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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

        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("dlms.security.jwt.secret", () -> "test_secret_must_be_at_least_32_characters_long_for_hs256");
        registry.add("dlms.security.session.encryption-key", () -> "dlmsassistant16c");
        registry.add("dlms.security.audit.secret", () -> "test-audit-hmac-key-at-least-32chars!!");
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:11434");
    }

    @Autowired
    private WorkflowOrchestrator orchestrator;

    @Test
    public void testFrameCounterRegression() {
        String sessionId = "test-session-" + UUID.randomUUID();
        
        // 1. Send Frame 1 (FC 1000)
        String frame1 = "7EA019032110E6E60030000003E800000000C401410000007E";
        WorkflowRequest req1 = new WorkflowRequest(sessionId, null, frame1, "ADMIN", InputClass.HEX_FRAME);
        
        WorkflowState state1 = orchestrator.executeRaw(req1).block();
        assertNotNull(state1);
        assertEquals("1000", state1.frameCounter());
        assertTrue(state1.anomalies() == null || state1.anomalies().isEmpty());

        // 2. Send Frame 2 (FC 500)
        String frame2 = "7EA019032110E6E60030000001F400000000C401410000007E";
        WorkflowRequest req2 = new WorkflowRequest(sessionId, null, frame2, "ADMIN", InputClass.HEX_FRAME);
        
        WorkflowState state2 = orchestrator.executeRaw(req2).block();
        assertNotNull(state2);
        assertEquals("500", state2.frameCounter());
        
        // This is what we are testing!
        assertNotNull(state2.anomalies());
        assertFalse(state2.anomalies().isEmpty(), "Anomaly list should not be empty");
        assertTrue(state2.anomalies().get(0).contains("FC-001"), "Should detect FC-001 regression: " + state2.anomalies());
    }
}
