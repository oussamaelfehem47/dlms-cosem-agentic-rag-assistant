package com.company.dlms.domain.siconia;

import java.io.Serializable;
import java.util.Set;

public record LogAnalysis(
        LogLayer dominantLayer,
        LogSeverity highestSeverity,
        Set<IssueCategory> issueCategories,
        int lineCount,
        int errorLineCount
) implements Serializable {}
