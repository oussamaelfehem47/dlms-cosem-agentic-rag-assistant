package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.ApduType;

/**
 * Utility for extracting security headers from ciphered APDUs.
 */
public class CipheredApduExtractor {

    public record SecurityHeader(
            int securityControl,
            long frameCounter,
            int securitySuite,
            boolean authenticated,
            boolean encrypted
    ) {}

    public static SecurityHeader extractSecurityHeader(byte[] apduBytes) {
        if (apduBytes == null || apduBytes.length < 16) return null;

        int tag = apduBytes[0] & 0xFF;
        if (!isCipheredTag(tag)) return null;

        // Skip tag + length + system-title-length + system-title
        int offset = 1; // skip tag
        int contentLength = apduBytes[offset++] & 0xFF;
        if (contentLength > 127) {
            // Long form length
            int numLenBytes = contentLength & 0x7F;
            offset += numLenBytes;
        }
        
        if (offset >= apduBytes.length) return null;
        int sysTitleLen = apduBytes[offset++] & 0xFF; // usually 8
        offset += sysTitleLen; // skip system title bytes

        if (offset >= apduBytes.length) return null;

        int securityControl = apduBytes[offset++] & 0xFF;
        // Extract frame counter (4 bytes, big-endian)
        if (offset + 4 > apduBytes.length) return null;
        long frameCounter = ((long) (apduBytes[offset] & 0xFF) << 24)
                | ((long) (apduBytes[offset + 1] & 0xFF) << 16)
                | ((long) (apduBytes[offset + 2] & 0xFF) << 8)
                | ((long) (apduBytes[offset + 3] & 0xFF));

        boolean authenticated = (securityControl & 0x10) != 0;
        boolean encrypted = (securityControl & 0x20) != 0;
        int securitySuite = securityControl & 0x0F;

        return new SecurityHeader(securityControl, frameCounter,
                securitySuite, authenticated, encrypted);
    }

    public static boolean isCipheredTag(int tag) {
        return tag == 0xDB || tag == 0xDC || tag == 0xC8 || tag == 0xCC 
            || tag == 0xC9 || tag == 0xCD || tag == 0xCB || tag == 0xCF;
    }
    
    public static boolean isCipheredType(ApduType type) {
        if (type == null) return false;
        return isCipheredTag(type.tag());
    }
}
