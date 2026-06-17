package com.company.dlms.domain.reflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReflectionStatsResponse(
    double mcpFailureRate,
    Map<String, Double> emptyRetrievalRate,
    double filterTriggerRate,
    double parseErrorRate,
    Map<String, Long> intentDistribution,
    Map<String, Long> avgResponseTimeMs,
    long totalExecutions,
    List<String> warnings,
    Instant lastUpdated,
    Map<String, PromptAdaptation> activeAdaptations,
    long feedbackDatasetSize,
    long dislikedResponseCount
) {}
