package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.ApduType;

public final class ApduClassifier {

    private ApduClassifier() {}

    public static ApduType classify(byte[] apdu) {
        if (apdu == null || apdu.length == 0) {
            return ApduType.UNKNOWN;
        }
        int tag = apdu[0] & 0xFF;
        // Check for specific global/dedicated ciphering tags
        return ApduType.fromFirstByte(apdu[0]);
    }
}

