package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.memory.SessionNarrativeService;
import com.company.dlms.memory.StmService;
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
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test for WorkflowOrchestrator.
 * 
 * NOTE: This test uses Testcontainers and requires Docker to be running.
 * The original test attempted to mock AgentNode beans directly, but this is not possible
 * because:
 * 1. WorkflowOrchestrator uses LangGraph4j's CompiledGraph, not direct AgentNode injection
 * 2. Spring Test cannot mock multiple beans of the same interface with @MockBean
 * 
 * This test verifies the orchestration flow works with the actual LangGraph4j graph.
 */
@Testcontainers
@SpringBootTest
class WorkflowOrchestratorTest {

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

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private StmService stmService;

    @MockBean
    private SessionNarrativeService sessionNarrativeService;

    @BeforeEach
    void setUpEmbedding() {
        float[] vector = new float[384];
        vector[0] = 0.5f;
        when(embeddingModel.embed(anyString())).thenReturn(vector);
    }

    @Test
    void testExecute_hexFrameInput_returnsResult() {
        // Mock STM and session narrative to return pass-through state
        when(stmService.loadStmSync(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionNarrativeService.loadNarrativeSync(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRequest req = new WorkflowRequest("s1", "c1", "7EA023210313A5E57E", "engineer");

        StepVerifier.create(orchestrator.execute(req))
                .assertNext(res -> {
                    assertThat(res.sessionId()).isEqualTo("s1");
                    assertThat(res.inputClass()).isEqualTo(InputClass.HEX_FRAME);
                    assertThat(res.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
                })
                .verifyComplete();
    }

    @Test
    void testExecute_alarmCode_returnsResult() {
        when(stmService.loadStmSync(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionNarrativeService.loadNarrativeSync(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRequest req = new WorkflowRequest("s2", "c2", "DCU_COMM_FAIL", "admin");

        StepVerifier.create(orchestrator.execute(req))
                .assertNext(res -> {
                    assertThat(res.sessionId()).isEqualTo("s2");
                    assertThat(res.inputClass()).isEqualTo(InputClass.ALARM_CODE);
                    assertThat(res.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
                })
                .verifyComplete();
    }

    @Test
    void testExecute_documentationQuery_returnsResult() {
        when(stmService.loadStmSync(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionNarrativeService.loadNarrativeSync(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRequest req = new WorkflowRequest("s3", "c3", "explain the green book structure", "viewer");

        StepVerifier.create(orchestrator.execute(req))
                .assertNext(res -> {
                    assertThat(res.sessionId()).isEqualTo("s3");
                    assertThat(res.inputClass()).isEqualTo(InputClass.QUERY);
                    assertThat(res.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
                })
                .verifyComplete();
    }

    @Test
    void testExecute_protocolExplanationQueryRoutesToDocumentation() {
        when(stmService.loadStmSync(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionNarrativeService.loadNarrativeSync(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRequest req = new WorkflowRequest("s3b", "c3b", "Explain HDLC frame structure", "viewer");

        StepVerifier.create(orchestrator.execute(req))
                .assertNext(res -> {
                    assertThat(res.sessionId()).isEqualTo("s3b");
                    assertThat(res.inputClass()).isEqualTo(InputClass.QUERY);
                    assertThat(res.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
                })
                .verifyComplete();
    }

    @Test
    void testExecuteRaw_securityExplainQuery_buildsSecurityPrompt() {
        when(stmService.loadStmSync(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionNarrativeService.loadNarrativeSync(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRequest req = new WorkflowRequest("s4", "c4", "Explain HLS authentication", "engineer");

        StepVerifier.create(orchestrator.executeRaw(req))
                .assertNext(state -> {
                    assertThat(state.sessionId()).isEqualTo("s4");
                    assertThat(state.inputClass()).isEqualTo(InputClass.QUERY);
                    assertThat(state.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
                    assertThat(state.llmPrompt()).contains("=== SECURITY EXPLAINER MODE ===");
                })
                .verifyComplete();
    }

    @Test
    void testExecute_sessionIdPropagated() {
        when(stmService.loadStmSync(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionNarrativeService.loadNarrativeSync(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRequest req = new WorkflowRequest("session-xyz", "conv-xyz", "hello", "viewer");

        StepVerifier.create(orchestrator.execute(req))
                .assertNext(res -> assertThat(res.sessionId()).isEqualTo("session-xyz"))
                .verifyComplete();
    }
}
