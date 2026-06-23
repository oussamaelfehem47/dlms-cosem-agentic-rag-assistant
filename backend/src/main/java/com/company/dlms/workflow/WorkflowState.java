package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.answer.GroundedAnswerContext;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.profile.ProfileResult;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record WorkflowState(
        String sessionId,
        String conversationId,
        String rawInput,
        SiconiaInputNormalization siconiaNormalization,
        DlmsInputNormalization dlmsNormalization,
        InputClass inputClass,
        DlmsIntent intent,
        StrategyMetadata strategyMetadata,
        OrchestrationMode orchestrationMode,
        String userRole,
        String hdlcClientSap,
        String hdlcServerSap,
        String frameCounter,
        String frameCounterHex,
        String securitySuite,
        String invokeId,
        String associationState,
        String maxPduSize,
        String lastObis,
        String lastIc,
        List<RetrievalResult> retrievalResults,
        Object decodeResult,
        ProfileResult profileResult,
        SiconiaResult siconiaResult,
        List<SessionEvent> narrativeContext,
        List<ArtifactResultPayload> recentArtifactResults,
        List<String> anomalies,
        StmSnapshot stmSnapshot,
        String securityContextSummary,
        GroundedAnswerContext groundedAnswerContext,
        String llmPrompt,
        String explanation,
        boolean outputFiltered,
        boolean mcpUsed,
        boolean plannerUsed,
        List<ToolTraceEntry> toolTrace,
        String plannerFallbackReason,
        List<String> errors,
        long startTimeMs
) implements java.io.Serializable {
    public static WorkflowState empty(String sessionId, String conversationId, String rawInput) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(conversationId, "conversationId is required");
        Objects.requireNonNull(rawInput, "rawInput is required");
        return new Builder()
                .sessionId(sessionId)
                .conversationId(conversationId)
                .rawInput(rawInput)
                .recentArtifactResults(List.of())
                .toolTrace(List.of())
                .errors(List.of())
                .startTimeMs(0L)
                .build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public WorkflowState withInputClass(InputClass inputClass) {
        return toBuilder().inputClass(inputClass).build();
    }

    public WorkflowState withIntent(DlmsIntent intent) {
        return toBuilder().intent(intent).build();
    }

    public WorkflowState withStrategyMetadata(StrategyMetadata strategyMetadata) {
        return toBuilder().strategyMetadata(strategyMetadata).build();
    }

    public WorkflowState withOrchestrationMode(OrchestrationMode orchestrationMode) {
        return toBuilder().orchestrationMode(orchestrationMode).build();
    }

    public WorkflowState withExplanation(String explanation) {
        return toBuilder().explanation(explanation).build();
    }

    public WorkflowState withLlmPrompt(String llmPrompt) {
        return toBuilder().llmPrompt(llmPrompt).build();
    }

    public WorkflowState withSecurityContextSummary(String securityContextSummary) {
        return toBuilder().securityContextSummary(securityContextSummary).build();
    }

    public WorkflowState withGroundedAnswerContext(GroundedAnswerContext groundedAnswerContext) {
        return toBuilder().groundedAnswerContext(groundedAnswerContext).build();
    }

    public WorkflowState withSiconiaResult(SiconiaResult siconiaResult) {
        return toBuilder().siconiaResult(siconiaResult).build();
    }

    public WorkflowState withDecodeResult(Object decodeResult) {
        return toBuilder().decodeResult(decodeResult).build();
    }

    public WorkflowState withProfileResult(ProfileResult profileResult) {
        return toBuilder().profileResult(profileResult).build();
    }

    public WorkflowState withMcpUsed(boolean mcpUsed) {
        return toBuilder().mcpUsed(mcpUsed).build();
    }

    public WorkflowState withStmSnapshot(StmSnapshot stmSnapshot) {
        return toBuilder().stmSnapshot(stmSnapshot).build();
    }

    public WorkflowState withRecentArtifactResults(List<ArtifactResultPayload> recentArtifactResults) {
        return toBuilder().recentArtifactResults(recentArtifactResults).build();
    }

    public WorkflowState withPlannerUsed(boolean plannerUsed) {
        return toBuilder().plannerUsed(plannerUsed).build();
    }

    public WorkflowState withPlannerFallbackReason(String plannerFallbackReason) {
        return toBuilder().plannerFallbackReason(plannerFallbackReason).build();
    }

    public WorkflowState addToolTrace(ToolTraceEntry entry) {
        if (entry == null) {
            return this;
        }
        Builder builder = toBuilder();
        List<ToolTraceEntry> next = new ArrayList<>(builder.toolTrace == null ? List.of() : builder.toolTrace);
        next.add(entry);
        builder.toolTrace(next);
        return builder.build();
    }

    public WorkflowState addError(String error) {
        Builder b = toBuilder();
        List<String> next = new ArrayList<>(b.errors == null ? List.of() : b.errors);
        next.add(error);
        b.errors(next);
        return b.build();
    }

    public static final class Builder {
        private String sessionId;
        private String conversationId;
        private String rawInput;
        private SiconiaInputNormalization siconiaNormalization;
        private DlmsInputNormalization dlmsNormalization;
        private InputClass inputClass;
        private DlmsIntent intent;
        private StrategyMetadata strategyMetadata;
        private OrchestrationMode orchestrationMode;
        private String userRole;
        private String hdlcClientSap;
        private String hdlcServerSap;
        private String frameCounter;
        private String frameCounterHex;
        private String securitySuite;
        private String invokeId;
        private String associationState;
        private String maxPduSize;
        private String lastObis;
        private String lastIc;
        private List<RetrievalResult> retrievalResults;
        private boolean retrievalResultsExplicitlySet;
        private Object decodeResult;
        private boolean decodeResultExplicitlySet;
        private ProfileResult profileResult;
        private SiconiaResult siconiaResult;
        private boolean siconiaResultExplicitlySet;
        private List<SessionEvent> narrativeContext;
        private boolean narrativeContextExplicitlySet;
        private List<ArtifactResultPayload> recentArtifactResults;
        private List<String> anomalies;
        private boolean anomaliesExplicitlySet;
        private StmSnapshot stmSnapshot;
        private boolean stmSnapshotExplicitlySet;
        private String securityContextSummary;
        private GroundedAnswerContext groundedAnswerContext;
        private String llmPrompt;
        private String explanation;
        private boolean outputFiltered;
        private boolean mcpUsed;
        private boolean plannerUsed;
        private List<ToolTraceEntry> toolTrace;
        private String plannerFallbackReason;
        private List<String> errors;
        private long startTimeMs;

        public Builder() {}

        private Builder(WorkflowState state) {
            this.sessionId = state.sessionId;
            this.conversationId = state.conversationId;
            this.rawInput = state.rawInput;
            this.siconiaNormalization = state.siconiaNormalization;
            this.dlmsNormalization = state.dlmsNormalization;
            this.inputClass = state.inputClass;
            this.intent = state.intent;
            this.strategyMetadata = state.strategyMetadata;
            this.orchestrationMode = state.orchestrationMode;
            this.userRole = state.userRole;
            this.hdlcClientSap = state.hdlcClientSap;
            this.hdlcServerSap = state.hdlcServerSap;
            this.frameCounter = state.frameCounter;
            this.frameCounterHex = state.frameCounterHex;
            this.securitySuite = state.securitySuite;
            this.invokeId = state.invokeId;
            this.associationState = state.associationState;
            this.maxPduSize = state.maxPduSize;
            this.lastObis = state.lastObis;
            this.lastIc = state.lastIc;
            this.retrievalResults = state.retrievalResults;
            this.decodeResult = state.decodeResult;
            this.profileResult = state.profileResult;
            this.siconiaResult = state.siconiaResult;
            this.narrativeContext = state.narrativeContext;
            this.recentArtifactResults = state.recentArtifactResults;
            this.anomalies = state.anomalies;
            this.stmSnapshot = state.stmSnapshot;
            this.securityContextSummary = state.securityContextSummary;
            this.groundedAnswerContext = state.groundedAnswerContext;
            this.llmPrompt = state.llmPrompt;
            this.explanation = state.explanation;
            this.outputFiltered = state.outputFiltered;
            this.mcpUsed = state.mcpUsed;
            this.plannerUsed = state.plannerUsed;
            this.toolTrace = state.toolTrace;
            this.plannerFallbackReason = state.plannerFallbackReason;
            this.errors = state.errors;
            this.startTimeMs = state.startTimeMs;
        }

        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
        public Builder rawInput(String rawInput) { this.rawInput = rawInput; return this; }
        public Builder siconiaNormalization(SiconiaInputNormalization siconiaNormalization) { this.siconiaNormalization = siconiaNormalization; return this; }
        public Builder dlmsNormalization(DlmsInputNormalization dlmsNormalization) { this.dlmsNormalization = dlmsNormalization; return this; }
        public Builder inputClass(InputClass inputClass) { this.inputClass = inputClass; return this; }
        public Builder intent(DlmsIntent intent) { this.intent = intent; return this; }
        public Builder strategyMetadata(StrategyMetadata strategyMetadata) { this.strategyMetadata = strategyMetadata; return this; }
        public Builder orchestrationMode(OrchestrationMode orchestrationMode) { this.orchestrationMode = orchestrationMode; return this; }
        public Builder userRole(String userRole) { this.userRole = userRole; return this; }
        public Builder hdlcClientSap(String v) { this.hdlcClientSap = v; return this; }
        public Builder hdlcServerSap(String v) { this.hdlcServerSap = v; return this; }
        public Builder frameCounter(String v) { this.frameCounter = v; return this; }
        public Builder frameCounterHex(String v) { this.frameCounterHex = v; return this; }
        public Builder securitySuite(String v) { this.securitySuite = v; return this; }
        public Builder invokeId(String v) { this.invokeId = v; return this; }
        public Builder associationState(String v) { this.associationState = v; return this; }
        public Builder maxPduSize(String v) { this.maxPduSize = v; return this; }
        public Builder lastObis(String v) { this.lastObis = v; return this; }
        public Builder lastIc(String v) { this.lastIc = v; return this; }
        public Builder retrievalResults(List<RetrievalResult> v) {
            this.retrievalResults = v;
            this.retrievalResultsExplicitlySet = true;
            return this;
        }
        public Builder decodeResult(Object v) {
            this.decodeResult = v;
            this.decodeResultExplicitlySet = true;
            return this;
        }
        public Builder profileResult(ProfileResult v) { this.profileResult = v; return this; }
        public Builder siconiaResult(SiconiaResult v) {
            this.siconiaResult = v;
            this.siconiaResultExplicitlySet = true;
            return this;
        }
        public Builder narrativeContext(List<SessionEvent> v) {
            this.narrativeContext = v;
            this.narrativeContextExplicitlySet = true;
            return this;
        }
        public Builder recentArtifactResults(List<ArtifactResultPayload> v) { this.recentArtifactResults = v; return this; }
        public Builder anomalies(List<String> v) {
            this.anomalies = v;
            this.anomaliesExplicitlySet = true;
            return this;
        }
        public Builder stmSnapshot(StmSnapshot v) {
            this.stmSnapshot = v;
            this.stmSnapshotExplicitlySet = true;
            return this;
        }
        public Builder securityContextSummary(String v) { this.securityContextSummary = v; return this; }
        public Builder groundedAnswerContext(GroundedAnswerContext v) { this.groundedAnswerContext = v; return this; }
        public Builder llmPrompt(String v) { this.llmPrompt = v; return this; }
        public Builder explanation(String v) { this.explanation = v; return this; }
        public Builder outputFiltered(boolean v) { this.outputFiltered = v; return this; }
        public Builder mcpUsed(boolean v) { this.mcpUsed = v; return this; }
        public Builder plannerUsed(boolean v) { this.plannerUsed = v; return this; }
        public Builder toolTrace(List<ToolTraceEntry> v) { this.toolTrace = v; return this; }
        public Builder plannerFallbackReason(String v) { this.plannerFallbackReason = v; return this; }
        public Builder errors(List<String> v) { this.errors = v; return this; }
        public Builder startTimeMs(long v) { this.startTimeMs = v; return this; }

        public WorkflowState build() {
            Objects.requireNonNull(sessionId, "sessionId is required");
            Objects.requireNonNull(conversationId, "conversationId is required");
            Objects.requireNonNull(rawInput, "rawInput is required");
            syncCanonicalFieldsFromGroundedAnswerContext();
            List<String> safeErrors = errors == null ? List.of() : List.copyOf(errors);
            List<SessionEvent> safeNarrative = narrativeContext == null ? null : List.copyOf(narrativeContext);
            List<ArtifactResultPayload> safeRecentArtifactResults = recentArtifactResults == null ? List.of() : List.copyOf(recentArtifactResults);
            List<RetrievalResult> safeRetrieval = retrievalResults == null ? null : List.copyOf(retrievalResults);
            List<String> safeAnomalies = anomalies == null ? null : List.copyOf(anomalies);
            List<ToolTraceEntry> safeToolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
            return new WorkflowState(
                    sessionId,
                    conversationId,
                    rawInput,
                    siconiaNormalization,
                    dlmsNormalization,
                    inputClass,
                    intent,
                    strategyMetadata,
                    orchestrationMode,
                    userRole,
                    hdlcClientSap,
                    hdlcServerSap,
                    frameCounter,
                    frameCounterHex,
                    securitySuite,
                    invokeId,
                    associationState,
                    maxPduSize,
                    lastObis,
                    lastIc,
                    safeRetrieval,
                    decodeResult,
                    profileResult,
                    siconiaResult,
                    safeNarrative,
                    safeRecentArtifactResults,
                    safeAnomalies,
                    stmSnapshot,
                    securityContextSummary,
                    groundedAnswerContext,
                    llmPrompt,
                    explanation,
                    outputFiltered,
                    mcpUsed,
                    plannerUsed,
                    safeToolTrace,
                    plannerFallbackReason,
                    safeErrors,
                    startTimeMs
            );
        }

        private void syncCanonicalFieldsFromGroundedAnswerContext() {
            if (groundedAnswerContext == null) {
                return;
            }

            if (canonicalFieldsWereExplicitlySet()) {
                groundedAnswerContext = new GroundedAnswerContext(
                        groundedAnswerContext.mode(),
                        groundedAnswerContext.selectedStrategy(),
                        groundedAnswerContext.ambiguityCandidates(),
                        decodeResultExplicitlySet ? castDecodeResult(decodeResult) : groundedAnswerContext.decodeResult(),
                        siconiaResultExplicitlySet ? siconiaResult : groundedAnswerContext.siconiaResult(),
                        retrievalResultsExplicitlySet ? nullSafeList(retrievalResults) : groundedAnswerContext.retrievalResults(),
                        stmSnapshotExplicitlySet ? stmSnapshot : groundedAnswerContext.stmSnapshot(),
                        narrativeContextExplicitlySet ? nullSafeList(narrativeContext) : groundedAnswerContext.narrativeContext(),
                        groundedAnswerContext.warnings(),
                        anomaliesExplicitlySet ? nullSafeStringList(anomalies) : groundedAnswerContext.anomalies(),
                        groundedAnswerContext.confidence(),
                        groundedAnswerContext.tentative()
                );
            }

            decodeResult = groundedAnswerContext.decodeResult();
            siconiaResult = groundedAnswerContext.siconiaResult();
            retrievalResults = groundedAnswerContext.retrievalResults();
            stmSnapshot = groundedAnswerContext.stmSnapshot();
            narrativeContext = groundedAnswerContext.narrativeContext();
            anomalies = groundedAnswerContext.anomalies();
        }

        private boolean canonicalFieldsWereExplicitlySet() {
            return retrievalResultsExplicitlySet
                    || decodeResultExplicitlySet
                    || siconiaResultExplicitlySet
                    || narrativeContextExplicitlySet
                    || anomaliesExplicitlySet
                    || stmSnapshotExplicitlySet;
        }

        private static com.company.dlms.domain.decoder.DecodeResult castDecodeResult(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof com.company.dlms.domain.decoder.DecodeResult decodeResult) {
                return decodeResult;
            }
            throw new IllegalArgumentException(
                    "groundedAnswerContext only supports DecodeResult for canonical decode synchronization"
            );
        }

        private static <T> List<T> nullSafeList(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }

        private static List<String> nullSafeStringList(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public WorkflowState withStartTimeMs(long startTimeMs) {
        return toBuilder().startTimeMs(startTimeMs).build();
    }

    public String analysisInput() {
        if (siconiaNormalization != null && siconiaNormalization.normalizedInput() != null) {
            return siconiaNormalization.normalizedInput();
        }
        if (dlmsNormalization != null && dlmsNormalization.normalizedInput() != null) {
            return dlmsNormalization.normalizedInput();
        }
        return rawInput;
    }
}
