package com.company.dlms.integration;
 
import com.company.dlms.agent.DecoderAgentNode;
import com.company.dlms.agent.decoder.GbtAssembler;
import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.infrastructure.rag.RetrievalService;
import com.company.dlms.memory.DlmsMemoryProperties;
import com.company.dlms.memory.StmService;
import com.company.dlms.workflow.WorkflowState;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
 
import java.util.HexFormat;
 
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
 
@Testcontainers
class DecodeIntegrationTest {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
 
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
 
        db.sql("""
                CREATE EXTENSION IF NOT EXISTS pgcrypto;
                CREATE TABLE IF NOT EXISTS kg_nodes (
                    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    type     TEXT NOT NULL,
                    label    TEXT NOT NULL,
                    metadata JSONB
                );
                CREATE TABLE IF NOT EXISTS stm_entries (
                    session_id        TEXT PRIMARY KEY,
                    hdlc_client_sap   TEXT,
                    hdlc_server_sap   TEXT,
                    frame_counter     BIGINT,
                    frame_counter_hex TEXT,
                    security_suite    INTEGER,
                    invoke_id         TEXT,
                    association_state TEXT,
                    max_pdu_size      INTEGER,
                    last_obis         TEXT,
                    last_ic           INTEGER,
                    updated_at        TIMESTAMP DEFAULT NOW()
                );
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
 
        db.sql("""
                INSERT INTO kg_nodes(type, label, metadata)
                VALUES ('OBIS', '1.0.1.8.0.255', CAST('{"description":"Active energy import (total)","ic":3,"unit":"Wh","scaler":-1}' AS JSONB))
                """).fetch().rowsUpdated().block();
    }
 
    @Test
    void decodePipeline_resolvesObisFromKg() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), anyInt())).thenReturn(Flux.empty());
 
        ObisResolver obisResolver = new ObisResolver(db, retrieval);
        GbtAssembler gbtAssembler = new GbtAssembler(db);
        DlmsMemoryProperties props = new DlmsMemoryProperties("stm_entries", "episodic_blocks", 10);
        StmService stmService = new StmService(db, props);
        McpDispatcher mcpDispatcher = mock(McpDispatcher.class);
        when(mcpDispatcher.dispatch(anyString(), anyMap()))
                .thenReturn(Mono.just(McpResult.unavailable("dlms.parse_hdlc")));
        
        com.company.dlms.agent.SessionEventService sessionEventService = new com.company.dlms.agent.SessionEventService();
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = new com.company.dlms.memory.SessionNarrativeService(db, props, new com.fasterxml.jackson.databind.ObjectMapper());
        
        DecoderAgentNode node = new DecoderAgentNode(obisResolver, gbtAssembler, stmService, mcpDispatcher, sessionEventService, sessionNarrativeService);
 
        // Frame contains: LLC + GET_RESPONSE (0xC4) + AXDR STRUCTURE[1] with OCTET_STRING(6) = 1.0.1.8.0.255
        byte[] apdu = new byte[]{(byte) 0xC4, 0x02, 0x01, 0x09, 0x06, 0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF};
        byte[] info = concat(new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00}, apdu);
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(0x02),
                encodeAddress(0x01),
                (byte) 0x00,
                info
        );
        String hex = HEX.formatHex(frame);
 
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));
        if (out.decodeResult() == null) {
            System.err.println("Decode failed. Errors: " + out.errors());
        }
        assertNotNull(out.decodeResult(), "DecodeResult should not be null. Errors: " + out.errors());
 
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertTrue(dr.hdlcFrame().fcsValid());
        assertFalse(dr.obisResolutions().isEmpty());
        assertEquals(ResolutionTier.KG, dr.obisResolutions().getFirst().tierUsed());
        assertEquals("Active energy import (total)", dr.obisResolutions().getFirst().description());
    }

    @Test
    void controlFrameDecodePersistsNarrativeEventToEpisodicBlocks() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(anyString(), any(), anyInt())).thenReturn(Flux.empty());

        ObisResolver obisResolver = new ObisResolver(db, retrieval);
        GbtAssembler gbtAssembler = new GbtAssembler(db);
        DlmsMemoryProperties props = new DlmsMemoryProperties("stm_entries", "episodic_blocks", 10);
        StmService stmService = new StmService(db, props);
        McpDispatcher mcpDispatcher = mock(McpDispatcher.class);
        when(mcpDispatcher.dispatch(anyString(), anyMap()))
                .thenReturn(Mono.just(McpResult.unavailable("dlms.parse_hdlc")));

        com.company.dlms.agent.SessionEventService sessionEventService = new com.company.dlms.agent.SessionEventService();
        com.company.dlms.memory.SessionNarrativeService sessionNarrativeService = new com.company.dlms.memory.SessionNarrativeService(db, props, new com.fasterxml.jackson.databind.ObjectMapper());

        DecoderAgentNode node = new DecoderAgentNode(obisResolver, gbtAssembler, stmService, mcpDispatcher, sessionEventService, sessionNarrativeService);

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("narrative-session", "c1", hex));

        assertNotNull(out.decodeResult(), "Control-frame decode should succeed before narrative persistence is verified.");

        Integer narrativeCount = db.sql("SELECT COUNT(*) AS c FROM episodic_blocks WHERE session_id = 'narrative-session'")
                .map((row, meta) -> row.get("c", Integer.class))
                .one()
                .block();

        String lastApduType = db.sql("SELECT apdu_type FROM episodic_blocks WHERE session_id = 'narrative-session' ORDER BY timestamp DESC LIMIT 1")
                .map((row, meta) -> row.get("apdu_type", String.class))
                .one()
                .block();

        assertEquals(1, narrativeCount);
        assertEquals("U_FRAME (SNRM)", lastApduType);
    }
 
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
 
    private static byte[] encodeAddress(int address) {
        int v = address;
        byte[] tmp = new byte[8];
        int n = 0;
        while (true) {
            int chunk = v & 0x7F;
            v >>>= 7;
            int b = (chunk << 1) & 0xFE;
            boolean last = (v == 0);
            if (last) b |= 0x01;
            tmp[n++] = (byte) b;
            if (last) break;
        }
        byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }
 
    private static byte[] buildFrame(byte[] format, byte[] destAddr, byte[] srcAddr, byte control, byte[] information) {
        int contentLen = format.length + destAddr.length + srcAddr.length + 1 + information.length;
        byte[] content = new byte[contentLen];
        int o = 0;
        System.arraycopy(format, 0, content, o, format.length);
        o += format.length;
        System.arraycopy(destAddr, 0, content, o, destAddr.length);
        o += destAddr.length;
        System.arraycopy(srcAddr, 0, content, o, srcAddr.length);
        o += srcAddr.length;
        content[o++] = control;
        System.arraycopy(information, 0, content, o, information.length);
 
        int fcs = crc16IbmArcXorFFFF(content);
        byte[] out = new byte[1 + content.length + 2 + 1];
        out[0] = 0x7E;
        System.arraycopy(content, 0, out, 1, content.length);
        out[1 + content.length] = (byte) (fcs & 0xFF);
        out[1 + content.length + 1] = (byte) ((fcs >>> 8) & 0xFF);
        out[out.length - 1] = 0x7E;
        return out;
    }

    private static String buildHdlcHexUFrame(byte control) {
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(0x03),
                encodeAddress(0x03),
                control,
                new byte[0]
        );
        return HEX.formatHex(frame);
    }
 
    private static int crc16IbmArcXorFFFF(byte[] data) {
        int crc = 0x0000;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc = (crc >>> 1);
                }
                crc &= 0xFFFF;
            }
        }
        crc ^= 0xFFFF;
        return crc & 0xFFFF;
    }
}
