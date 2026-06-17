package com.company.dlms.agent.decoder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class GbtAssemblerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg15")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    static DatabaseClient db;

    @BeforeAll
    static void initDb() {
        String r2dbcUrl = "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(r2dbcUrl)
                .mutate()
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build();
        ConnectionFactory cf = ConnectionFactories.get(options);
        db = DatabaseClient.create(cf);

        // Minimal schema for this test.
        db.sql("""
                CREATE EXTENSION IF NOT EXISTS pgcrypto;
                CREATE TABLE IF NOT EXISTS episodic_blocks (
                    id                UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
                    session_id        TEXT      NOT NULL,
                    frame_number      INTEGER   NOT NULL,
                    apdu_type         TEXT,
                    decode_stage      TEXT,
                    association_state TEXT,
                    obis              TEXT,
                    ic                INTEGER,
                    errors            JSONB,
                    warnings          JSONB,
                    anomalies         JSONB,
                    timestamp         TIMESTAMP DEFAULT NOW()
                );
                """).fetch().rowsUpdated().block();
    }

    @AfterAll
    static void cleanup() {
        if (POSTGRES != null) POSTGRES.stop();
    }

    @Test
    void threeBlockAssembly_partialThenAssembled() {
        GbtAssembler assembler = new GbtAssembler(db);

        assertEquals(Optional.empty(), assembler.onBlock("s1", 1, false, new byte[]{0x01}).block());
        assertEquals(Optional.empty(), assembler.onBlock("s1", 2, false, new byte[]{0x02}).block());
        Optional<byte[]> out = assembler.onBlock("s1", 3, true, new byte[]{0x03}).block();

        assertNotNull(out);
        assertTrue(out.isPresent());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, out.get());
    }

    @Test
    void sessionIsolation() {
        GbtAssembler assembler = new GbtAssembler(db);

        assembler.onBlock("a", 1, false, new byte[]{0x0A}).block();
        assembler.onBlock("b", 1, true, new byte[]{0x0B}).block();

        Optional<byte[]> outA = assembler.onBlock("a", 2, true, new byte[]{0x0C}).block();
        assertTrue(outA.isPresent());
        assertArrayEquals(new byte[]{0x0A, 0x0C}, outA.get());
    }

    @Test
    void cleanupDeletesStalePartialRows() {
        // Insert a stale row manually.
        db.sql("""
                INSERT INTO episodic_blocks(session_id, frame_number, apdu_type, decode_stage, errors, timestamp)
                VALUES ('stale', 1, 'GBT', 'GBT_PARTIAL', CAST('{"block_hex":"AA"}' AS JSONB), NOW() - INTERVAL '10 minutes')
                """).fetch().rowsUpdated().block();

        GbtAssembler assembler = new GbtAssembler(db);
        Long deleted = assembler.cleanupStale().block();
        assertNotNull(deleted);
        assertTrue(deleted >= 1);

        Integer remaining = db.sql("""
                SELECT COUNT(*) AS c
                FROM episodic_blocks
                WHERE session_id='stale' AND decode_stage='GBT_PARTIAL'
                """).map((row, meta) -> row.get("c", Integer.class)).one().block();
        assertEquals(0, remaining);
    }
}

