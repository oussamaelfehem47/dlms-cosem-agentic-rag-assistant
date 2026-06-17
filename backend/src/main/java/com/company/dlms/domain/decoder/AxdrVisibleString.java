package com.company.dlms.domain.decoder;

public record AxdrVisibleString(int tag, String value) implements AxdrValue {
    public static final int TAG = 0x0A;

    public AxdrVisibleString(String value) {
        this(TAG, value);
    }
}

