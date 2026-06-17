package com.company.dlms.agent;

import com.company.dlms.agent.siconia.AlarmDecoder;
import com.company.dlms.agent.siconia.LogClassifier;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;
import com.company.dlms.agent.siconia.XmlTraceParser;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.siconia.*;

import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.infrastructure.mcp.McpTools;

@Component
public class SiconiaAgentNode {

    private static final Logger log = LoggerFactory.getLogger(SiconiaAgentNode.class);
    private static final Duration PERSIST_TIMEOUT = Duration.ofSeconds(2);
    private static final java.util.regex.Pattern NAMED_ALARM_TOKEN = java.util.regex.Pattern.compile("\\b(?=.*[G-Z_])[A-Z][A-Z0-9_]{2,39}\\b");
    private static final java.util.regex.Pattern HEX_ALARM_CONTEXT = java.util.regex.Pattern.compile(
            "\\b(alarm|severity|critical|high|medium|low|info|root\\s*cause|remediation|fault|error\\s*code)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );
    private static final java.util.regex.Pattern NAMED_ALARM_CONTEXT = java.util.regex.Pattern.compile(
            "\\b(alarm|severity|critical|high|medium|low|info|root\\s*cause|remediation|fault|error\\s*code|what\\s+does|mean(?:ing)?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );
    private static final java.util.regex.Pattern DLMS_PROTOCOL_CONTEXT = java.util.regex.Pattern.compile(
            "\\b(hdlc|frame|obis|dlms|cosem|green\\s*book|blue\\s*book|iec\\s*62056|aarq|aare|apdu|axdr|security|suite)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private final XmlTraceParser xmlTraceParser;
    private final AlarmDecoder alarmDecoder;
    private final LogClassifier logClassifier;
    private final McpDispatcher mcpDispatcher;
    private final SessionEventService sessionEventService;
    private final com.company.dlms.memory.SessionNarrativeService sessionNarrativeService;

    SiconiaAgentNode(
            XmlTraceParser xmlTraceParser, 
            AlarmDecoder alarmDecoder, 
            LogClassifier logClassifier, 
            McpDispatcher mcpDispatcher,
            SessionEventService sessionEventService,
            com.company.dlms.memory.SessionNarrativeService sessionNarrativeService
    ) {
        this.xmlTraceParser = xmlTraceParser;
        this.alarmDecoder = alarmDecoder;
        this.logClassifier = logClassifier;
        this.mcpDispatcher = mcpDispatcher;
        this.sessionEventService = sessionEventService;
        this.sessionNarrativeService = sessionNarrativeService;
    }

    public WorkflowState process(WorkflowState state) {
        try {
            if (state == null) {
                return WorkflowState.empty("unknown", "unknown", "").addError("state is null");
            }

            InputClass inputClass = state.inputClass();
            if (inputClass == null) {
                return state.addError("inputClass is null");
            }
            String raw = state.rawInput();
            String analysisInput = state.analysisInput();
            if (raw == null) {
                return state.addError("rawInput is null");
            }
            if (inputClass == InputClass.QUERY) {
                return state;
            }

            // Attempt MCP first if appropriate
            McpResult mcp = null;
            WorkflowState finalState = state;

            SiconiaResult result = switch (inputClass) {
                case XML_TRACE -> {
                    mcp = mcpDispatcher.dispatch(McpTools.SICONIA_PARSE_XML, Map.of("xml_content", analysisInput))
                            .subscribeOn(Schedulers.boundedElastic())
                            .block(java.time.Duration.ofSeconds(10));
                    if (mcp != null && mcp.success()) {
                        SiconiaXmlTrace trace = mapMcpXmlTrace(mcp.result(), analysisInput);
                        if (trace != null) {
                            // FIX 2: Check if MCP returned useful traces or empty/generic data
                            if (!isMcpXmlTraceUseless(trace)) {
                                finalState = finalState.withMcpUsed(true);
                                yield new SiconiaResult(
                                        trace,
                                        decodeAlarmsInTrace(trace),
                                        null,
                                        "MCP:" + McpTools.SICONIA_PARSE_XML,
                                        buildProcessingMetadata(state, InputClass.XML_TRACE, trace, List.of(), true)
                                );
                            } else {
                                log.warn("MCP XML trace returned empty/generic data, falling back to Java XmlTraceParser");
                            }
                        }
                    }
                    yield processXmlTrace(analysisInput, state);
                }
                case ALARM_CODE -> {
                    String code = extractCode(analysisInput);
                    mcp = mcpDispatcher.dispatch(McpTools.SICONIA_DECODE_ALARM, Map.of("alarm_code", code))
                            .subscribeOn(Schedulers.boundedElastic())
                            .block(java.time.Duration.ofSeconds(10));
                    if (mcp != null && mcp.success()) {
                        List<AlarmDecodeResult> alarms = mapMcpAlarmResult(mcp.result());
                        log.debug("MCP alarm results: {}", alarms);
                        if (alarms != null) {
                            boolean useless = isMcpAlarmResultUseless(alarms);
                            log.debug("isMcpAlarmResultUseless: {}", useless);
                            // FIX 2: Check if MCP returned UNKNOWN/generic results — if so, fall through to Java decoder
                            if (!useless) {
                                finalState = finalState.withMcpUsed(true);
                                yield new SiconiaResult(
                                        null,
                                        alarms,
                                        null,
                                        "MCP:" + McpTools.SICONIA_DECODE_ALARM,
                                        buildProcessingMetadata(state, InputClass.ALARM_CODE, null, List.of(), true)
                                );
                            } else {
                                log.warn("MCP alarm decode returned UNKNOWN/generic data, falling back to Java AlarmDecoder");
                            }
                        }
                    }
                    yield processAlarmCode(code, state);
                }
                case LOG_BLOCK -> {
                    mcp = mcpDispatcher.dispatch(McpTools.SICONIA_CLASSIFY_LOG, Map.of("log_text", analysisInput))
                            .subscribeOn(Schedulers.boundedElastic())
                            .block(java.time.Duration.ofSeconds(10));
                    if (mcp != null && mcp.success()) {
                        LogAnalysis analysis = mapMcpLogAnalysis(mcp.result());
                        if (analysis != null) {
                            // FIX 2: Check if MCP returned generic/empty log analysis
                            if (!isMcpLogAnalysisUseless(analysis)) {
                                finalState = finalState.withMcpUsed(true);
                                yield new SiconiaResult(
                                        null,
                                        null,
                                        analysis,
                                        "MCP:" + McpTools.SICONIA_CLASSIFY_LOG,
                                        buildProcessingMetadata(state, InputClass.LOG_BLOCK, null, List.of(), true)
                                );
                            } else {
                                log.warn("MCP log analysis returned generic/empty data, falling back to Java LogClassifier");
                            }
                        }
                    }
                    yield processLogBlock(analysisInput, state);
                }
                default -> null;
            };

            if (result == null) {
                return finalState.addError("Unknown input class: " + inputClass);
            }

            WorkflowState updated = finalState.withSiconiaResult(result);

            persistNarrativeEvent(updated);

            return updated;
        } catch (Exception e) {
            log.warn("SiconiaAgentNode failed sessionId={} err={}", state == null ? "null" : state.sessionId(), e.toString());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return state == null ? WorkflowState.empty("unknown", "unknown", "").addError(msg) : state.addError(msg);
        }
    }

    // ── MCP result quality checks (FIX 2) ────────────────────────────────────────

    /**
     * Checks if MCP alarm results are useless (all UNKNOWN with generic root cause).
     * When MCP returns garbage, the Java AlarmDecoder bitfield decomposition should be used instead.
     */
    private boolean isMcpAlarmResultUseless(List<AlarmDecodeResult> alarms) {
        if (alarms == null || alarms.isEmpty()) return true;
        return alarms.stream().allMatch(a -> {
            boolean codeUnknown = a.code() == null || a.code().isBlank()
                    || "UNKNOWN".equalsIgnoreCase(a.code());
            boolean rootCauseUnknown = a.rootCause() == null || a.rootCause().isBlank()
                    || a.rootCause().toLowerCase().contains("unknown");
            return codeUnknown || rootCauseUnknown;
        });
    }

    /**
     * Checks if MCP XML trace result is empty or has no useful events.
     */
    private boolean isMcpXmlTraceUseless(SiconiaXmlTrace trace) {
        if (trace == null) return true;
        if (trace.events() == null || trace.events().isEmpty()) return true;
        // All events have empty/generic codes
        return trace.events().stream().allMatch(e ->
            e.code() == null || e.code().isBlank() || "UNKNOWN".equals(e.code())
        );
    }

    /**
     * Checks if MCP log analysis returned generic/empty data.
     */
    private boolean isMcpLogAnalysisUseless(LogAnalysis analysis) {
        if (analysis == null) return true;
        // If dominant layer is UNKNOWN and there are no issue categories, the analysis is useless
        if (analysis.dominantLayer() == LogLayer.UNKNOWN) return true;
        if (analysis.issueCategories() == null || analysis.issueCategories().isEmpty()) return true;
        return false;
    }

    private String extractCode(String input) {
        String trimmed = input == null ? "" : input.trim();
        java.util.regex.Matcher hexMatcher = java.util.regex.Pattern.compile("(0x|0X)[0-9A-Fa-f]+").matcher(trimmed);
        if (hexMatcher.find()
                && (hexMatcher.group().equals(trimmed)
                || (HEX_ALARM_CONTEXT.matcher(trimmed).find() && !DLMS_PROTOCOL_CONTEXT.matcher(trimmed).find()))) {
            return hexMatcher.group();
        }
        java.util.regex.Matcher namedMatcher = NAMED_ALARM_TOKEN.matcher(trimmed);
        if (namedMatcher.find()
                && (namedMatcher.group().equals(trimmed)
                || (NAMED_ALARM_CONTEXT.matcher(trimmed).find() && !DLMS_PROTOCOL_CONTEXT.matcher(trimmed).find()))) {
            return namedMatcher.group();
        }
        return trimmed;
    }

    private void persistNarrativeEvent(WorkflowState updated) {
        try {
            sessionNarrativeService.appendEvent(sessionEventService.buildSiconiaEvent(updated))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(PERSIST_TIMEOUT);
        } catch (Exception e) {
            log.warn("Session event append failed for sessionId={}", updated.sessionId(), e);
        }
    }

    private List<AlarmDecodeResult> decodeAlarmsInTrace(SiconiaXmlTrace trace) {
        List<AlarmDecodeResult> alarmResults = new ArrayList<>();
        if (trace != null && trace.events() != null) {
            for (XmlEvent event : trace.events()) {
                boolean alarmEvent = event.type() != null && "ALARM".equalsIgnoreCase(event.type());
                if (alarmEvent && event.code() != null && !event.code().isBlank()) {
                    try {
                        List<AlarmDecodeResult> decoded = alarmDecoder.decode(event.code());
                        if (decoded != null && !decoded.isEmpty()) {
                            alarmResults.addAll(decoded);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to decode alarm code '{}' from XML trace: {}", event.code(), e.getMessage());
                    }
                }
            }
        }
        return alarmResults.isEmpty() ? null : List.copyOf(alarmResults);
    }

    private SiconiaResult processXmlTrace(String raw, WorkflowState state) {
        SiconiaXmlTrace trace = xmlTraceParser.parse(raw);
        return new SiconiaResult(
                trace,
                decodeAlarmsInTrace(trace),
                null,
                InputClass.XML_TRACE.name(),
                buildProcessingMetadata(state, InputClass.XML_TRACE, trace, trace.parseErrors(), false)
        );
    }

    private SiconiaResult processAlarmCode(String code, WorkflowState state) {
        List<AlarmDecodeResult> alarms = alarmDecoder.decode(code);
        return new SiconiaResult(
                null,
                alarms,
                null,
                InputClass.ALARM_CODE.name(),
                buildProcessingMetadata(state, InputClass.ALARM_CODE, null, List.of(), false)
        );
    }

    private SiconiaResult processLogBlock(String raw, WorkflowState state) {
        LogAnalysis analysis = logClassifier.classify(raw);
        return new SiconiaResult(
                null,
                null,
                analysis,
                InputClass.LOG_BLOCK.name(),
                buildProcessingMetadata(state, InputClass.LOG_BLOCK, null, List.of(), false)
        );
    }

    private SiconiaProcessingMetadata buildProcessingMetadata(
            WorkflowState state,
            InputClass effectiveInputClass,
            SiconiaXmlTrace trace,
            List<String> parserWarnings,
            boolean mcpStructured
    ) {
        SiconiaInputNormalization normalization = state.siconiaNormalization();
        ParseProvenance provenance = normalization != null
                ? normalization.provenance()
                : ParseProvenance.STRUCTURED_DIRECT;
        String extractorNote = normalization != null ? normalization.extractorNote() : null;
        List<String> warnings = new ArrayList<>();
        if (normalization != null && normalization.warnings() != null) {
            warnings.addAll(normalization.warnings());
        }
        if (parserWarnings != null && !parserWarnings.isEmpty()) {
            warnings.addAll(parserWarnings);
        }
        if (trace != null && (trace.events() == null || trace.events().isEmpty())) {
            provenance = ParseProvenance.RAW_FALLBACK;
            warnings.add("Structured event extraction did not match a supported schema");
            if (extractorNote == null || extractorNote.isBlank()) {
                extractorNote = "valid XML interpreted from raw input";
            }
        } else if (mcpStructured && provenance == ParseProvenance.RAW_FALLBACK) {
            provenance = ParseProvenance.STRUCTURED_HEURISTIC;
        }

        return new SiconiaProcessingMetadata(
                effectiveInputClass,
                provenance,
                warnings,
                extractorNote
        );
    }

    // --- MCP Mapping Helpers ---

    private List<AlarmDecodeResult> mapMcpAlarmResult(JsonNode root) {
        try {
            // MCP might return a single object or an array.
            List<AlarmDecodeResult> list = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(item -> list.add(parseMcpAlarmItem(item)));
            } else {
                list.add(parseMcpAlarmItem(root));
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    private AlarmDecodeResult parseMcpAlarmItem(JsonNode item) {
        return new AlarmDecodeResult(
                item.path("code").asText("UNKNOWN"),
                parseSeverity(item.path("severity").asText()),
                item.path("root_cause").asText("Unknown cause (MCP)"),
                item.path("remediation").asText("Contact support"),
                parseAffectedComponent(item.path("affected_component").asText())
        );
    }

    private SiconiaXmlTrace mapMcpXmlTrace(JsonNode root, String rawXml) {
        try {
            List<XmlEvent> events = new ArrayList<>();
            root.path("events").forEach(e -> {
                events.add(new XmlEvent(
                    e.path("type").asText(),
                    e.path("code").asText(),
                    e.path("timestamp").asText(),
                    e.path("deviceId").asText(),
                    e.path("errorCode").asText()
                ));
            });
            List<String> errors = new ArrayList<>();
            root.path("parse_errors").forEach(e -> errors.add(e.asText()));
            return new SiconiaXmlTrace(events, errors, rawXml);
        } catch (Exception e) {
            return null;
        }
    }

    private LogAnalysis mapMcpLogAnalysis(JsonNode root) {
        try {
            // Try reading "dominant_layer" directly
            LogLayer layer = parseLogLayer(root.path("dominant_layer").asText());

            // Fallback: compute dominant layer from "layers" object
            // The MCP Python tool returns: {"layers": {"WAN": 30, "PLC": 20, ...}}
            if (layer == LogLayer.UNKNOWN) {
                JsonNode layers = root.path("layers");
                if (layers.isObject()) {
                    int best = -1;
                    Iterator<Map.Entry<String, JsonNode>> fields = layers.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        int count = entry.getValue().asInt(0);
                        if (count > best) {
                            best = count;
                            layer = parseLogLayer(entry.getKey());
                        }
                    }
                }
            }

            // Try reading "highest_severity" directly; fallback to "severities" object
            LogSeverity severity = parseLogSeverity(root.path("highest_severity").asText());
            // Fallback: look at "severities" object for the highest severity present
            // LogSeverity enum: ERROR(0), WARN(1), INFO(2), DEBUG(3) — lower ordinal = higher severity
            JsonNode severities = root.path("severities");
            if (severities.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = severities.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    int count = entry.getValue().asInt(0);
                    if (count > 0) {
                        LogSeverity s = parseLogSeverity(entry.getKey());
                        // Pick the severity with the lowest ordinal (highest severity)
                        if (s.ordinal() < severity.ordinal()) {
                            severity = s;
                        }
                    }
                }
            }

            // Map issue categories from MCP result if present
            Set<IssueCategory> categories = java.util.Collections.emptySet();
            JsonNode categoriesNode = root.path("issue_categories");
            if (categoriesNode.isArray()) {
                Set<IssueCategory> cats = new java.util.LinkedHashSet<>();
                for (JsonNode cat : categoriesNode) {
                    IssueCategory parsed = parseIssueCategory(cat.asText());
                    if (parsed != null) {
                        cats.add(parsed);
                    }
                }
                if (!cats.isEmpty()) {
                    categories = cats;
                }
            }
            // Fallback: derive categories from severities or layer info
            if (categories.isEmpty()) {
                if (severity == LogSeverity.ERROR) {
                    categories = java.util.Set.of(IssueCategory.CONNECTIVITY);
                } else if (layer == LogLayer.HES) {
                    categories = java.util.Set.of(IssueCategory.CONNECTIVITY);
                }
            }

            int count = root.path("total_lines").asInt(0);
            int errorCount = root.path("error_lines").asInt(0);
            return new LogAnalysis(layer, severity, categories, count, errorCount);
        } catch (Exception e) {
            return null;
        }
    }

    private IssueCategory parseIssueCategory(String s) {
        if (s == null) return null;
        try {
            return IssueCategory.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AlarmSeverity parseSeverity(String s) {
        if (s == null) return AlarmSeverity.INFO;
        try {
            return AlarmSeverity.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AlarmSeverity.INFO;
        }
    }

    private AffectedComponent parseAffectedComponent(String s) {
        if (s == null) return AffectedComponent.UNKNOWN;
        try {
            return AffectedComponent.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AffectedComponent.UNKNOWN;
        }
    }

    private LogLayer parseLogLayer(String s) {
        if (s == null) return LogLayer.UNKNOWN;
        try {
            return LogLayer.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return LogLayer.UNKNOWN;
        }
    }

    private LogSeverity parseLogSeverity(String s) {
        if (s == null) return LogSeverity.INFO;
        try {
            return LogSeverity.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return LogSeverity.INFO;
        }
    }
}
