package com.company.dlms.domain.answer;

/**
 * Describes how the final answer should be shaped for the user.
 * <p>
 * Routing/tool selection still lives in {@code StrategyKey}; this enum only captures the
 * answer contract that the UI and explanation layer should honor.
 */
public enum AnswerMode {
    CASUAL_HELP,
    AMBIGUOUS,
    SESSION_RECALL,
    DETERMINISTIC_DECODE,
    DETERMINISTIC_SICONIA,
    RETRIEVAL_DOCS,
    RETRIEVAL_SECURITY,
    FAILURE
}
