package com.company.dlms.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a single protocol anomaly event.
 */
public record AnomalyEvent(
        String ruleId,
        String message,
        AnomalySeverity severity,
        Long frameCounter,
        Map<String, Object> details
) implements java.io.Serializable {
    public AnomalyEvent {
        Objects.requireNonNull(ruleId, "ruleId is required");
        Objects.requireNonNull(message, "message is required");
        Objects.requireNonNull(severity, "severity is required");
    }

    public static AnomalyEvent of(String ruleId, AnomalySeverity severity, String message, Long frameCounter, Map<String, Object> details) {
        return new AnomalyEvent(ruleId, message, severity, frameCounter, details != null ? Map.copyOf(details) : Map.of());
    }

    /**
     * Formats the anomaly as a human-readable string for LLM prompt injection.
     */
    public String formatted() {
        return String.format("[%s] %s: %s", ruleId, severity, message);
    }
}
