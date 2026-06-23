package com.company.dlms.workflow;

import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.infrastructure.llm.OllamaStreamingClient;
import com.company.dlms.infrastructure.security.OutputFilter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TurnSynthesisPlannerService {

    private static final Duration SYNTHESIS_TIMEOUT = Duration.ofSeconds(25);

    private final OllamaStreamingClient ollamaStreamingClient;
    private final OutputFilter outputFilter;

    public TurnSynthesisPlannerService(
            OllamaStreamingClient ollamaStreamingClient,
            OutputFilter outputFilter
    ) {
        this.ollamaStreamingClient = Objects.requireNonNull(ollamaStreamingClient, "ollamaStreamingClient");
        this.outputFilter = Objects.requireNonNull(outputFilter, "outputFilter");
    }

    public Mono<TurnSynthesisResult> synthesize(
            @NonNull TurnArtifactExtraction extraction,
            @NonNull List<WorkflowState> artifactStates
    ) {
        if (artifactStates.isEmpty()) {
            return Mono.just(new TurnSynthesisResult("", false));
        }
        if (extraction.turnInstruction() == null || extraction.turnInstruction().isBlank()) {
            return Mono.just(new TurnSynthesisResult(deterministicBatchSummary(artifactStates), false));
        }

        String deterministicInstructionSummary = deterministicInstructionSummary(
                extraction.turnInstruction(),
                artifactStates
        );
        if (deterministicInstructionSummary != null) {
            return Mono.just(new TurnSynthesisResult(deterministicInstructionSummary, false));
        }

        String systemPrompt = """
                You are summarizing a multi-artifact DLMS/SICONIA turn.
                Use only the provided structured observations.
                Never parse or reinterpret raw protocol bytes.
                Never invent fields, protocol steps, or returned values that are not present in the observations.
                Start with a short direct summary of the user's request.
                Then connect the artifacts in plain technical prose.
                Keep the response concise and organized.
                Do not emit a Sources footer.
                """;
        String prompt = buildSynthesisPrompt(extraction, artifactStates);
        String fallbackSummary = deterministicFallbackSummary(extraction, artifactStates);

        Flux<String> stream = ollamaStreamingClient.stream(systemPrompt, prompt);
        if (stream == null) {
            return Mono.just(new TurnSynthesisResult(fallbackSummary, false));
        }

        return stream
                .take(SYNTHESIS_TIMEOUT)
                .collectList()
                .map(tokens -> String.join("", tokens))
                .map(this::sanitizeSummary)
                .map(summary -> summary == null || summary.isBlank()
                        ? new TurnSynthesisResult(fallbackSummary, false)
                        : new TurnSynthesisResult(summary, true))
                .onErrorReturn(new TurnSynthesisResult(fallbackSummary, false));
    }

    private String buildSynthesisPrompt(TurnArtifactExtraction extraction, List<WorkflowState> artifactStates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Turn instruction:\n");
        sb.append(extraction.turnInstruction().trim()).append("\n\n");
        sb.append("Artifacts:\n");
        for (int i = 0; i < artifactStates.size(); i += 1) {
            sb.append("Artifact ").append(i + 1).append(":\n");
            sb.append(summarizeArtifact(artifactStates.get(i))).append("\n\n");
        }
        sb.append("Write one short overall synthesis. Do not duplicate every field verbatim.\n");
        return sb.toString();
    }

    private String summarizeArtifact(WorkflowState state) {
        List<String> parts = new ArrayList<>();
        parts.add("Input class: " + (state.inputClass() == null ? "QUERY" : state.inputClass().name()));
        parts.add("Intent: " + (state.intent() == null ? "UNKNOWN" : state.intent().name()));

        if (state.decodeResult() instanceof DecodeResult dr) {
            if (dr.hdlcFrame() != null) {
                String frameSummary = dr.hdlcFrame().frameType().name();
                if (dr.hdlcFrame().uFrameType() != null) {
                    frameSummary += " " + dr.hdlcFrame().uFrameType().name();
                }
                if (dr.hdlcFrame().sFrameType() != null) {
                    frameSummary += " " + dr.hdlcFrame().sFrameType().name();
                }
                parts.add("Decode: " + frameSummary);
                parts.add("FCS valid: " + dr.hdlcFrame().fcsValid());
            } else if (dr.apduType() != null) {
                parts.add("APDU type: " + dr.apduType().name());
            }
            if (dr.obisResolutions() != null && !dr.obisResolutions().isEmpty()) {
                var obis = dr.obisResolutions().getFirst();
                parts.add("OBIS: " + obis.obis() + " = " + obis.description());
            }
            if (dr.parseErrors() != null && !dr.parseErrors().isEmpty()) {
                parts.add("Parse issues: " + String.join("; ", dr.parseErrors()));
            }
        }

        if (state.siconiaResult() instanceof SiconiaResult sr) {
            if (sr.alarmResults() != null && !sr.alarmResults().isEmpty()) {
                parts.add("Alarm count: " + sr.alarmResults().size());
                parts.add("Alarm summary: " + sr.alarmResults().stream()
                        .map(alarm -> alarm.code() + " " + alarm.severity())
                        .collect(Collectors.joining(", ")));
            }
            if (sr.logAnalysis() != null) {
                parts.add("Log layer: " + sr.logAnalysis().dominantLayer());
                parts.add("Highest severity: " + sr.logAnalysis().highestSeverity());
            }
            if (sr.xmlTrace() != null && sr.xmlTrace().events() != null) {
                parts.add("XML events: " + sr.xmlTrace().events().size());
            }
        }

        if (state.errors() != null && !state.errors().isEmpty()) {
            parts.add("Errors: " + String.join("; ", state.errors()));
        }
        return parts.stream().collect(Collectors.joining("\n"));
    }

    private String deterministicBatchSummary(List<WorkflowState> artifactStates) {
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < artifactStates.size(); i += 1) {
            WorkflowState state = artifactStates.get(i);
            summaries.add("- Artifact " + (i + 1) + ": " + deterministicArtifactLabel(state));
        }
        return "I analyzed " + artifactStates.size() + " artifact" + (artifactStates.size() == 1 ? "" : "s") + ":\n\n"
                + String.join("\n", summaries);
    }

    private String deterministicInstructionSummary(String instruction, List<WorkflowState> artifactStates) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }
        String normalized = instruction.toLowerCase(Locale.ROOT);
        if (!looksLikeCrossArtifactSummaryInstruction(normalized)) {
            return null;
        }

        List<String> inventory = new ArrayList<>();
        for (int i = 0; i < artifactStates.size(); i += 1) {
            inventory.add("- Artifact " + (i + 1) + ": " + deterministicArtifactLabel(artifactStates.get(i))
                    + " - " + artifactRoleSummary(artifactStates.get(i)));
        }

        return "These artifacts are related, but they do not all describe the same layer or role.\n\n"
                + String.join("\n", inventory)
                + "\n\n"
                + crossArtifactContextSentence(artifactStates);
    }

    private boolean looksLikeCrossArtifactSummaryInstruction(String normalizedInstruction) {
        return normalizedInstruction.contains("compare")
                || normalizedInstruction.contains("difference")
                || normalizedInstruction.contains("contrast")
                || normalizedInstruction.contains("tie")
                || normalizedInstruction.contains("related")
                || normalizedInstruction.contains("relationship")
                || normalizedInstruction.contains("context")
                || normalizedInstruction.contains("sequence")
                || normalizedInstruction.contains("flow")
                || normalizedInstruction.contains("fit together");
    }

    private String deterministicFallbackSummary(TurnArtifactExtraction extraction, List<WorkflowState> artifactStates) {
        String deterministicInstructionSummary = deterministicInstructionSummary(extraction.turnInstruction(), artifactStates);
        if (deterministicInstructionSummary != null) {
            return deterministicInstructionSummary;
        }
        return deterministicBatchSummary(artifactStates);
    }

    private String deterministicArtifactLabel(WorkflowState state) {
        if (state.decodeResult() instanceof DecodeResult dr) {
            if (dr.hdlcFrame() != null) {
                String subtype = dr.hdlcFrame().uFrameType() != null
                        ? dr.hdlcFrame().uFrameType().name()
                        : dr.hdlcFrame().sFrameType() != null
                            ? dr.hdlcFrame().sFrameType().name()
                            : null;
                return dr.hdlcFrame().frameType().name()
                        + (subtype == null ? "" : " (" + subtype + ")");
            }
            DlmsNormalizedKind normalizedKind = dr.processingMetadata() == null ? null : dr.processingMetadata().normalizedKind();
            if (normalizedKind == DlmsNormalizedKind.AXDR_HEX) {
                return summarizeAxdrArtifact(dr);
            }
            if (dr.apduType() != null && dr.apduType() != com.company.dlms.domain.decoder.ApduType.UNKNOWN) {
                String obisSuffix = firstObis(dr)
                        .map(obis -> " for OBIS `" + obis + "`")
                        .orElse("");
                return dr.apduType().name() + obisSuffix;
            }
            if (dr.obisResolutions() != null && !dr.obisResolutions().isEmpty()) {
                return "OBIS `" + dr.obisResolutions().getFirst().obis() + "`";
            }
        }
        if (state.siconiaResult() instanceof SiconiaResult sr) {
            if (sr.alarmResults() != null && !sr.alarmResults().isEmpty()) {
                return sr.alarmResults().size() + " alarm result"
                        + (sr.alarmResults().size() == 1 ? "" : "s");
            }
            if (sr.logAnalysis() != null) {
                return sr.logAnalysis().dominantLayer() + " log analysis";
            }
            if (sr.xmlTrace() != null) {
                int count = sr.xmlTrace().events() == null ? 0 : sr.xmlTrace().events().size();
                return "XML trace with " + count + " event" + (count == 1 ? "" : "s");
            }
        }
        return "processed";
    }

    private String summarizeAxdrArtifact(DecodeResult dr) {
        if (dr.axdrTree() instanceof com.company.dlms.domain.decoder.AxdrBoolean bool) {
            return "AXDR boolean `" + bool.value() + "`";
        }
        if (dr.axdrTree() instanceof com.company.dlms.domain.decoder.AxdrNull) {
            return "AXDR `null-data`";
        }
        return "AXDR payload";
    }

    private String artifactRoleSummary(WorkflowState state) {
        if (state.decodeResult() instanceof DecodeResult dr) {
            if (dr.hdlcFrame() != null) {
                if (dr.hdlcFrame().frameType() == com.company.dlms.domain.decoder.FrameType.U_FRAME
                        && dr.hdlcFrame().uFrameType() == com.company.dlms.domain.decoder.UFrameType.SNRM) {
                    return "HDLC link-layer setup before normal DLMS association traffic";
                }
                if (dr.hdlcFrame().frameType() == com.company.dlms.domain.decoder.FrameType.S_FRAME) {
                    return "HDLC supervisory flow-control or acknowledgement traffic";
                }
                if (dr.hdlcFrame().frameType() == com.company.dlms.domain.decoder.FrameType.I_FRAME) {
                    return "framed DLMS application traffic inside an HDLC session";
                }
                return "HDLC link-layer control or framing";
            }
            DlmsNormalizedKind normalizedKind = dr.processingMetadata() == null ? null : dr.processingMetadata().normalizedKind();
            if (normalizedKind == DlmsNormalizedKind.AXDR_HEX) {
                return "a standalone AXDR value without an HDLC or APDU envelope";
            }
            if (dr.apduType() != null && dr.apduType() == com.company.dlms.domain.decoder.ApduType.GET_RESPONSE) {
                return "application-layer response data returned by the server";
            }
            if (dr.apduType() != null && dr.apduType() != com.company.dlms.domain.decoder.ApduType.UNKNOWN) {
                return "application-layer DLMS APDU content";
            }
            if (normalizedKind == DlmsNormalizedKind.OBIS_QUERY) {
                return "a deterministic object-identifier lookup";
            }
        }
        if (state.siconiaResult() instanceof SiconiaResult sr) {
            if (sr.alarmResults() != null && !sr.alarmResults().isEmpty()) {
                return "an operational SICONIA alarm diagnosis";
            }
            if (sr.logAnalysis() != null) {
                return "a layered SICONIA log analysis result";
            }
            if (sr.xmlTrace() != null) {
                return "a structured SICONIA XML trace analysis";
            }
        }
        return "a structured artifact that should be read on its own evidence";
    }

    private String crossArtifactContextSentence(List<WorkflowState> artifactStates) {
        boolean hasFrame = artifactStates.stream().anyMatch(this::isFrameArtifact);
        boolean hasApdu = artifactStates.stream().anyMatch(this::isApduArtifact);
        boolean hasAxdr = artifactStates.stream().anyMatch(this::isAxdrArtifact);
        boolean hasSiconia = artifactStates.stream().anyMatch(this::isSiconiaArtifact);

        if (hasFrame && hasApdu && hasAxdr) {
            return "In context, these artifacts span three levels: HDLC link setup, a standalone AXDR data value, and an application-layer APDU response. They should be correlated, not merged into a single decode.";
        }
        if (hasFrame && hasApdu) {
            return "In context, the frame artifacts belong to HDLC transport state, while the APDU artifact belongs to the DLMS application layer that runs after the link is available.";
        }
        if (hasSiconia && !hasFrame && !hasApdu && !hasAxdr) {
            return "In context, these are operational troubleshooting artifacts rather than one protocol sequence, so compare them by component, severity, and evidence type.";
        }
        if (artifactStates.stream().allMatch(this::isFrameArtifact)) {
            return "In context, these are all HDLC artifacts, so the useful comparison is subtype, trust level, and where each frame sits in the session sequence.";
        }
        if (artifactStates.stream().allMatch(this::isApduArtifact)) {
            return "In context, these are all DLMS application messages, so compare them by APDU role, returned object identity, and whether they carry values or control information.";
        }
        return "In context, treat each artifact as a separate structured result first, then compare their layer, role, and trust level rather than assuming they are one continuous payload.";
    }

    private boolean isFrameArtifact(WorkflowState state) {
        return state.decodeResult() instanceof DecodeResult dr && dr.hdlcFrame() != null;
    }

    private boolean isApduArtifact(WorkflowState state) {
        return state.decodeResult() instanceof DecodeResult dr
                && dr.hdlcFrame() == null
                && dr.apduType() != null
                && dr.apduType() != com.company.dlms.domain.decoder.ApduType.UNKNOWN;
    }

    private boolean isAxdrArtifact(WorkflowState state) {
        return state.decodeResult() instanceof DecodeResult dr
                && dr.processingMetadata() != null
                && dr.processingMetadata().normalizedKind() == DlmsNormalizedKind.AXDR_HEX;
    }

    private boolean isSiconiaArtifact(WorkflowState state) {
        return state.siconiaResult() != null;
    }

    private java.util.Optional<String> firstObis(DecodeResult dr) {
        if (dr == null || dr.obisResolutions() == null || dr.obisResolutions().isEmpty()) {
            return java.util.Optional.empty();
        }
        ObisResolution resolution = dr.obisResolutions().getFirst();
        return java.util.Optional.ofNullable(resolution.obis());
    }

    private String sanitizeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String stripped = value
                .replaceAll("(?is)<think>.*?</think>", "")
                .replaceFirst("(?is)(?:\\n{1,2}|(?<=[.!?]))\\s*Sources:\\s*.+$", "")
                .trim();
        OutputFilter.FilterResult filtered = outputFilter.filter(stripped);
        if (filtered.blocked() || filtered.content() == null) {
            return "";
        }
        return filtered.content().trim();
    }

    public List<ToolTraceEntry> aggregateToolTrace(List<WorkflowState> artifactStates) {
        List<ToolTraceEntry> traces = new ArrayList<>();
        for (int i = 0; i < artifactStates.size(); i += 1) {
            WorkflowState state = artifactStates.get(i);
            if (state.toolTrace() == null || state.toolTrace().isEmpty()) {
                continue;
            }
            for (ToolTraceEntry entry : state.toolTrace()) {
                traces.add(new ToolTraceEntry(
                        entry.toolName(),
                        "Artifact " + (i + 1) + ": " + entry.summary(),
                        entry.authoritative(),
                        entry.provenance()
                ));
            }
        }
        return traces;
    }

    public record TurnSynthesisResult(
            String text,
            boolean plannerUsed
    ) {}
}
