package com.company.dlms.agent.dlms;

import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.siconia.ParseProvenance;

import java.io.Serializable;
import java.util.List;

public record DlmsInputNormalization(
        String normalizedInput,
        DlmsNormalizedKind kind,
        ParseProvenance provenance,
        List<String> warnings,
        String extractorNote,
        boolean ambiguous
) implements Serializable {
    public DlmsInputNormalization {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
