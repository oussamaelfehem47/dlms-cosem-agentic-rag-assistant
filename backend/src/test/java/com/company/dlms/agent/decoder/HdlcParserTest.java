package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.HdlcParseException;
import com.company.dlms.domain.decoder.SFrameType;
import com.company.dlms.domain.decoder.UFrameType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class HdlcParserTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void knownGoodFrame_fcsValid_true() {
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},         // type-A
                encodeAddress(0x02),                   // server
                encodeAddress(0x01),                   // client
                (byte) 0x00,                           // I-frame
                new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00, (byte) 0xC4, 0x00} // LLC + GET_RESPONSE tag + 0x00 AXDR null
        );

        HdlcFrame parsed = HdlcParser.parse(frame);
        assertTrue(parsed.fcsValid());
        assertEquals(FrameType.I_FRAME, parsed.frameType());
        assertEquals(1, parsed.clientSap());
        assertEquals(2, parsed.serverSap());
        assertNotNull(parsed.informationField());
    }

    @Test
    void sameFrameWithCorruptedFcs_fcsValid_false() {
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(0x02),
                encodeAddress(0x01),
                (byte) 0x00,
                new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00, (byte) 0xC4, 0x00}
        );
        frame[frame.length - 3] ^= 0x01; // flip 1 bit in FCS

        HdlcFrame parsed = HdlcParser.parse(frame);
        assertFalse(parsed.fcsValid());
    }

    @Test
    void typeAFormatDetection_parses() {
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(0x01),
                encodeAddress(0x01),
                (byte) 0x00,
                new byte[]{}
        );
        assertDoesNotThrow(() -> HdlcParser.parse(frame));
    }

    @Test
    void typeBFormatDetection_parses() {
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA8, 0x00, 0x0A}, // bit 0x08 set => type-B (extended), 3 bytes
                encodeAddress(0x01),
                encodeAddress(0x01),
                (byte) 0x00,
                new byte[]{}
        );
        assertDoesNotThrow(() -> HdlcParser.parse(frame));
    }

    @Test
    void sFrameSubtypes_rr_rnr_rej() {
        assertEquals(SFrameType.RR, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x01, new byte[]{})).sFrameType());
        assertEquals(SFrameType.RNR, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x05, new byte[]{})).sFrameType());
        assertEquals(SFrameType.REJ, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x09, new byte[]{})).sFrameType());
    }

    @Test
    void uFrameSubtypes_snrm_ua_dm_disc() {
        assertEquals(UFrameType.SNRM, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x83, new byte[]{})).uFrameType());
        assertEquals(UFrameType.UA, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x63, new byte[]{})).uFrameType());
        assertEquals(UFrameType.DM, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x0F, new byte[]{})).uFrameType());
        assertEquals(UFrameType.DISC, HdlcParser.parse(buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x43, new byte[]{})).uFrameType());
    }

    @Test
    void uFrameControlByte_classifiedAsUFrame() {
        // Per HDLC spec: (control & 0x03) == 0x03 => U_FRAME
        assertEquals(FrameType.U_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x83, new byte[]{})).frameType());
        assertEquals(FrameType.U_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x63, new byte[]{})).frameType());
        assertEquals(FrameType.U_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x43, new byte[]{})).frameType());
        assertEquals(FrameType.U_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x0F, new byte[]{})).frameType());
    }

    @Test
    void sFrameControlByte_classifiedAsSFrame() {
        // Per HDLC spec: (control & 0x03) == 0x01 => S_FRAME
        assertEquals(FrameType.S_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x01, new byte[]{})).frameType());
        assertEquals(FrameType.S_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x05, new byte[]{})).frameType());
        assertEquals(FrameType.S_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x09, new byte[]{})).frameType());
    }

    @Test
    void iFrameControlByte_classifiedAsIFrame() {
        // Per HDLC spec: (control & 0x01) == 0x00 => I_FRAME
        assertEquals(FrameType.I_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x00, new byte[]{})).frameType());
        assertEquals(FrameType.I_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x10, new byte[]{})).frameType());
        assertEquals(FrameType.I_FRAME, HdlcParser.parse(
                buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x20, new byte[]{})).frameType());
    }

    @Test
    void multiByteServerAddressDecoding() {
        int serverAddress = 0x123;
        byte[] frame = buildFrame(
                new byte[]{(byte) 0xA0, 0x0A},
                encodeAddress(serverAddress),
                encodeAddress(0x01),
                (byte) 0x00,
                new byte[]{}
        );
        HdlcFrame parsed = HdlcParser.parse(frame);
        assertEquals(serverAddress, parsed.serverSap());
    }

    @Test
    void throwsOnTooShort() {
        assertThrows(HdlcParseException.class, () -> HdlcParser.parse(new byte[]{0x7E, 0x7E}));
    }

    @Test
    void throwsOnMissingOpenFlag() {
        byte[] frame = buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x00, new byte[]{});
        frame[0] = 0x00;
        assertThrows(HdlcParseException.class, () -> HdlcParser.parse(frame));
    }

    @Test
    void throwsOnMissingCloseFlag() {
        byte[] frame = buildFrame(new byte[]{(byte) 0xA0, 0x0A}, encodeAddress(1), encodeAddress(1), (byte) 0x00, new byte[]{});
        frame[frame.length - 1] = 0x00;
        assertThrows(HdlcParseException.class, () -> HdlcParser.parse(frame));
    }

    @Test
    void exactUaFixture_decodesAsUFrameUa() {
        byte[] frame = HEX.parseHex("7E0806030363C0AA7E");

        HdlcFrame parsed = HdlcParser.parse(frame);

        assertEquals(FrameType.U_FRAME, parsed.frameType());
        assertEquals(UFrameType.UA, parsed.uFrameType());
        assertFalse(parsed.fcsValid());
    }

    @Test
    void supervisoryFixtureWithPayload_isRejectedAsMalformed() {
        byte[] frame = HEX.parseHex("7EA01903010110E6E600C4020109060100010800FF90007E");

        HdlcParseException ex = assertThrows(HdlcParseException.class, () -> HdlcParser.parse(frame));

        assertEquals(HdlcParser.ERR_UNEXPECTED_INFO_ON_SUPERVISORY_FRAME, ex.getMessage());
    }

    @Test
    void lenientOuterParse_salvagesMalformedSupervisoryFrameHeader() {
        byte[] frame = HEX.parseHex("7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E");

        HdlcFrame parsed = HdlcParser.parseLenientOuterFrame(frame);

        assertEquals(FrameType.S_FRAME, parsed.frameType());
        assertEquals(SFrameType.RR, parsed.sFrameType());
        assertFalse(parsed.fcsValid());
        assertNotNull(parsed.informationField());
    }

    private static byte[] encodeAddress(int address) {
        // 7-bit chunks, LSB=1 marks last byte
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
        return Arrays.copyOf(tmp, n);
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
        byte f0 = (byte) (fcs & 0xFF);
        byte f1 = (byte) ((fcs >>> 8) & 0xFF);

        byte[] out = new byte[1 + content.length + 2 + 1];
        out[0] = 0x7E;
        System.arraycopy(content, 0, out, 1, content.length);
        out[1 + content.length] = f0;
        out[1 + content.length + 1] = f1;
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
