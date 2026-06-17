package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StmFieldExtractorTest {

    @Test
    void extract_frameCounter_fromSecurityHeader() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        AxdrStructure securityHeader = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x30),
                new AxdrUint32(100L),
                new AxdrUint32(500L)
        ));
        DecodeResult result = new DecodeResult(frame, ApduType.GET_RESPONSE, securityHeader, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals(100L, fields.get("frameCounter"));
        assertEquals("00000064", fields.get("frameCounterHex"));
        assertEquals("500", fields.get("lastIc"));
    }

    @Test
    void extract_frameCounter_fromRawInfo_whenNoSecurityHeader() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, new byte[]{
                0x30, 0x00, 0x00, 0x03, (byte) 0xE8, 0x00, 0x00, 0x00, 0x00, (byte) 0xC4, 0x01, 0x41, 0x00, 0x00, 0x00
        }, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.GET_RESPONSE, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals(1000L, fields.get("frameCounter"));
        assertEquals("000003E8", fields.get("frameCounterHex"));
    }

    @Test
    void extract_frameCounterHex_nullWhenNoFrameCounter() {
        HdlcFrame frame = new HdlcFrame(FrameType.S_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.GET_RESPONSE, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("frameCounter"));
        assertNull(fields.get("frameCounterHex"));
    }

    @Test
    void extract_securitySuite_fromAarq() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        AxdrStructure aarqBody = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x10),
                new AxdrUint8((byte) 0x00),
                new AxdrUint16(0xFFFF),
                new AxdrUint16(2000),
                new AxdrUint8((byte) 0x02)   // security suite = 2
        ));
        DecodeResult result = new DecodeResult(frame, ApduType.AARQ, aarqBody, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals(2, fields.get("securitySuite"));
    }

    @Test
    void extract_securitySuite_nullWhenNotAarq() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        AxdrStructure body = new AxdrStructure(List.of(new AxdrUint8((byte) 0x01)));
        DecodeResult result = new DecodeResult(frame, ApduType.GET_REQUEST, body, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("securitySuite"));
    }

    @Test
    void extract_securitySuite_scanFindsValueAtDepth1() {
        AxdrStructure l1 = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x01),
                new AxdrUint16(2000),
                new AxdrUint8((byte) 0x01)
        ));
        AxdrStructure root = new AxdrStructure(List.of(l1));
        Integer result = StmFieldExtractor.extractSecuritySuite(root);

        assertEquals(1, result);
    }

    @Test
    void extract_securitySuite_scanReturnsNullWhenOutOfRange() {
        AxdrStructure l1 = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x05)   // 5 > 2
        ));
        AxdrStructure root = new AxdrStructure(List.of(l1));
        Integer result = StmFieldExtractor.extractSecuritySuite(root);

        assertNull(result);
    }

    @Test
    void extract_securitySuite_scanReturnsNullWhenDepthExceeded() {
        // Nested deeper than 3
        AxdrStructure l4 = new AxdrStructure(List.of(new AxdrUint8((byte) 0x01)));
        AxdrStructure l3 = new AxdrStructure(List.of(l4));
        AxdrStructure l2 = new AxdrStructure(List.of(l3));
        AxdrStructure root = new AxdrStructure(List.of(l2));
        Integer result = StmFieldExtractor.extractSecuritySuite(root);

        assertNull(result);
    }

    @Test
    void extract_maxPduSize_fromAarq() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        // AARQ body without any Uint16 >= 128 before the actual maxPduSize
        AxdrStructure aarqBody = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x10),
                new AxdrUint8((byte) 0x00),
                new AxdrUint8((byte) 0x00),  // protocol version as Uint8, not Uint16
                new AxdrUint16(2000),         // maxPduSize = 2000 (first Uint16 >= 128)
                new AxdrUint8((byte) 0x02)
        ));
        DecodeResult result = new DecodeResult(frame, ApduType.AARQ, aarqBody, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals(2000, fields.get("maxPduSize"));
    }

    @Test
    void extract_maxPduSize_nullWhenNotAarq() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        AxdrStructure body = new AxdrStructure(List.of(new AxdrUint16(2000)));
        DecodeResult result = new DecodeResult(frame, ApduType.GET_REQUEST, body, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("maxPduSize"));
    }

    @Test
    void extract_maxPduSize_scanFindsFirstMatch() {
        // scanForMaxPduSize returns the first AxdrUint16 >= 128 found in DFS order
        AxdrStructure l1 = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x01),
                new AxdrUint16(2000),    // first Uint16 >= 128 → returned
                new AxdrUint16(3000)     // would be next but first match wins
        ));
        AxdrStructure root = new AxdrStructure(List.of(l1));
        Integer result = StmFieldExtractor.extractMaxPduSize(root);

        assertEquals(2000, result);
    }

    @Test
    void extract_maxPduSize_scanReturnsNullWhenBelow128() {
        AxdrStructure l1 = new AxdrStructure(List.of(
                new AxdrUint16(100)   // below 128
        ));
        AxdrStructure root = new AxdrStructure(List.of(l1));
        Integer result = StmFieldExtractor.extractMaxPduSize(root);

        assertNull(result);
    }

    @Test
    void extract_maxPduSize_scanReturnsNullWhenDepthExceeded() {
        AxdrStructure l4 = new AxdrStructure(List.of(new AxdrUint16(2000)));
        AxdrStructure l3 = new AxdrStructure(List.of(l4));
        AxdrStructure l2 = new AxdrStructure(List.of(l3));
        AxdrStructure root = new AxdrStructure(List.of(l2));
        Integer result = StmFieldExtractor.extractMaxPduSize(root);

        assertNull(result);
    }

    @Test
    void extract_associationState_forAare() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.AARE, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals("ASSOCIATED", fields.get("associationState"));
    }

    @Test
    void extract_associationState_forAarq() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.AARQ, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals("ASSOCIATING", fields.get("associationState"));
    }

    @Test
    void extract_associationState_forReleaseResponse() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.RLRE, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals("DISCONNECTED", fields.get("associationState"));
    }

    @Test
    void extract_associationState_default() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.GET_REQUEST, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("associationState"));
    }

    @Test
    void extract_invokeId_fromGetRequest() {
        // info must contain LLC header (3 bytes) + APDU tag + invoke-id byte (5+ bytes total)
        // invokeIdByte at info[4]: (invokeIdByte >> 2) & 0x3F = 1 → invokeIdByte = 0x04
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1,
                new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00, (byte) 0xC0, 0x04}, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.GET_REQUEST, null, List.of(), false, "01", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals("1", fields.get("invokeId"));
    }

    @Test
    void extract_invokeId_nullWhenInfoTooShort() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, new byte[]{0x01, 0x02, 0x03}, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.GET_REQUEST, null, List.of(), false, null, List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("invokeId"));
    }

    @Test
    void extract_invokeId_fromSetRequest() {
        // invokeIdByte at info[4]: (invokeIdByte >> 2) & 0x3F = 5 → invokeIdByte = 0x14
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1,
                new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00, (byte) 0xC1, 0x14}, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.SET_REQUEST, null, List.of(), false, "05", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals("5", fields.get("invokeId"));
    }

    @Test
    void extract_invokeId_fromActionRequest() {
        // invokeIdByte at info[4]: (invokeIdByte >> 2) & 0x3F = 10 → invokeIdByte = 0x28
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1,
                new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00, (byte) 0xC3, 0x28}, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.ACTION_REQUEST, null, List.of(), false, "0A", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals("10", fields.get("invokeId"));
    }

    @Test
    void extract_invokeId_nullForAarq() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.AARQ, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("invokeId"));
    }

    @Test
    void extract_invokeId_nullWhenInfoIsNull() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.SET_REQUEST, null, List.of(), false, "05", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNull(fields.get("invokeId"));
    }

    @Test
    void extract_fillsAllFields() {
        HdlcFrame frame = new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
        AxdrStructure aarqBody = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x10),
                new AxdrUint8((byte) 0x00),
                new AxdrUint8((byte) 0x00),
                new AxdrUint16(2000),
                new AxdrUint8((byte) 0x02)
        ));
        AxdrStructure securityHeader = new AxdrStructure(List.of(
                new AxdrUint8((byte) 0x30),
                new AxdrUint32(100L),
                new AxdrUint32(500L)
        ));
        DecodeResult result = new DecodeResult(frame, ApduType.AARQ, aarqBody, List.of(), false, "00", List.of());
        // Use security header for frameCounter/lastIc extraction
        result = new DecodeResult(frame, ApduType.AARQ, securityHeader, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertEquals(100L, fields.get("frameCounter"));
        assertEquals("00000064", fields.get("frameCounterHex"));
        assertEquals("500", fields.get("lastIc"));
        assertEquals("ASSOCIATING", fields.get("associationState"));
    }

    @Test
    void extract_handlesNullAxdrTreeGracefully() {
        HdlcFrame frame = new HdlcFrame(FrameType.S_FRAME, null, null, 1, 1, null, true, null);
        DecodeResult result = new DecodeResult(frame, ApduType.GET_REQUEST, null, List.of(), false, "00", List.of());

        Map<String, Object> fields = StmFieldExtractor.extract(result);

        assertNotNull(fields);
        assertNull(fields.get("frameCounter"));
        assertNull(fields.get("securitySuite"));
        assertNull(fields.get("maxPduSize"));
        assertNull(fields.get("associationState"));
    }

    @Test
    void extractIntValue_withAxdrUint8() {
        assertEquals(5, StmFieldExtractor.extractIntValue(new AxdrUint8((byte) 5)));
    }

    @Test
    void extractIntValue_withAxdrUint16() {
        assertEquals(2000, StmFieldExtractor.extractIntValue(new AxdrUint16(2000)));
    }

    @Test
    void extractIntValue_withAxdrUint32() {
        // extractIntValue returns Integer for values within int range
        Integer value = StmFieldExtractor.extractIntValue(new AxdrUint32(100000L));
        assertEquals(100000, value);
    }

    @Test
    void extractIntValue_withAxdrInt8() {
        assertEquals(-5, StmFieldExtractor.extractIntValue(new AxdrInt8((byte) -5)));
    }

    @Test
    void extractIntValue_withAxdrInt16() {
        assertEquals(-1000, StmFieldExtractor.extractIntValue(new AxdrInt16((short) -1000)));
    }

    @Test
    void extractIntValue_withUnsupportedType_returnsNull() {
        assertNull(StmFieldExtractor.extractIntValue(new AxdrOctetString(0x09, new byte[]{0x01})));
    }

    @Test
    void extractIntValue_withNull_returnsNull() {
        assertNull(StmFieldExtractor.extractIntValue(null));
    }
}
