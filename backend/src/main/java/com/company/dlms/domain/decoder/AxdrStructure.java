package com.company.dlms.domain.decoder;

import java.util.List;

public record AxdrStructure(int tag, List<AxdrValue> elements) implements AxdrValue {
    public static final int TAG = 0x02;

    public AxdrStructure(List<AxdrValue> elements) {
        this(TAG, elements);
    }
}

