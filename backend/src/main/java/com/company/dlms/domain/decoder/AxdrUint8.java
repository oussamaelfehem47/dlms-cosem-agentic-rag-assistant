package com.company.dlms.domain.decoder;

public record AxdrUint8(int tag, short value) implements AxdrValue {
    public static final int TAG = 0x11;

    public AxdrUint8(short value) {
        this(TAG, value);
    }
}

