package com.company.dlms.domain.decoder;

public record AxdrNull(int tag) implements AxdrValue {
    public static final int TAG = 0x00;

    public AxdrNull() {
        this(TAG);
    }
}

