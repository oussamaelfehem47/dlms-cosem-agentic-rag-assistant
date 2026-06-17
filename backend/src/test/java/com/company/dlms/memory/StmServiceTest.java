package com.company.dlms.memory;

import com.company.dlms.config.R2dbcConfig;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataR2dbcTest
@Import({R2dbcConfig.class, MemoryConfig.class, StmService.class})
class StmServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15")
                    .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        // Ensure our services target the Phase 0 table name
        registry.add("dlms.memory.stm-table", () -> "stm_entries");
    }

    @Autowired
    private StmService stmService;

    @Autowired
    private DatabaseClient databaseClient;

    @Test
    void testLoadStm_noExistingRow_returnsUnchangedState() {
        WorkflowState input = WorkflowState.empty("s1", "c1", "hello");
        StepVerifier.create(stmService.loadStm(input))
                .assertNext(out -> assertThat(out).isEqualTo(input))
                .verifyComplete();
    }

    @Test
    void testLoadStm_existingRow_mergesFieldsCorrectly() {
        databaseClient.sql("""
                        INSERT INTO stm_entries (session_id, hdlc_client_sap, frame_counter_hex, updated_at)
                        VALUES ('s2', '16', '0x01', NOW())
                        """)
                .fetch().rowsUpdated()
                .as(StepVerifier::create)
                .expectNextCount(1)
                .verifyComplete();

        WorkflowState input = WorkflowState.empty("s2", "c2", "hello")
                .toBuilder()
                .hdlcClientSap(null)
                .frameCounterHex(null)
                .build();

        StepVerifier.create(stmService.loadStm(input))
                .assertNext(out -> {
                    assertThat(out.hdlcClientSap()).isEqualTo("16");
                    assertThat(out.frameCounterHex()).isEqualTo("0x01");
                })
                .verifyComplete();
    }

    @Test
    void testSaveStm_insertsNewRow() {
        WorkflowState state = WorkflowState.empty("s3", "c3", "hello")
                .toBuilder()
                .hdlcClientSap("1")
                .hdlcServerSap("16")
                .frameCounter("42")
                .build();

        StepVerifier.create(stmService.saveStm(state))
                .verifyComplete();

        StepVerifier.create(databaseClient.sql("SELECT hdlc_client_sap, hdlc_server_sap, frame_counter FROM stm_entries WHERE session_id='s3'")
                        .map((row, meta) -> row.get("frame_counter", Long.class))
                        .one())
                .assertNext(fc -> assertThat(fc).isEqualTo(42L))
                .verifyComplete();
    }

    @Test
    void testSaveStm_upsertMergesWithCoalesce() {
        databaseClient.sql("""
                        INSERT INTO stm_entries (session_id, hdlc_client_sap, updated_at)
                        VALUES ('s4', 'EXISTING', NOW())
                        """)
                .fetch().rowsUpdated()
                .as(StepVerifier::create)
                .expectNextCount(1)
                .verifyComplete();

        WorkflowState newState = WorkflowState.empty("s4", "c4", "hello")
                .toBuilder()
                .hdlcClientSap(null) // should NOT overwrite existing
                .hdlcServerSap("NEW_SERVER")
                .build();

        StepVerifier.create(stmService.saveStm(newState))
                .verifyComplete();

        StepVerifier.create(databaseClient.sql("SELECT hdlc_client_sap, hdlc_server_sap FROM stm_entries WHERE session_id='s4'")
                        .map((row, meta) -> row.get("hdlc_client_sap", String.class) + "|" + row.get("hdlc_server_sap", String.class))
                        .one())
                .assertNext(v -> assertThat(v).isEqualTo("EXISTING|NEW_SERVER"))
                .verifyComplete();
    }
}

