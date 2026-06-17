package com.company.dlms.domain.siconia;

import com.company.dlms.domain.InputClass;

import java.io.Serializable;
import java.util.List;

public record SiconiaProcessingMetadata(
        InputClass normalizedInputClass,
        ParseProvenance provenance,
        List<String> warnings,
        String extractorNote
) implements Serializable {
    public SiconiaProcessingMetadata {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
