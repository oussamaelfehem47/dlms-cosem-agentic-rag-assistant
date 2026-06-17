package com.company.dlms.domain.decoder;

public record AxdrDate(
        int tag,
        int year,
        byte month,
        byte dom,
        byte dow
) implements AxdrValue {
    public static final int TAG = 0x1A;

    public AxdrDate(int year, byte month, byte dom, byte dow) {
        this(TAG, year, month, dom, dow);
    }
}

