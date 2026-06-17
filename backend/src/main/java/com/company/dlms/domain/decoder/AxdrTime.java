package com.company.dlms.domain.decoder;

public record AxdrTime(
        int tag,
        byte hour,
        byte min,
        byte sec,
        byte hundredths
) implements AxdrValue {
    public static final int TAG = 0x1B;

    public AxdrTime(byte hour, byte min, byte sec, byte hundredths) {
        this(TAG, hour, min, sec, hundredths);
    }
}

