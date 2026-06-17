package com.company.dlms.domain.decoder;

public record AxdrCompactArray(int tag, byte[] rawData) implements AxdrValue {
    public static final int TAG = 0x13;

    public AxdrCompactArray(byte[] rawData) {
        this(TAG, rawData);
    }
}

