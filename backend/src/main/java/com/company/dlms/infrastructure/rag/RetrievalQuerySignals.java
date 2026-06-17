package com.company.dlms.infrastructure.rag;

import java.util.Set;

final class RetrievalQuerySignals {

    private static final Set<String> OPERATIONS_SIGNALS = Set.of(
            "siconia",
            "ansible",
            "helm",
            "dcu",
            "hes",
            "firmware",
            "campaign",
            "calendar",
            "alarm",
            "operations",
            "local operations",
            "collect task",
            "retele",
            "clock sync",
            "clock synchronization",
            "meter activation",
            "parameterization"
    );

    private static final Set<String> STANDARDS_SIGNALS = Set.of(
            "green book",
            "blue book",
            "dlms",
            "cosem",
            "iec 62056",
            "hdlc",
            "obis",
            "aarq",
            "aare",
            "apdu",
            "frame structure"
    );

    private RetrievalQuerySignals() {
    }

    static boolean containsOpsSignal(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return OPERATIONS_SIGNALS.stream().anyMatch(normalizedQuery::contains);
    }

    static boolean containsStandardsSignal(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return STANDARDS_SIGNALS.stream().anyMatch(normalizedQuery::contains);
    }
}
