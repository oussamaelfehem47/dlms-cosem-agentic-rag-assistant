package com.company.dlms.domain.orchestration;

import java.io.Serializable;
import java.util.List;

public record StrategyCandidate(
        StrategyKey strategy,
        String label,
        double confidence,
        String rationale,
        boolean deterministic,
        boolean tentative,
        String inputClass,
        String normalizedKind,
        String provenance,
        String normalizedInput,
        List<String> warnings
) implements Serializable {
    public StrategyCandidate {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
