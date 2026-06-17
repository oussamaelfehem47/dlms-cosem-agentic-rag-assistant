package com.company.dlms.domain.orchestration;

import java.io.Serializable;
import java.util.List;

public record StrategyMetadata(
        StrategyKey selectedStrategy,
        String selectedLabel,
        double confidence,
        boolean ambiguous,
        boolean tentative,
        List<StrategyCandidate> candidates,
        List<String> warnings
) implements Serializable {
    public StrategyMetadata {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
