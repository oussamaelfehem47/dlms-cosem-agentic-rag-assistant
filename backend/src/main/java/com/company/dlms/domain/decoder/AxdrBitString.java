package com.company.dlms.domain.decoder;

public record AxdrBitString(int tag, byte[] value, int unusedBits) implements AxdrValue {
    public static final int TAG = 0x04;

    public AxdrBitString(byte[] value, int unusedBits) {
        this(TAG, value, unusedBits);
    }
}

