package com.company.dlms.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dlms.memory")
public record DlmsMemoryProperties(
        String stmTable,
        String narrativeTable,
        int narrativeLimit
) {
    public DlmsMemoryProperties {
        if (stmTable == null || stmTable.isBlank()) {
            stmTable = "stm_entries";
        }
        if (narrativeTable == null || narrativeTable.isBlank()) {
            narrativeTable = "episodic_blocks";
        }
        if (narrativeLimit <= 0) {
            narrativeLimit = 10;
        }
    }
}

