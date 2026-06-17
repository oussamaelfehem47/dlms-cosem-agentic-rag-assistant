package com.company.dlms.domain.decoder;

public record AxdrOctetString(int tag, byte[] value) implements AxdrValue {
    public static final int TAG = 0x09;

    public AxdrOctetString(byte[] value) {
        this(TAG, value);
    }
}

