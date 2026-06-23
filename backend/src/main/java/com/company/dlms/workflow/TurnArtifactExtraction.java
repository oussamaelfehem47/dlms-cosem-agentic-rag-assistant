package com.company.dlms.workflow;

import java.io.Serializable;
import java.util.List;

public record TurnArtifactExtraction(
        List<TurnArtifact> artifacts,
        String turnInstruction,
        boolean explicitArtifacts,
        boolean tooManyArtifacts
) implements Serializable {

    public boolean isBatch() {
        return artifacts != null && artifacts.size() > 1;
    }

    public boolean hasSingleArtifact() {
        return artifacts != null && artifacts.size() == 1;
    }

    public boolean hasTurnInstruction() {
        return turnInstruction != null && !turnInstruction.isBlank();
    }
}
