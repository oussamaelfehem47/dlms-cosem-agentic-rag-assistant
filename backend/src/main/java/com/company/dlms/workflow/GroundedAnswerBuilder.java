package com.company.dlms.workflow;

import com.company.dlms.domain.CasualQueryClassifier;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.answer.GroundedAnswerContext;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class GroundedAnswerBuilder {

    private final FollowUpResolver followUpResolver;

    public GroundedAnswerBuilder(FollowUpResolver followUpResolver) {
        this.followUpResolver = followUpResolver;
    }

    public GroundedAnswerContext build(WorkflowState state) {
        Objects.requireNonNull(state, "state is required");

        if (CasualQueryClassifier.isCasualNonTechnicalQuery(state.rawInput())
                || CasualQueryClassifier.isAssistantCapabilityQuestion(state.rawInput())) {
            return GroundedAnswerContext.casualHelp(
                    warningsFrom(state),
                    confidenceFrom(state, 0.92)
            );
        }

        if (followUpResolver.hasResolvableSessionContext(state)
                && hasSessionContext(state)) {
            return GroundedAnswerContext.sessionRecall(
                    StrategyKey.SESSION_RECALL,
                    state.stmSnapshot(),
                    state.narrativeContext(),
                    warningsFrom(state),
                    confidenceFrom(state, 0.84),
                    false
            );
        }

        if (requiresDisambiguation(state.strategyMetadata())) {
            return GroundedAnswerContext.ambiguous(
                    state.strategyMetadata().candidates(),
                    warningsFrom(state),
                    confidenceFrom(state, 0.5)
            );
        }

        if (state.decodeResult() instanceof DecodeResult decodeResult) {
            return GroundedAnswerContext.deterministicDecode(
                    strategyForDecode(state, decodeResult),
                    decodeResult,
                    warningsFrom(state),
                    anomaliesFrom(state),
                    confidenceFrom(state, confidenceForDecode(decodeResult)),
                    isTentativeDecode(state, decodeResult)
            );
        }

        if (state.siconiaResult() != null) {
            return GroundedAnswerContext.deterministicSiconia(
                    strategyOrFallback(state.strategyMetadata(), inferSiconiaStrategy(state)),
                    state.siconiaResult(),
                    warningsFrom(state),
                    anomaliesFrom(state),
                    confidenceFrom(state, 0.9),
                    isTentativeFromMetadata(state.strategyMetadata())
            );
        }

        if (state.intent() == DlmsIntent.SECURITY_EXPLAIN) {
            return GroundedAnswerContext.retrievalSecurity(
                    state.retrievalResults(),
                    warningsFrom(state),
                    confidenceFrom(state, 0.74)
            );
        }

        if (state.intent() == DlmsIntent.DOCUMENTATION) {
            return GroundedAnswerContext.retrievalDocs(
                    state.retrievalResults(),
                    warningsFrom(state),
                    confidenceFrom(state, 0.68)
            );
        }

        if (hasRetrievalResults(state)) {
            return GroundedAnswerContext.retrievalDocs(
                    state.retrievalResults(),
                    warningsFrom(state),
                    confidenceFrom(state, 0.68)
            );
        }

        return GroundedAnswerContext.failure(
                strategyOrFallback(state.strategyMetadata(), StrategyKey.UNKNOWN),
                warningsFrom(state),
                anomaliesFrom(state),
                confidenceFrom(state, 0.4),
                true
        );
    }

    private boolean hasSessionContext(WorkflowState state) {
        return state.stmSnapshot() != null
                || (state.narrativeContext() != null && !state.narrativeContext().isEmpty());
    }

    private boolean hasRetrievalResults(WorkflowState state) {
        return state.retrievalResults() != null && !state.retrievalResults().isEmpty();
    }

    private boolean requiresDisambiguation(StrategyMetadata metadata) {
        return metadata != null && metadata.ambiguous();
    }

    private StrategyKey strategyForDecode(WorkflowState state, DecodeResult decodeResult) {
        StrategyKey selected = strategyOrFallback(state.strategyMetadata(), null);
        if (selected != null && selected != StrategyKey.UNKNOWN) {
            return selected;
        }
        DlmsNormalizedKind kind = decodeResult.processingMetadata() != null
                ? decodeResult.processingMetadata().normalizedKind()
                : null;
        if (kind == null) {
            return StrategyKey.DLMS_FRAME_DECODE;
        }
        return switch (kind) {
            case FRAME_HEX -> StrategyKey.DLMS_FRAME_DECODE;
            case APDU_HEX -> StrategyKey.DLMS_APDU_DECODE;
            case AXDR_HEX -> StrategyKey.DLMS_AXDR_DECODE;
            case OBIS_QUERY -> StrategyKey.DLMS_OBIS_LOOKUP;
        };
    }

    private StrategyKey inferSiconiaStrategy(WorkflowState state) {
        if (state.siconiaResult() == null) {
            return StrategyKey.UNKNOWN;
        }
        return switch (state.siconiaResult().inputClass()) {
            case "XML_TRACE" -> StrategyKey.SICONIA_XML_ANALYSIS;
            case "ALARM_CODE" -> StrategyKey.SICONIA_ALARM_ANALYSIS;
            case "LOG_BLOCK" -> StrategyKey.SICONIA_LOG_ANALYSIS;
            default -> StrategyKey.UNKNOWN;
        };
    }

    private double confidenceForDecode(DecodeResult decodeResult) {
        DlmsNormalizedKind kind = decodeResult.processingMetadata() != null
                ? decodeResult.processingMetadata().normalizedKind()
                : null;
        if (kind == null) {
            return 0.9;
        }
        return switch (kind) {
            case FRAME_HEX -> 0.96;
            case APDU_HEX -> 0.93;
            case AXDR_HEX -> 0.92;
            case OBIS_QUERY -> 0.94;
        };
    }

    private boolean isTentativeDecode(WorkflowState state, DecodeResult decodeResult) {
        if (isTentativeFromMetadata(state.strategyMetadata())) {
            return true;
        }
        return decodeResult.hdlcFrame() != null && !decodeResult.hdlcFrame().fcsValid();
    }

    private boolean isTentativeFromMetadata(StrategyMetadata metadata) {
        return metadata != null && metadata.tentative();
    }

    private StrategyKey strategyOrFallback(StrategyMetadata metadata, StrategyKey fallback) {
        if (metadata == null || metadata.selectedStrategy() == null) {
            return fallback;
        }
        return metadata.selectedStrategy();
    }

    private List<String> warningsFrom(WorkflowState state) {
        List<String> warnings = new ArrayList<>();
        if (state.strategyMetadata() != null && state.strategyMetadata().warnings() != null) {
            warnings.addAll(state.strategyMetadata().warnings());
        }
        if (state.errors() != null && !state.errors().isEmpty()) {
            warnings.addAll(state.errors());
        }
        return List.copyOf(warnings);
    }

    private List<String> anomaliesFrom(WorkflowState state) {
        return state.anomalies() == null ? List.of() : List.copyOf(state.anomalies());
    }

    private double confidenceFrom(WorkflowState state, double fallback) {
        if (state.strategyMetadata() == null) {
            return fallback;
        }
        return state.strategyMetadata().confidence();
    }
}
