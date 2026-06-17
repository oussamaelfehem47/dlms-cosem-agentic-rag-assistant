package com.company.dlms.domain.decoder;

public record AxdrDateTime(
        int tag,
        int year,
        byte month,
        byte dom,
        byte dow,
        byte hour,
        byte min,
        byte sec,
        byte hundredths,
        short deviation,
        byte clockStatus
) implements AxdrValue {
    public static final int TAG = 0x19;

    public AxdrDateTime(
            int year,
            byte month,
            byte dom,
            byte dow,
            byte hour,
            byte min,
            byte sec,
            byte hundredths,
            short deviation,
            byte clockStatus
    ) {
        this(TAG, year, month, dom, dow, hour, min, sec, hundredths, deviation, clockStatus);
    }
}

