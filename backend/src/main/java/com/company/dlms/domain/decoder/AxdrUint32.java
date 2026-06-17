package com.company.dlms.domain.decoder;

public record AxdrUint32(int tag, long value) implements AxdrValue {
    public static final int TAG = 0x06;

    public AxdrUint32(long value) {
        this(TAG, value);
    }
}

