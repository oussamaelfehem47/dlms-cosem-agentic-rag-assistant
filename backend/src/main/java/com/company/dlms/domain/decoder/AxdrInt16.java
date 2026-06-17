package com.company.dlms.domain.decoder;

public record AxdrInt16(int tag, short value) implements AxdrValue {
    public static final int TAG = 0x10;

    public AxdrInt16(short value) {
        this(TAG, value);
    }
}

