package com.company.dlms.agent.decoder;

public final class LlcExtractor {

    private LlcExtractor() {}

    /**
     * If the information field starts with E6E6xx or E6E7xx LLC bytes, strip 3 bytes.
     * Otherwise return the information field as-is.
     */
    public static byte[] extract(byte[] informationField) {
        if (informationField == null) return new byte[0];
        if (informationField.length < 3) return informationField;

        int b0 = informationField[0] & 0xFF;
        int b1 = informationField[1] & 0xFF;
        if (b0 == 0xE6 && (b1 == 0xE6 || b1 == 0xE7)) {
            byte[] out = new byte[informationField.length - 3];
            System.arraycopy(informationField, 3, out, 0, out.length);
            return out;
        }
        return informationField;
    }
}

