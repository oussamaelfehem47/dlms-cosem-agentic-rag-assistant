package com.company.dlms.domain.orchestration;

import java.io.Serializable;

public record ToolTraceEntry(
        String toolName,
        String summary,
        boolean authoritative,
        String provenance
) implements Serializable {
    public ToolTraceEntry {
        toolName = toolName == null ? "" : toolName;
        summary = summary == null ? "" : summary;
        provenance = provenance == null ? "" : provenance;
    }
}
