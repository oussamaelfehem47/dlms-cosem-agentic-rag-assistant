package com.company.dlms.domain.decoder;

public record AxdrUtf8String(int tag, String value) implements AxdrValue {
    public static final int TAG = 0x0C;

    public AxdrUtf8String(String value) {
        this(TAG, value);
    }
}

