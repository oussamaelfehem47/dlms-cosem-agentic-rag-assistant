package com.company.dlms.domain.orchestration;

/**
 * Internal orchestration boundary between deterministic execution and bounded agentic planning.
 */
public enum OrchestrationMode {
    DETERMINISTIC_FAST_PATH,
    STRUCTURED_PLUS_AGENTIC,
    NATURAL_LANGUAGE_AGENTIC,
    AMBIGUOUS_SAFE_FALLBACK
}
