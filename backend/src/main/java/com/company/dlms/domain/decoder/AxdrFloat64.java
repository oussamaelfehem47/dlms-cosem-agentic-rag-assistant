package com.company.dlms.domain.decoder;

public record AxdrFloat64(int tag, double value) implements AxdrValue {
    public static final int TAG = 0x18;

    public AxdrFloat64(double value) {
        this(TAG, value);
    }
}

