package com.company.dlms.domain.answer;

import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.orchestration.StrategyCandidate;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.workflow.StmSnapshot;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Canonical answer contract shared between orchestration, explanation, and UI rendering.
 * <p>
 * {@link AnswerMode} describes how the answer should be presented, while
 * {@link StrategyKey} records the selected routing/tool strategy that produced the facts.
 */
public record GroundedAnswerContext(
        AnswerMode mode,
        StrategyKey selectedStrategy,
        List<StrategyCandidate> ambiguityCandidates,
        DecodeResult decodeResult,
        SiconiaResult siconiaResult,
        List<RetrievalResult> retrievalResults,
        StmSnapshot stmSnapshot,
        List<SessionEvent> narrativeContext,
        List<String> warnings,
        List<String> anomalies,
        double confidence,
        boolean tentative
) implements Serializable {
    public GroundedAnswerContext {
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(selectedStrategy, "selectedStrategy is required");
        ambiguityCandidates = ambiguityCandidates == null ? List.of() : List.copyOf(ambiguityCandidates);
        retrievalResults = retrievalResults == null ? List.of() : List.copyOf(retrievalResults);
        narrativeContext = narrativeContext == null ? List.of() : List.copyOf(narrativeContext);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);

        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }

        switch (mode) {
            case DETERMINISTIC_DECODE -> {
                requireNonNull(decodeResult, "decodeResult is required for deterministic decode answers");
                requireNull(siconiaResult, "siconiaResult must be null for deterministic decode answers");
            }
            case DETERMINISTIC_SICONIA -> {
                requireNonNull(siconiaResult, "siconiaResult is required for deterministic SICONIA answers");
                requireNull(decodeResult, "decodeResult must be null for deterministic SICONIA answers");
            }
            case RETRIEVAL_DOCS, RETRIEVAL_SECURITY -> {
                // Retrieval-backed conceptual answers may temporarily have zero snippets
                // during staged migration, but they still use the documentation/security
                // answer contract rather than a failure contract.
            }
            case SESSION_RECALL -> {
                if (stmSnapshot == null && narrativeContext.isEmpty()) {
                    throw new IllegalArgumentException(
                            "session recall answers require stmSnapshot or narrativeContext"
                    );
                }
            }
            case AMBIGUOUS -> requireNotEmpty(
                    ambiguityCandidates,
                    "ambiguityCandidates are required for ambiguous answers"
            );
            case CASUAL_HELP, FAILURE -> {
                // no additional invariants
            }
        }
    }

    public static GroundedAnswerContext casualHelp(
            List<String> warnings,
            double confidence
    ) {
        return base(
                AnswerMode.CASUAL_HELP,
                StrategyKey.CASUAL_CHAT,
                warnings,
                List.of(),
                confidence,
                false
        );
    }

    public static GroundedAnswerContext failure(
            StrategyKey selectedStrategy,
            List<String> warnings,
            List<String> anomalies,
            double confidence,
            boolean tentative
    ) {
        return new GroundedAnswerContext(
                AnswerMode.FAILURE,
                Objects.requireNonNull(selectedStrategy, "selectedStrategy is required"),
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                warnings,
                anomalies,
                confidence,
                tentative
        );
    }

    public static GroundedAnswerContext deterministicDecode(
            StrategyKey selectedStrategy,
            DecodeResult decodeResult,
            List<String> warnings,
            List<String> anomalies,
            double confidence,
            boolean tentative
    ) {
        return new GroundedAnswerContext(
                AnswerMode.DETERMINISTIC_DECODE,
                Objects.requireNonNull(selectedStrategy, "selectedStrategy is required"),
                List.of(),
                Objects.requireNonNull(decodeResult, "decodeResult is required"),
                null,
                List.of(),
                null,
                List.of(),
                warnings,
                anomalies,
                confidence,
                tentative
        );
    }

    public static GroundedAnswerContext deterministicSiconia(
            StrategyKey selectedStrategy,
            SiconiaResult siconiaResult,
            List<String> warnings,
            List<String> anomalies,
            double confidence,
            boolean tentative
    ) {
        return new GroundedAnswerContext(
                AnswerMode.DETERMINISTIC_SICONIA,
                Objects.requireNonNull(selectedStrategy, "selectedStrategy is required"),
                List.of(),
                null,
                Objects.requireNonNull(siconiaResult, "siconiaResult is required"),
                List.of(),
                null,
                List.of(),
                warnings,
                anomalies,
                confidence,
                tentative
        );
    }

    public static GroundedAnswerContext retrievalDocs(
            List<RetrievalResult> retrievalResults,
            List<String> warnings,
            double confidence
    ) {
        return retrievalBacked(AnswerMode.RETRIEVAL_DOCS, StrategyKey.DOCUMENTATION, retrievalResults, warnings, confidence);
    }

    public static GroundedAnswerContext retrievalSecurity(
            List<RetrievalResult> retrievalResults,
            List<String> warnings,
            double confidence
    ) {
        return retrievalBacked(AnswerMode.RETRIEVAL_SECURITY, StrategyKey.SECURITY_EXPLAIN, retrievalResults, warnings, confidence);
    }

    public static GroundedAnswerContext sessionRecall(
            StrategyKey selectedStrategy,
            StmSnapshot stmSnapshot,
            List<SessionEvent> narrativeContext,
            List<String> warnings,
            double confidence,
            boolean tentative
    ) {
        return new GroundedAnswerContext(
                AnswerMode.SESSION_RECALL,
                Objects.requireNonNull(selectedStrategy, "selectedStrategy is required"),
                List.of(),
                null,
                null,
                List.of(),
                stmSnapshot,
                narrativeContext,
                warnings,
                List.of(),
                confidence,
                tentative
        );
    }

    public static GroundedAnswerContext ambiguous(
            List<StrategyCandidate> ambiguityCandidates,
            List<String> warnings,
            double confidence
    ) {
        return new GroundedAnswerContext(
                AnswerMode.AMBIGUOUS,
                StrategyKey.UNKNOWN,
                ambiguityCandidates,
                null,
                null,
                List.of(),
                null,
                List.of(),
                warnings,
                List.of(),
                confidence,
                true
        );
    }

    private static GroundedAnswerContext retrievalBacked(
            AnswerMode mode,
            StrategyKey strategyKey,
            List<RetrievalResult> retrievalResults,
            List<String> warnings,
            double confidence
    ) {
        return new GroundedAnswerContext(
                mode,
                strategyKey,
                List.of(),
                null,
                null,
                retrievalResults,
                null,
                List.of(),
                warnings,
                List.of(),
                confidence,
                false
        );
    }

    private static GroundedAnswerContext base(
            AnswerMode mode,
            StrategyKey strategyKey,
            List<String> warnings,
            List<String> anomalies,
            double confidence,
            boolean tentative
    ) {
        return new GroundedAnswerContext(
                mode,
                strategyKey,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                warnings,
                anomalies,
                confidence,
                tentative
        );
    }

    private static void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireNull(Object value, String message) {
        if (value != null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireNotEmpty(List<?> values, String message) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
