package com.company.dlms.domain.decoder;

import java.math.BigInteger;

public record AxdrUint64(int tag, BigInteger value) implements AxdrValue {
    public static final int TAG = 0x15;

    public AxdrUint64(BigInteger value) {
        this(TAG, value);
    }
}

