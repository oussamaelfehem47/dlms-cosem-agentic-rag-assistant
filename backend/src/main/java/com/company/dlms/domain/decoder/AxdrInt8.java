package com.company.dlms.domain.decoder;

public record AxdrInt8(int tag, byte value) implements AxdrValue {
    public static final int TAG = 0x0F;

    public AxdrInt8(byte value) {
        this(TAG, value);
    }
}

