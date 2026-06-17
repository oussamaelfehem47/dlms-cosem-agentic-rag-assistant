package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AxdrDecoderTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void nullData() {
        AxdrValue v = AxdrDecoder.decode(hex("00"));
        assertInstanceOf(AxdrNull.class, v);
        assertEquals(0x00, v.tag());
    }

    @Test
    void booleanTrue() {
        AxdrBoolean v = (AxdrBoolean) AxdrDecoder.decode(hex("0301"));
        assertTrue(v.value());
    }

    @Test
    void bitString_8bits() {
        AxdrBitString v = (AxdrBitString) AxdrDecoder.decode(hex("0408AA"));
        assertArrayEquals(hex("AA"), v.value());
        assertEquals(0, v.unusedBits());
    }

    @Test
    void int8() {
        AxdrInt8 v = (AxdrInt8) AxdrDecoder.decode(hex("0FFF"));
        assertEquals((byte) -1, v.value());
    }

    @Test
    void int16() {
        AxdrInt16 v = (AxdrInt16) AxdrDecoder.decode(hex("10FFFE"));
        assertEquals((short) -2, v.value());
    }

    @Test
    void int32() {
        AxdrInt32 v = (AxdrInt32) AxdrDecoder.decode(hex("0500003039"));
        assertEquals(12345, v.value());
    }

    @Test
    void int64() {
        AxdrInt64 v = (AxdrInt64) AxdrDecoder.decode(hex("14000000000000002A"));
        assertEquals(42L, v.value());
    }

    @Test
    void uint8() {
        AxdrUint8 v = (AxdrUint8) AxdrDecoder.decode(hex("11FF"));
        assertEquals(255, v.value());
    }

    @Test
    void uint16() {
        AxdrUint16 v = (AxdrUint16) AxdrDecoder.decode(hex("12FFFE"));
        assertEquals(65534, v.value());
    }

    @Test
    void uint32() {
        AxdrUint32 v = (AxdrUint32) AxdrDecoder.decode(hex("06FFFFFFFF"));
        assertEquals(4294967295L, v.value());
    }

    @Test
    void uint64() {
        AxdrUint64 v = (AxdrUint64) AxdrDecoder.decode(hex("15FFFFFFFFFFFFFFFF"));
        assertEquals(new BigInteger("18446744073709551615"), v.value());
    }

    @Test
    void float32() {
        AxdrFloat32 v = (AxdrFloat32) AxdrDecoder.decode(hex("174048F5C3"));
        assertEquals(3.14f, v.value(), 0.0001);
    }

    @Test
    void float64() {
        AxdrFloat64 v = (AxdrFloat64) AxdrDecoder.decode(hex("18400921FB54442D18"));
        assertEquals(3.141592653589793, v.value(), 0.000000000000001);
    }

    @Test
    void octetString() {
        AxdrOctetString v = (AxdrOctetString) AxdrDecoder.decode(hex("09060100010800FF"));
        assertArrayEquals(hex("0100010800FF"), v.value());
    }

    @Test
    void visibleString() {
        AxdrVisibleString v = (AxdrVisibleString) AxdrDecoder.decode(hex("0A0568656C6C6F"));
        assertEquals("hello", v.value());
    }

    @Test
    void utf8String() {
        AxdrUtf8String v = (AxdrUtf8String) AxdrDecoder.decode(hex("0C03E282AC")); // "€"
        assertEquals("€", v.value());
    }

    @Test
    void enumValue() {
        AxdrEnum v = (AxdrEnum) AxdrDecoder.decode(hex("1603"));
        assertEquals(3, v.value());
    }

    @Test
    void arrayOfTwo() {
        AxdrArray arr = (AxdrArray) AxdrDecoder.decode(hex("0102030111FF"));
        assertEquals(2, arr.elements().size());
        assertInstanceOf(AxdrBoolean.class, arr.elements().get(0));
        assertInstanceOf(AxdrUint8.class, arr.elements().get(1));
    }

    @Test
    void structureOfTwo() {
        AxdrStructure st = (AxdrStructure) AxdrDecoder.decode(hex("0202050000002A0300"));
        assertEquals(2, st.elements().size());
        assertEquals(42, ((AxdrInt32) st.elements().get(0)).value());
        assertFalse(((AxdrBoolean) st.elements().get(1)).value());
    }

    @Test
    void compactArray_rawDataOnly() {
        AxdrCompactArray v = (AxdrCompactArray) AxdrDecoder.decode(hex("1303A1B2C3"));
        assertArrayEquals(hex("A1B2C3"), v.rawData());
    }

    @Test
    void dateTime() {
        AxdrDateTime v = (AxdrDateTime) AxdrDecoder.decode(hex("1907E80416010E1E0000003C00"));
        assertEquals(2024, v.year());
        assertEquals((byte) 4, v.month());
        assertEquals((byte) 0x16, v.dom());
        assertEquals((byte) 1, v.dow());
        assertEquals((byte) 0x0E, v.hour());
        assertEquals((byte) 0x1E, v.min());
        assertEquals((byte) 0, v.sec());
        assertEquals((short) 60, v.deviation());
    }

    @Test
    void date() {
        AxdrDate v = (AxdrDate) AxdrDecoder.decode(hex("1A07E8041601"));
        assertEquals(2024, v.year());
        assertEquals((byte) 4, v.month());
        assertEquals((byte) 0x16, v.dom());
        assertEquals((byte) 1, v.dow());
    }

    @Test
    void time() {
        AxdrTime v = (AxdrTime) AxdrDecoder.decode(hex("1B0E1E0000"));
        assertEquals((byte) 0x0E, v.hour());
        assertEquals((byte) 0x1E, v.min());
    }

    @Test
    void unknownTag_returnsOctetStringOfRemainingBytes() {
        AxdrValue v = AxdrDecoder.decode(hex("99010203"));
        assertInstanceOf(AxdrOctetString.class, v);
        assertArrayEquals(hex("010203"), ((AxdrOctetString) v).value());
    }

    @Test
    void nestedArrayOfStructures() {
        // array[2] of structure[2] (octet-string(6) + int32)
        // element1: { octet(0100010800FF), int32(1) }
        // element2: { octet(0100010800FF), int32(2) }
        String hex =
                "01" + "02" +
                "02" + "02" + "09" + "06" + "0100010800FF" + "05" + "00000001" +
                "02" + "02" + "09" + "06" + "0100010800FF" + "05" + "00000002";
        AxdrArray arr = (AxdrArray) AxdrDecoder.decode(hex(hex));
        assertEquals(2, arr.elements().size());
        assertInstanceOf(AxdrStructure.class, arr.elements().get(0));
    }

    @Test
    void truncatedValue_throwsAxdrDecodeException() {
        assertThrows(AxdrDecodeException.class, () -> AxdrDecoder.decode(hex("05FFFF"))); // int32 needs 4 bytes
    }

    private static byte[] hex(String s) {
        return HEX.parseHex(s);
    }
}

