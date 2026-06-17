package com.company.dlms.domain.decoder;

import java.util.Optional;

public enum ApduType {
    GET_REQUEST(0xC0),
    GET_RESPONSE(0xC4),
    SET_REQUEST(0xC1),
    SET_RESPONSE(0xC5),
    ACTION_REQUEST(0xC3),
    ACTION_RESPONSE(0xC7),
    // AARQ has two common encodings:
    // 0x60 = standard ACSE AARQ-apdu tag (ISO/IEC 9506)
    // 0x30 = BER-TLV SEQUENCE wrapping the AARQ (used by some implementations)
    AARQ(0x60),
    AARQ_BER(0x30),
    AARE(0x61),
    RLRQ(0x62),
    RLRE(0x63),
    GBT(0xE6),
    DATA_NOTIFICATION(0x01),
    // Ciphered APDUs
    GENERAL_GLO_CIPHERING(0xDB),
    GENERAL_DED_CIPHERING(0xDC),
    GLO_GET_REQUEST(0xC8),
    GLO_GET_RESPONSE(0xCC),
    GLO_SET_REQUEST(0xC9),
    GLO_SET_RESPONSE(0xCD),
    GLO_ACTION_REQUEST(0xCB),
    GLO_ACTION_RESPONSE(0xCF),
    UNKNOWN(-1);

    private final int tag;

    ApduType(int tag) {
        this.tag = tag;
    }

    public int tag() {
        return tag;
    }

    public static ApduType fromFirstByte(byte firstByte) {
        int b = firstByte & 0xFF;
        for (ApduType t : values()) {
            if (t.tag != -1 && t.tag == b) {
                return t;
            }
        }
        return UNKNOWN;
    }

    public static Optional<ApduType> fromTagInt(int tag) {
        for (ApduType t : values()) {
            if (t.tag == tag) return Optional.of(t);
        }
        return Optional.empty();
    }
}
