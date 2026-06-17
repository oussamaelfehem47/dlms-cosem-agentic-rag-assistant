package com.company.dlms.domain.decoder;

public record AxdrInt32(int tag, int value) implements AxdrValue {
    public static final int TAG = 0x05;

    public AxdrInt32(int value) {
        this(TAG, value);
    }
}

