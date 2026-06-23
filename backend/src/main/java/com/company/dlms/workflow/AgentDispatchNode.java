package com.company.dlms.workflow;

import com.company.dlms.agent.DecoderAgentNode;
import com.company.dlms.agent.ProfileAgentNode;
import com.company.dlms.agent.ReportingAgentNode;
import com.company.dlms.agent.RetrievalAgentNode;
import com.company.dlms.agent.SecurityAgentNode;
import com.company.dlms.agent.SiconiaAgentNode;
import com.company.dlms.domain.CasualQueryClassifier;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.rag.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AgentDispatchNode {

    private final DecoderAgentNode decoderAgentNode;
    private final RetrievalAgentNode retrievalAgentNode;
    private final SiconiaAgentNode siconiaAgentNode;
    private final SecurityAgentNode securityAgentNode;
    private final ProfileAgentNode profileAgentNode;
    private final ReportingAgentNode reportingAgentNode;
    private final FollowUpResolver followUpResolver;

    public AgentDispatchNode(
            DecoderAgentNode decoderAgentNode,
            RetrievalAgentNode retrievalAgentNode,
            SiconiaAgentNode siconiaAgentNode,
            SecurityAgentNode securityAgentNode,
            ProfileAgentNode profileAgentNode,
            ReportingAgentNode reportingAgentNode,
            FollowUpResolver followUpResolver
    ) {
        this.decoderAgentNode = decoderAgentNode;
        this.retrievalAgentNode = retrievalAgentNode;
        this.siconiaAgentNode = siconiaAgentNode;
        this.securityAgentNode = securityAgentNode;
        this.profileAgentNode = profileAgentNode;
        this.reportingAgentNode = reportingAgentNode;
        this.followUpResolver = followUpResolver;
    }

    public WorkflowState dispatch(WorkflowState state) {
        if (state.strategyMetadata() != null && state.strategyMetadata().ambiguous()) {
            return state;
        }
        if (state.orchestrationMode() == OrchestrationMode.NATURAL_LANGUAGE_AGENTIC
                || state.orchestrationMode() == OrchestrationMode.AMBIGUOUS_SAFE_FALLBACK) {
            return state;
        }
        DlmsIntent intent = state.intent();
        if (intent == null) {
            return state;
        }

        WorkflowState dispatched = switch (intent) {
            case FRAME_DECODE -> profileAgentNode.process(decoderAgentNode.process(state));
            case APDU_ANALYSIS -> decoderAgentNode.process(state);
            case SICONIA_TROUBLESHOOT -> {
                if (state.inputClass() == InputClass.QUERY) {
                    yield retrievalAgentNode.process(state);
                }
                WorkflowState analyzed = siconiaAgentNode.process(state);
                yield analyzed;
            }
            case PROFILE_DECODE -> retrievalAgentNode.process(profileAgentNode.process(state));
            case SECURITY_EXPLAIN -> securityAgentNode.process(state);
            case OBIS_LOOKUP -> {
                if (state.dlmsNormalization() != null
                        && state.dlmsNormalization().kind() == DlmsNormalizedKind.OBIS_QUERY) {
                    yield retrievalAgentNode.process(decoderAgentNode.process(state));
                }
                yield retrievalAgentNode.process(state);
            }
            case DOCUMENTATION -> retrievalAgentNode.process(state);
            case UNKNOWN -> {
                if (state.inputClass() == InputClass.ALARM_CODE
                        || state.inputClass() == InputClass.XML_TRACE
                        || state.inputClass() == InputClass.LOG_BLOCK) {
                    yield siconiaAgentNode.process(state);
                }
                if (state.inputClass() == InputClass.HEX_FRAME) {
                    yield decoderAgentNode.process(state);
                }
                if (state.inputClass() == InputClass.QUERY
                        && (CasualQueryClassifier.isCasualNonTechnicalQuery(state.rawInput())
                        || CasualQueryClassifier.isAssistantCapabilityQuestion(state.rawInput())
                        || followUpResolver.isFollowUpQuestion(state.rawInput()))) {
                    yield state;
                }
                yield retrievalAgentNode.process(state);
            }
        };
        return dispatched != null ? dispatched : state;
    }

    public WorkflowState executeTool(WorkflowState state, String toolName, String query, String rationale) {
        if (state == null || toolName == null || toolName.isBlank()) {
            return state;
        }

        String normalizedTool = toolName.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedTool) {
            case "decode_frame" -> executeDeterministicDecodeTool(
                    state,
                    DlmsIntent.FRAME_DECODE,
                    InputClass.HEX_FRAME,
                    "decode_frame",
                    "Ran deterministic HDLC frame decode on the current input."
            );
            case "decode_apdu", "decode_axdr" -> executeDeterministicDecodeTool(
                    state,
                    DlmsIntent.APDU_ANALYSIS,
                    InputClass.QUERY,
                    normalizedTool,
                    "Ran deterministic DLMS payload decode on the current input."
            );
            case "resolve_obis" -> executeDeterministicDecodeTool(
                    state,
                    DlmsIntent.OBIS_LOOKUP,
                    InputClass.QUERY,
                    "resolve_obis",
                    "Ran deterministic OBIS resolution on the current input."
            );
            case "analyze_alarm" -> executeSiconiaTool(
                    state,
                    InputClass.ALARM_CODE,
                    "analyze_alarm",
                    "Ran deterministic SICONIA alarm analysis."
            );
            case "analyze_xml" -> executeSiconiaTool(
                    state,
                    InputClass.XML_TRACE,
                    "analyze_xml",
                    "Ran deterministic SICONIA XML analysis."
            );
            case "analyze_log" -> executeSiconiaTool(
                    state,
                    InputClass.LOG_BLOCK,
                    "analyze_log",
                    "Ran deterministic SICONIA log analysis."
            );
            case "search_docs" -> executeSearchDocsTool(state, query, rationale);
            case "get_session_memory" -> appendTrace(
                    state,
                    "get_session_memory",
                    summarizeSessionMemory(state),
                    true,
                    "SESSION"
            );
            case "get_session_history" -> appendTrace(
                    state,
                    "get_session_history",
                    summarizeSessionHistory(state),
                    true,
                    "SESSION"
            );
            case "detect_anomalies" -> appendTrace(
                    state,
                    "detect_anomalies",
                    summarizeAnomalies(state),
                    true,
                    "ANOMALY"
            );
            default -> appendTrace(
                    state,
                    normalizedTool,
                    "Planner requested an unsupported internal tool.",
                    false,
                    "PLANNER"
            );
        };
    }

    private WorkflowState executeDeterministicDecodeTool(
            WorkflowState state,
            DlmsIntent intent,
            InputClass inputClass,
            String toolName,
            String successSummary
    ) {
        WorkflowState temp = state.toBuilder()
                .intent(intent)
                .inputClass(inputClass)
                .build();
        WorkflowState decoded = intent == DlmsIntent.FRAME_DECODE
                ? profileAgentNode.process(decoderAgentNode.process(temp))
                : decoderAgentNode.process(temp);
        WorkflowState merged = state.toBuilder()
                .decodeResult(decoded.decodeResult())
                .profileResult(decoded.profileResult())
                .mcpUsed(state.mcpUsed() || decoded.mcpUsed())
                .hdlcClientSap(decoded.hdlcClientSap() != null ? decoded.hdlcClientSap() : state.hdlcClientSap())
                .hdlcServerSap(decoded.hdlcServerSap() != null ? decoded.hdlcServerSap() : state.hdlcServerSap())
                .frameCounter(decoded.frameCounter() != null ? decoded.frameCounter() : state.frameCounter())
                .frameCounterHex(decoded.frameCounterHex() != null ? decoded.frameCounterHex() : state.frameCounterHex())
                .securitySuite(decoded.securitySuite() != null ? decoded.securitySuite() : state.securitySuite())
                .invokeId(decoded.invokeId() != null ? decoded.invokeId() : state.invokeId())
                .associationState(decoded.associationState() != null ? decoded.associationState() : state.associationState())
                .maxPduSize(decoded.maxPduSize() != null ? decoded.maxPduSize() : state.maxPduSize())
                .lastObis(decoded.lastObis() != null ? decoded.lastObis() : state.lastObis())
                .lastIc(decoded.lastIc() != null ? decoded.lastIc() : state.lastIc())
                .errors(mergeErrors(state.errors(), decoded.errors()))
                .build();
        return appendTrace(
                merged,
                toolName,
                merged.decodeResult() != null ? successSummary : "Deterministic decode did not produce a structured result.",
                merged.decodeResult() != null,
                merged.mcpUsed() ? "MCP" : "JAVA"
        );
    }

    private WorkflowState executeSiconiaTool(
            WorkflowState state,
            InputClass inputClass,
            String toolName,
            String successSummary
    ) {
        WorkflowState temp = state.toBuilder()
                .intent(DlmsIntent.SICONIA_TROUBLESHOOT)
                .inputClass(inputClass)
                .build();
        WorkflowState analyzed = siconiaAgentNode.process(temp);
        WorkflowState merged = state.toBuilder()
                .siconiaResult(analyzed.siconiaResult())
                .mcpUsed(state.mcpUsed() || analyzed.mcpUsed())
                .errors(mergeErrors(state.errors(), analyzed.errors()))
                .build();
        return appendTrace(
                merged,
                toolName,
                merged.siconiaResult() != null ? successSummary : "Structured SICONIA analysis did not produce a result.",
                merged.siconiaResult() != null,
                merged.mcpUsed() ? "MCP" : "JAVA"
        );
    }

    private WorkflowState executeSearchDocsTool(WorkflowState state, String query, String rationale) {
        String searchQuery = query == null || query.isBlank() ? state.rawInput() : query.trim();
        boolean securitySearch = state.intent() == DlmsIntent.SECURITY_EXPLAIN
                || (rationale != null && rationale.toLowerCase(Locale.ROOT).contains("security"));
        WorkflowState temp = state.toBuilder()
                .rawInput(searchQuery)
                .inputClass(InputClass.QUERY)
                .intent(securitySearch ? DlmsIntent.SECURITY_EXPLAIN : DlmsIntent.DOCUMENTATION)
                .build();
        WorkflowState searched = securitySearch
                ? securityAgentNode.process(temp)
                : retrievalAgentNode.process(temp);
        List<RetrievalResult> results = searched.retrievalResults();
        String summary = results == null || results.isEmpty()
                ? "No supporting documentation snippets were recovered."
                : "Recovered " + results.size() + " supporting documentation snippet"
                + (results.size() == 1 ? "" : "s") + ".";
        WorkflowState merged = state.toBuilder()
                .retrievalResults(results)
                .mcpUsed(state.mcpUsed() || searched.mcpUsed())
                .securityContextSummary(searched.securityContextSummary() != null
                        ? searched.securityContextSummary()
                        : state.securityContextSummary())
                .errors(mergeErrors(state.errors(), searched.errors()))
                .build();
        return appendTrace(merged, "search_docs", summary, results != null && !results.isEmpty(), "RETRIEVAL");
    }

    private WorkflowState appendTrace(
            WorkflowState state,
            String toolName,
            String summary,
            boolean authoritative,
            String provenance
    ) {
        return state.addToolTrace(new ToolTraceEntry(toolName, summary, authoritative, provenance));
    }

    private List<String> mergeErrors(List<String> base, List<String> additional) {
        List<String> merged = new ArrayList<>();
        if (base != null) {
            merged.addAll(base);
        }
        if (additional != null) {
            additional.stream()
                    .filter(error -> error != null && !error.isBlank())
                    .filter(error -> !merged.contains(error))
                    .forEach(merged::add);
        }
        return List.copyOf(merged);
    }

    private String summarizeSessionMemory(WorkflowState state) {
        List<String> parts = new ArrayList<>();
        if (state.recentArtifactResults() != null && !state.recentArtifactResults().isEmpty()) {
            parts.add("last multi-artifact batch with " + state.recentArtifactResults().size() + " artifact result"
                    + (state.recentArtifactResults().size() == 1 ? "" : "s"));
        }
        if (state.associationState() != null && !state.associationState().isBlank()) {
            parts.add("association state " + state.associationState());
        }
        if (state.hdlcClientSap() != null && !state.hdlcClientSap().isBlank()) {
            parts.add("client SAP " + state.hdlcClientSap());
        }
        if (state.hdlcServerSap() != null && !state.hdlcServerSap().isBlank()) {
            parts.add("server SAP " + state.hdlcServerSap());
        }
        if (state.lastObis() != null && !state.lastObis().isBlank()) {
            parts.add("last OBIS " + state.lastObis());
        }
        if (parts.isEmpty()) {
            return "No session STM fields are currently populated for this conversation.";
        }
        return "Session memory shows " + String.join(", ", parts) + ".";
    }

    private String summarizeSessionHistory(WorkflowState state) {
        if (state.narrativeContext() == null || state.narrativeContext().isEmpty()) {
            return "No narrative session history is currently available for this conversation.";
        }
        var latest = state.narrativeContext().getLast();
        String label = latest.apduType() == null || latest.apduType().isBlank() ? "unknown event" : latest.apduType();
        return "Session history contains " + state.narrativeContext().size()
                + " recorded event" + (state.narrativeContext().size() == 1 ? "" : "s")
                + "; the latest is " + label + ".";
    }

    private String summarizeAnomalies(WorkflowState state) {
        if (state.anomalies() == null || state.anomalies().isEmpty()) {
            return "No deterministic anomalies are currently recorded for this turn.";
        }
        return "Deterministic anomaly detection currently reports: " + String.join("; ", state.anomalies()) + ".";
    }
}
