package com.company.dlms.domain.orchestration;

public enum StrategyKey {
    CASUAL_CHAT("Casual reply"),
    SESSION_RECALL("Session recall"),
    DLMS_FRAME_DECODE("HDLC frame decode"),
    DLMS_APDU_DECODE("APDU decode"),
    DLMS_AXDR_DECODE("AXDR decode"),
    DLMS_OBIS_LOOKUP("OBIS lookup"),
    SICONIA_XML_ANALYSIS("SICONIA XML analysis"),
    SICONIA_ALARM_ANALYSIS("SICONIA alarm analysis"),
    SICONIA_LOG_ANALYSIS("SICONIA log analysis"),
    SECURITY_EXPLAIN("Security explanation"),
    DOCUMENTATION("Documentation answer"),
    UNKNOWN("Unknown");

    private final String label;

    StrategyKey(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
