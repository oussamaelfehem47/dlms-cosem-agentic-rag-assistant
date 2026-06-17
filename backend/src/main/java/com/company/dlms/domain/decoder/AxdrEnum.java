package com.company.dlms.domain.decoder;

public record AxdrEnum(int tag, int value) implements AxdrValue {
    public static final int TAG = 0x16;

    public AxdrEnum(int value) {
        this(TAG, value);
    }
}

