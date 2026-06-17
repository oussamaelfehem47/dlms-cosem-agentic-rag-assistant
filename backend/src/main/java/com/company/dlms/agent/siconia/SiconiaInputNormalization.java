package com.company.dlms.agent.siconia;

import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.siconia.ParseProvenance;

import java.io.Serializable;
import java.util.List;

public record SiconiaInputNormalization(
        String normalizedInput,
        InputClass inputClass,
        ParseProvenance provenance,
        List<String> warnings,
        String extractorNote
) implements Serializable {
    public SiconiaInputNormalization {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
