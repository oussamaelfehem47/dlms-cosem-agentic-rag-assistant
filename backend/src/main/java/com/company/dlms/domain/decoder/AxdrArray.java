package com.company.dlms.domain.decoder;

import java.util.List;

public record AxdrArray(int tag, List<AxdrValue> elements) implements AxdrValue {
    public static final int TAG = 0x01;

    public AxdrArray(List<AxdrValue> elements) {
        this(TAG, elements);
    }
}

