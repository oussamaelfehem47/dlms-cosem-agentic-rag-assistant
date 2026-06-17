package com.company.dlms.agent;

import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.decoder.GbtAssembler;
import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.SFrameType;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.memory.StmService;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecoderAgentNodeMcpTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private ObisResolver resolver;
    private GbtAssembler gbtAssembler;
    private StmService stmService;
    private McpDispatcher mcpDispatcher;
    private SessionEventService sessionEventService;
    private com.company.dlms.memory.SessionNarrativeService sessionNarrativeService;
    private DecoderAgentNode node;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resolver = mock(ObisResolver.class);
        gbtAssembler = mock(GbtAssembler.class);
        stmService = mock(StmService.class);
        mcpDispatcher = mock(McpDispatcher.class);
        sessionEventService = new SessionEventService();
        sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        objectMapper = new ObjectMapper();
        
        when(stmService.saveStm(any())).thenReturn(Mono.empty());
        when(sessionNarrativeService.appendEvent(any())).thenReturn(Mono.empty());
        
        node = new DecoderAgentNode(resolver, gbtAssembler, stmService, mcpDispatcher, sessionEventService, sessionNarrativeService);
    }

    @Test
    void mcpSuccess_javaParserNotCalled_mcpUsedTrue() throws Exception {
        String jsonStr = """
                {
                    "hcs_valid": true,
                    "fcs_valid": true,
                    "frame_type": "U_FRAME",
                    "information_hex": "",
                    "client_address": 1,
                    "server_address": 1,
                    "errors": []
                }
                """;
        var mcpJson = objectMapper.readTree(jsonStr);

        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.success("dlms.parse_hdlc", mcpJson)));

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));

        assertNotNull(out.decodeResult());
        assertTrue(out.mcpUsed(), "MCP should be used when successful");
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.U_FRAME, dr.hdlcFrame().frameType());
        assertTrue(dr.hdlcFrame().fcsValid());
        assertEquals(UFrameType.SNRM, dr.hdlcFrame().uFrameType());
    }

    @Test
    void mcpFailure_javaParserIsCalled_mcpUsedFalse() {
        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.failure("dlms.parse_hdlc", "Connection refused")));

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));

        assertNotNull(out.decodeResult());
        assertFalse(out.mcpUsed(), "MCP should not be used when failed");
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.U_FRAME, dr.hdlcFrame().frameType());
    }

    @Test
    void mcpUnavailable_javaParserIsCalled_mcpUsedFalse() {
        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.unavailable("dlms.parse_hdlc")));

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));

        assertNotNull(out.decodeResult());
        assertFalse(out.mcpUsed(), "MCP should not be used when unavailable");
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.U_FRAME, dr.hdlcFrame().frameType());
    }

    @Test
    void mcpSuccessBadJson_javaParserIsCalled_mcpUsedFalse() throws Exception {
        String jsonStr = """
                {
                    "hcs_valid": true,
                    "fcs_valid": true
                }
                """;
        var mcpJson = objectMapper.readTree(jsonStr);

        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.success("dlms.parse_hdlc", mcpJson)));

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));

        assertNotNull(out.decodeResult());
        assertFalse(out.mcpUsed(), "MCP should not be used when mapping fails");
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.U_FRAME, dr.hdlcFrame().frameType());
    }

    @Test
    void mcpSuccessWithSapKeys_javaParserNotCalled_mcpUsedTrue() throws Exception {
        String jsonStr = """
                {
                    "fcs_valid": true,
                    "frame_type": "U_FRAME",
                    "information_hex": "",
                    "client_sap": 16,
                    "server_sap": 1,
                    "errors": []
                }
                """;
        var mcpJson = objectMapper.readTree(jsonStr);

        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.success("dlms.parse_hdlc", mcpJson)));

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));

        assertNotNull(out.decodeResult());
        assertTrue(out.mcpUsed(), "MCP should be used when tool returns sap keys");
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(16, dr.hdlcFrame().clientSap());
        assertEquals(1, dr.hdlcFrame().serverSap());
    }

    @Test
    void wrappedFrameNormalizationStillSendsCleanHexPayloadToMcp() throws Exception {
        String jsonStr = """
                {
                    "fcs_valid": true,
                    "frame_type": "U_FRAME",
                    "information_hex": "",
                    "client_address": 1,
                    "server_address": 1,
                    "errors": []
                }
                """;
        var mcpJson = objectMapper.readTree(jsonStr);

        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.success("dlms.parse_hdlc", mcpJson)));

        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode this frame: " + hex)
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        hex,
                        DlmsNormalizedKind.FRAME_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        java.util.List.of(),
                        "Recovered embedded HDLC frame from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNotNull(out.decodeResult());
        verify(mcpDispatcher).dispatch(eq("dlms.parse_hdlc"), eq(Map.of("frame_hex", hex)));
    }

    @Test
    void mcpMalformedSupervisoryFramePreservesSubtypeFromToolPayload() throws Exception {
        String jsonStr = """
                {
                    "fcs_valid": false,
                    "frame_type": "S_FRAME",
                    "s_frame_type": "RR",
                    "information_hex": "7b2b80c401c100be1004800a0601602801ff000000065ff00000008040",
                    "client_address": 1,
                    "server_address": 35651712,
                    "errors": ["Unexpected information field on supervisory frame", "FCS invalid"]
                }
                """;
        var mcpJson = objectMapper.readTree(jsonStr);

        when(mcpDispatcher.dispatch(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(McpResult.success("dlms.parse_hdlc", mcpJson)));

        WorkflowState out = node.process(WorkflowState.empty(
                "s1",
                "c1",
                "7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E"
        ));

        assertNotNull(out.decodeResult());
        assertTrue(out.mcpUsed(), "MCP salvage should still be used when it returns structured outer-frame data");
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.S_FRAME, dr.hdlcFrame().frameType());
        assertEquals(SFrameType.RR, dr.hdlcFrame().sFrameType());
        assertFalse(dr.hdlcFrame().fcsValid());
        assertTrue(dr.parseErrors().contains("Unexpected information field on supervisory frame"));
        assertEquals(2, dr.parseErrors().size());
    }

    private static String buildHdlcHexUFrame(byte control) {
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(0x01),
                encodeAddress(0x01),
                control,
                new byte[]{}
        );
        return HEX.formatHex(frame);
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
