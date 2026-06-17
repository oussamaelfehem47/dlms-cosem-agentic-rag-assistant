package com.company.dlms.domain.decoder;

public record AxdrFloat32(int tag, float value) implements AxdrValue {
    public static final int TAG = 0x17;

    public AxdrFloat32(float value) {
        this(TAG, value);
    }
}

