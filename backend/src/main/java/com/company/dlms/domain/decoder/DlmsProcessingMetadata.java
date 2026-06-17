package com.company.dlms.domain.decoder;

import com.company.dlms.domain.siconia.ParseProvenance;

import java.io.Serializable;
import java.util.List;

public record DlmsProcessingMetadata(
        DlmsNormalizedKind normalizedKind,
        ParseProvenance provenance,
        List<String> warnings,
        String extractorNote
) implements Serializable {
    public DlmsProcessingMetadata {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
