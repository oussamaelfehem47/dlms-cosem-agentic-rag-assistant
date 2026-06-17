package com.company.dlms.domain.siconia;

public enum LogSeverity {
    ERROR,
    WARN,
    INFO,
    DEBUG;

    public static LogSeverity max(LogSeverity a, LogSeverity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() <= b.ordinal() ? a : b; // ERROR (0) is highest
    }
}

