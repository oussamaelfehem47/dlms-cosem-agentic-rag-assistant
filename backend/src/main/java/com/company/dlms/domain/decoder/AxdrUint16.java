package com.company.dlms.domain.decoder;

public record AxdrUint16(int tag, int value) implements AxdrValue {
    public static final int TAG = 0x12;

    public AxdrUint16(int value) {
        this(TAG, value);
    }
}

