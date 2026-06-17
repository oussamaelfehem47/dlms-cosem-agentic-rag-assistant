package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.HdlcParseException;
import com.company.dlms.domain.decoder.SFrameType;
import com.company.dlms.domain.decoder.UFrameType;

import java.util.Arrays;

/**
 * Deterministic DLMS/COSEM HDLC parser.
 *
 * CRC-16/IBM (ARC): poly=0x8005, init=0x0000, reflected in/out, xorout=0xFFFF (per Phase 4 rules).
 *
 * Control field classification per ISO 13239 / ITU-T Q.921:
 * 1. U-frame: (control & 0x03) == 0x03  (bits 1-0 = 11)
 * 2. S-frame: (control & 0x03) == 0x01  (bits 1-0 = 01)
 * 3. I-frame: (control & 0x01) == 0x00  (bit 0 = 0)
 */
public final class HdlcParser {

    private static final int FLAG = 0x7E;
    public static final String ERR_UNEXPECTED_INFO_ON_SUPERVISORY_FRAME =
            "Unexpected information field on supervisory frame";

    /** Bit mask to clear the Poll/Final bit (bit 4) in U-frame control bytes. */
    private static final int PF_BIT_MASK = 0xEF; // 1110 1111

    private HdlcParser() {}

    public static HdlcFrame parse(byte[] frame) {
        return parseInternal(frame, false);
    }

    /**
     * Parses only the outer HDLC fields and tolerates malformed supervisory
     * frames that wrongly carry trailing bytes. This is used to salvage a
     * tentative header-level decode when the strict parser would otherwise
     * reject the frame entirely.
     */
    public static HdlcFrame parseLenientOuterFrame(byte[] frame) {
        return parseInternal(frame, true);
    }

    private static HdlcFrame parseInternal(byte[] frame, boolean allowMalformedSupervisoryPayload) {
        if (frame == null || frame.length < 5) {
            throw new HdlcParseException("Frame too short");
        }
        if ((frame[0] & 0xFF) != FLAG) {
            throw new HdlcParseException("Missing opening 0x7E flag");
        }
        if ((frame[frame.length - 1] & 0xFF) != FLAG) {
            throw new HdlcParseException("Missing closing 0x7E flag");
        }

        // Layout: 7E | ...content... | FCS(2) | 7E
        if (frame.length < 1 + 1 + 2 + 1) {
            throw new HdlcParseException("Frame too short for content+FCS");
        }

        int contentStart = 1;
        int fcsStart = frame.length - 3;
        if (fcsStart <= contentStart) {
            throw new HdlcParseException("Frame too short for FCS");
        }

        byte[] content = Arrays.copyOfRange(frame, contentStart, fcsStart);
        int receivedFcsLe = ((frame[fcsStart] & 0xFF)) | ((frame[fcsStart + 1] & 0xFF) << 8);
        int computedFcs = crc16IbmArc(content);
        boolean fcsValid = (receivedFcsLe == computedFcs);

        int offset = 0;
        // Skip frame format field (type A/B). We do not currently enforce length bits; we just advance.
        // Type-A is 2 bytes; Type-B (extended) is 3 bytes.
        //
        // IMPORTANT: the first byte may legitimately have bit 0x08 set even for
        // non-extended real-world fixtures (for example 0x08 0x06 ...). Treating
        // any 0x08 bit as "type-B" misaligns the rest of the frame and turns a
        // valid UA control field into a "missing control field" error. Only the
        // explicit 0xA8-style extended format marker should select the 3-byte path.
        if (content.length < 2) {
            throw new HdlcParseException("Missing frame format field");
        }
        boolean typeB = (content[0] & 0xF8) == 0xA8;
        int formatLen = typeB ? 3 : 2;
        if (content.length < formatLen) {
            throw new HdlcParseException("Truncated frame format field");
        }
        offset += formatLen;

        AddressParse dest = parseAddress(content, offset);
        offset = dest.nextOffset();
        AddressParse src = parseAddress(content, offset);
        offset = src.nextOffset();

        if (offset >= content.length) {
            throw new HdlcParseException("Missing control field");
        }
        int control = content[offset] & 0xFF;
        offset += 1;

        FrameType frameType = classifyFrameType(control);
        SFrameType sFrameType = null;
        UFrameType uFrameType = null;

        if (frameType == FrameType.S_FRAME) {
            sFrameType = classifySFrame(control);
        } else if (frameType == FrameType.U_FRAME) {
            uFrameType = classifyUFrame(control);
        }

        byte[] informationField = offset >= content.length ? null : Arrays.copyOfRange(content, offset, content.length);
        if (informationField != null && informationField.length == 0) {
            informationField = null;
        }
        if (frameType == FrameType.S_FRAME && informationField != null && !allowMalformedSupervisoryPayload) {
            throw new HdlcParseException(ERR_UNEXPECTED_INFO_ON_SUPERVISORY_FRAME);
        }

        return new HdlcFrame(
                frameType,
                uFrameType,
                sFrameType,
                src.address(),   // client SAP (source)
                dest.address(),  // server SAP (destination)
                informationField,
                fcsValid,
                Arrays.copyOf(frame, frame.length)
        );
    }

    /**
     * Classifies the HDLC control byte per ISO 13239 priority order:
     * 1. U-frame: (control & 0x03) == 0x03  (bits 1-0 = 11)
     * 2. S-frame: (control & 0x03) == 0x01  (bits 1-0 = 01)
     * 3. I-frame: (control & 0x01) == 0x00  (bit 0 = 0)
     */
    private static FrameType classifyFrameType(int control) {
        if ((control & 0x03) == 0x03) return FrameType.U_FRAME;
        if ((control & 0x03) == 0x01) return FrameType.S_FRAME;
        if ((control & 0x01) == 0x00) return FrameType.I_FRAME;
        // Fallback: treat unrecognized control as U_FRAME (permissive decoding)
        return FrameType.U_FRAME;
    }

    private static SFrameType classifySFrame(int control) {
        int code = (control >> 2) & 0x03;
        return switch (code) {
            case 0 -> SFrameType.RR;
            case 1 -> SFrameType.RNR;
            case 2 -> SFrameType.REJ;
            default -> SFrameType.RR;
        };
    }

    /**
     * Classifies the U-frame subtype from the control byte.
     * The Poll/Final bit (bit 4 = 0x10) is masked out before matching,
     * so variants like 0x93 (SNRM with P/F bit set) are correctly
     * recognized as SNRM alongside the standard 0x83.
     *
     * Common DLMS U-frame control values (base):
     * SNRM=0x83, UA=0x63, DM=0x0F, DISC=0x43
     */
    private static UFrameType classifyUFrame(int control) {
        // Mask out the P/F bit (bit 4 = 0x10) so that e.g. 0x93 → 0x83 (SNRM)
        int masked = control & PF_BIT_MASK;
        return switch (masked) {
            case 0x83 -> UFrameType.SNRM;
            case 0x63 -> UFrameType.UA;
            case 0x0F -> UFrameType.DM;
            case 0x43 -> UFrameType.DISC;
            default -> UFrameType.UNKNOWN;
        };
    }

    private record AddressParse(int address, int nextOffset) {}

    private static AddressParse parseAddress(byte[] content, int offset) {
        int value = 0;
        int shift = 0;
        int i = offset;
        while (i < content.length) {
            int b = content[i] & 0xFF;
            int payload = (b >> 1) & 0x7F;
            value |= (payload << shift);
            shift += 7;
            i++;
            if ((b & 0x01) == 1) {
                return new AddressParse(value, i);
            }
        }
        throw new HdlcParseException("Truncated HDLC address field");
    }

    static int crc16IbmArc(byte[] data) {
        int crc = 0x0000; // init=0x0000
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001; // reflected poly 0x8005
                } else {
                    crc = (crc >>> 1);
                }
                crc &= 0xFFFF;
            }
        }
        crc ^= 0xFFFF; // xorout=0xFFFF (per Phase 4 rules)
        return crc & 0xFFFF;
    }
}
