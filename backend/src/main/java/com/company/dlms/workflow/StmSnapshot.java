package com.company.dlms.workflow;

import java.util.Optional;

/**
 * Captured snapshot of Short-Term Memory (STM) fields from a previous decode.
 * Used for anomaly detection rules that require comparison between current and prior state.
 */
public record StmSnapshot(
        Long frameCounter,
        Integer securitySuite,
        String associationState,
        Integer maxPduSize
) implements java.io.Serializable {
    /**
     * Creates a snapshot from the current WorkflowState fields.
     */
    public static StmSnapshot from(WorkflowState state) {
        return new StmSnapshot(
                parseLong(state.frameCounter()),
                parseInt(state.securitySuite()),
                state.associationState(),
                parseInt(state.maxPduSize())
        );
    }

    private static Long parseLong(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
