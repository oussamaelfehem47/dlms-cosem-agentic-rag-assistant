package com.company.dlms.domain.decoder;

public record AxdrBoolean(int tag, boolean value) implements AxdrValue {
    public static final int TAG = 0x03;

    public AxdrBoolean(boolean value) {
        this(TAG, value);
    }
}

