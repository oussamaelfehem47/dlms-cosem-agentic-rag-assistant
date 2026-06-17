package com.company.dlms.agent;
 
import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.decoder.GbtAssembler;
import com.company.dlms.agent.decoder.HdlcParser;
import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.domain.decoder.*;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.memory.StmService;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
 
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
 
class DecoderAgentNodeTest {
 
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private ObisResolver resolver;
    private GbtAssembler gbtAssembler;
    private StmService stmService;
    private McpDispatcher mcpDispatcher;
    private SessionEventService sessionEventService;
    private com.company.dlms.memory.SessionNarrativeService sessionNarrativeService;
    private DecoderAgentNode node;
 
    @BeforeEach
    void setUp() {
        resolver = mock(ObisResolver.class);
        gbtAssembler = mock(GbtAssembler.class);
        stmService = mock(StmService.class);
        mcpDispatcher = mock(McpDispatcher.class);
        sessionEventService = new SessionEventService();
        sessionNarrativeService = mock(com.company.dlms.memory.SessionNarrativeService.class);
        
        when(stmService.saveStm(any())).thenReturn(Mono.empty());
        when(sessionNarrativeService.appendEvent(any())).thenReturn(Mono.empty());
        
        // Default: MCP unavailable so Java pipeline runs
        when(mcpDispatcher.dispatch(anyString(), anyMap()))
                .thenReturn(Mono.just(McpResult.unavailable("dlms.parse_hdlc")));
        
        node = new DecoderAgentNode(resolver, gbtAssembler, stmService, mcpDispatcher, sessionEventService, sessionNarrativeService);
    }
 
    @Test
    void validGetResponseHex_fullPipeline() {
        when(resolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "desc", 3, "Wh", -1, ResolutionTier.KG)));
 
        String hex = buildHdlcHexIFrameWithApdu(
                new byte[]{(byte) 0xC4, 0x02, 0x01, 0x09, 0x06, 0x01, 0x00, 0x01, 0x08, 0x00, (byte) 0xFF}
        );
 
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));
        assertNotNull(out.decodeResult());
 
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(ApduType.GET_RESPONSE, dr.apduType());
        assertTrue(dr.hdlcFrame().fcsValid());
        assertNotNull(dr.axdrTree());
        assertFalse(dr.obisResolutions().isEmpty());
        verify(stmService, atLeastOnce()).saveStm(any());
    }
 
    @Test
    void snrmUFrame_noApdu() {
        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));
 
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.U_FRAME, dr.hdlcFrame().frameType());
        assertNull(dr.axdrTree());
    }

    @Test
    void decodeWaitsForSessionNarrativeAppendBeforeReturning() {
        when(sessionNarrativeService.appendEvent(any()))
                .thenReturn(Mono.fromCallable(() -> {
                    Thread.sleep(75);
                    return 1;
                }).then());

        String hex = buildHdlcHexUFrame((byte) 0x83);
        long startedAt = System.nanoTime();

        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertNotNull(out.decodeResult());
        assertThat(elapsedMs).isGreaterThanOrEqualTo(60L);
    }
 
    @Test
    void neverThrows_onBadInput() {
        assertDoesNotThrow(() -> node.process(WorkflowState.empty("s1", "c1", "ZZ")));
    }

    @Test
    void wrappedHdlcFrameExtractsAndDecodesDeterministically() {
        String hex = buildHdlcHexUFrame((byte) 0x83);
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", "Decode this HDLC frame: " + hex));

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.U_FRAME, dr.hdlcFrame().frameType());
        assertEquals(hex, dr.rawHex());
        assertTrue(out.errors().isEmpty());
    }

    @Test
    void wrappedApduHexDecodesDeterministicallyWithoutHdlc() {
        when(resolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "desc", 3, "Wh", -1, ResolutionTier.KG)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode APDU C4020109060100010800FF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "C4020109060100010800FF",
                        DlmsNormalizedKind.APDU_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered APDU payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(ApduType.GET_RESPONSE, dr.apduType());
        assertNotNull(dr.axdrTree());
        assertFalse(dr.obisResolutions().isEmpty());
        assertThat(dr.processingMetadata().normalizedKind()).isEqualTo(DlmsNormalizedKind.APDU_HEX);
        assertThat(dr.processingMetadata().provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
        verify(mcpDispatcher, never()).dispatch(eq("dlms.parse_hdlc"), anyMap());
    }

    @Test
    void directApduDecodePersistsLastObisIntoStm() {
        when(resolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "desc", 3, "Wh", -1, ResolutionTier.KG)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "C4020109060100010800FF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "C4020109060100010800FF",
                        DlmsNormalizedKind.APDU_HEX,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null,
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertThat(out.lastObis()).isEqualTo("1.0.1.8.0.255");
        verify(stmService).saveStm(argThat(saved -> "1.0.1.8.0.255".equals(saved.lastObis())));
    }

    @Test
    void wrappedAxdrHexDecodesDeterministicallyWithoutHdlc() {
        when(resolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "desc", 3, "Wh", -1, ResolutionTier.KG)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain AXDR payload 020109060100010800FF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "020109060100010800FF",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(ApduType.UNKNOWN, dr.apduType());
        assertNotNull(dr.axdrTree());
        assertFalse(dr.obisResolutions().isEmpty());
        assertThat(dr.processingMetadata().normalizedKind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        verify(mcpDispatcher, never()).dispatch(eq("dlms.parse_hdlc"), anyMap());
    }

    @Test
    void directObisLookupPersistsLastObisIntoStm() {
        when(resolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -3, ResolutionTier.KG)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "What is OBIS 1.0.1.8.0.255?")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "1.0.1.8.0.255",
                        DlmsNormalizedKind.OBIS_QUERY,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null,
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertThat(out.lastObis()).isEqualTo("1.0.1.8.0.255");
        verify(stmService).saveStm(argThat(saved -> "1.0.1.8.0.255".equals(saved.lastObis())));
    }

    @Test
    void explicitShortAxdrPayloadsStayDeterministic() {
        WorkflowState nullState = WorkflowState.empty("s1", "c1", "Explain AXDR payload 00")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "00",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState booleanState = WorkflowState.empty("s1", "c1", "Explain AXDR payload 0301")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "0301",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        DecodeResult nullResult = (DecodeResult) node.process(nullState).decodeResult();
        DecodeResult booleanResult = (DecodeResult) node.process(booleanState).decodeResult();

        assertThat(nullResult.axdrTree()).isInstanceOf(AxdrNull.class);
        assertThat(booleanResult.axdrTree()).isInstanceOf(AxdrBoolean.class);
        assertThat(((AxdrBoolean) booleanResult.axdrTree()).value()).isTrue();
    }

    @Test
    void explicitAxdrPromptOverridesApduClassificationForDataNotificationLikeBytes() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain AXDR payload 0102030111FF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "0102030111FF",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertThat(dr.apduType()).isEqualTo(ApduType.UNKNOWN);
        assertThat(dr.axdrTree()).isInstanceOf(AxdrArray.class);
        assertThat(((AxdrArray) dr.axdrTree()).elements()).hasSize(2);
    }

    @Test
    void unknownAxdrTagFallsBackDeterministicallyToOctetString() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain AXDR payload 99010203")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "99010203",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertThat(dr.axdrTree()).isInstanceOf(AxdrOctetString.class);
        assertThat(((AxdrOctetString) dr.axdrTree()).value()).containsExactly((byte) 0x01, (byte) 0x02, (byte) 0x03);
    }

    @Test
    void truncatedExplicitAxdrPayloadReturnsDeterministicParseError() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain AXDR payload 05FFFF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "05FFFF",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNull(out.decodeResult());
        assertThat(out.errors()).isNotEmpty();
        assertThat(out.errors().getFirst()).contains("Unexpected end of buffer for int32");
    }

    @Test
    void directPayloadProcessingMetadataPreservesDeterministicKinds() {
        WorkflowState apduState = WorkflowState.empty("s1", "c1", "Decode APDU C4020109060100010800FF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "C4020109060100010800FF",
                        DlmsNormalizedKind.APDU_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered APDU payload from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState axdrState = WorkflowState.empty("s1", "c1", "Explain AXDR payload 020109060100010800FF")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "020109060100010800FF",
                        DlmsNormalizedKind.AXDR_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered AXDR payload from wrapped prose input",
                        false
                ))
                .build();

        assertThat(((DecodeResult) node.process(apduState).decodeResult()).processingMetadata().normalizedKind())
                .isEqualTo(DlmsNormalizedKind.APDU_HEX);
        assertThat(((DecodeResult) node.process(axdrState).decodeResult()).processingMetadata().normalizedKind())
                .isEqualTo(DlmsNormalizedKind.AXDR_HEX);
    }

    @Test
    void wrappedObisQueryResolvesDeterministicallyWithoutGenericRetrievalGuessing() {
        when(resolver.resolve(eq("1.0.1.8.0.255"), anyString()))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "Active energy import total", 3, "Wh", -1, ResolutionTier.KG)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "What is OBIS 1.0.1.8.0.255?")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        "1.0.1.8.0.255",
                        DlmsNormalizedKind.OBIS_QUERY,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of(),
                        "Recovered OBIS code from wrapped prose input",
                        false
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertThat(dr.obisResolutions()).hasSize(1);
        assertThat(dr.obisResolutions().getFirst().description()).contains("Active energy import total");
        assertThat(dr.processingMetadata().normalizedKind()).isEqualTo(DlmsNormalizedKind.OBIS_QUERY);
        verify(mcpDispatcher, never()).dispatch(eq("dlms.parse_hdlc"), anyMap());
    }

    @Test
    void ambiguousMultipleFrameCandidatesReturnDeterministicErrorInsteadOfGuessing() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Decode frames 7EA00A030383CD6F7E and 7EA00A0101934D7E")
                .toBuilder()
                .dlmsNormalization(new DlmsInputNormalization(
                        null,
                        DlmsNormalizedKind.FRAME_HEX,
                        ParseProvenance.STRUCTURED_HEURISTIC,
                        List.of("Multiple DLMS payload candidates were found in the request"),
                        "Detected more than one HDLC frame candidate",
                        true
                ))
                .build();

        WorkflowState out = node.process(state);

        assertNull(out.decodeResult());
        assertThat(out.errors()).contains("Multiple DLMS payload candidates were found in the request");
        verify(mcpDispatcher, never()).dispatch(eq("dlms.parse_hdlc"), anyMap());
    }

    @Test
    void malformedSupervisoryFrameSalvagesTentativeOuterDecode() {
        WorkflowState out = node.process(WorkflowState.empty(
                "s1",
                "c1",
                "7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E"
        ));

        assertNotNull(out.decodeResult());
        DecodeResult dr = (DecodeResult) out.decodeResult();
        assertEquals(FrameType.S_FRAME, dr.hdlcFrame().frameType());
        assertEquals(SFrameType.RR, dr.hdlcFrame().sFrameType());
        assertFalse(dr.hdlcFrame().fcsValid());
        assertThat(dr.parseErrors()).contains(HdlcParser.ERR_UNEXPECTED_INFO_ON_SUPERVISORY_FRAME, "FCS invalid");
        assertThat(dr.parseErrors()).hasSize(2);
        assertThat(out.errors()).isEmpty();
    }
 
    @Test
    void oddLengthHex_errorsNonEmpty_decodeResultNull() {
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", "ABC"));
        assertNull(out.decodeResult());
        assertFalse(out.errors().isEmpty());
    }
 
    @Test
    void missingClosingFlag_errorsNonEmpty() {
        String ok = buildHdlcHexUFrame((byte) 0x83);
        String bad = ok.substring(0, ok.length() - 2) + "00"; // corrupt last 0x7E
 
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", bad));
        assertNull(out.decodeResult());
        assertFalse(out.errors().isEmpty());
    }
 
    @Test
    void truncatedAxdrPayload_errorsNonEmpty_decodeResultNull() {
        // APDU: C4 + AXDR INT32 but only 2 bytes provided => AxdrDecodeException
        String hex = buildHdlcHexIFrameWithApdu(new byte[]{(byte) 0xC4, 0x05, 0x00, 0x01});
        WorkflowState out = node.process(WorkflowState.empty("s1", "c1", hex));
 
        assertNull(out.decodeResult());
        assertFalse(out.errors().isEmpty());
    }
 
    private static String buildHdlcHexIFrameWithApdu(byte[] apduBytes) {
        byte[] info = concat(new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00}, apduBytes);
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(0x02),
                encodeAddress(0x01),
                (byte) 0x00,
                info
        );
        return HEX.formatHex(frame);
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
