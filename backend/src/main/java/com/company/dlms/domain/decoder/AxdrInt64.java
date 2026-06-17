package com.company.dlms.domain.decoder;

public record AxdrInt64(int tag, long value) implements AxdrValue {
    public static final int TAG = 0x14;

    public AxdrInt64(long value) {
        this(TAG, value);
    }
}

