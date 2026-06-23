package com.company.dlms.workflow;

import com.company.dlms.domain.CasualQueryClassifier;
import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.siconia.SiconiaResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FollowUpResolver {

    private static final Pattern MEANING_PATTERN = Pattern.compile(
            "\\bwhat\\s+does\\s+(that|this|it)\\s+mean\\b|\\bwhat\\s+does\\s+that\\s+indicate\\b|\\bexplain\\s+that\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FAILURE_PATTERN = Pattern.compile(
            "\\bwhy\\s+did\\s+(that|this|it)\\s+fail\\b|\\bwhy\\s+did\\s+it\\s+fail\\b|\\bwhat\\s+failed\\b|\\bwhy\\s+is\\s+(that|this|it)\\s+invalid\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONNECTION_ROLE_PATTERN = Pattern.compile(
            "\\bwhat\\s+type\\s+of\\s+connection\\b|\\bwhat\\s+role\\s+does\\s+that\\s+frame\\b|\\bwhat\\s+is\\s+it\\s+trying\\s+to\\s+do\\b|"
                    + "\\bwhat\\s+does\\s+that\\s+frame\\s+do\\b|\\bwhy\\s+was\\s+that\\s+frame\\s+sent\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LAST_ENTITY_RECALL_PATTERN = Pattern.compile(
            "\\bwhat\\s+obis\\s+code\\s+was\\s+in\\b|\\bwhat\\s+was\\s+the\\s+last\\s+obis\\b|\\bwhat\\s+object\\s+was\\s+returned\\b|"
                    + "\\blast\\s+obis\\b|\\bwhat\\s+obis\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ARTIFACT_REFERENCE_PATTERN = Pattern.compile("\\bartifact\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRAME_PATTERN = Pattern.compile("(?i)7E(?:[0-9A-F]{2}|[\\s:]){5,}7E");
    private static final Pattern OBIS_PATTERN = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){5}\\b");
    private static final Pattern DIRECT_ALARM_PATTERN = Pattern.compile("(?i)^\\s*0x[0-9a-f]{2,8}\\s*$");
    private static final Pattern HEX_PAYLOAD_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])(?:0x)?(?:(?=[0-9A-F]*\\d)[0-9A-F]{4,}|(?:[0-9A-F]{2}(?:[\\s:][0-9A-F]{2})+))(?![A-Z0-9])"
    );
    private static final Pattern XML_MARKER_PATTERN = Pattern.compile("(?i)<\\??(?:xml|[a-z_][\\w:-]*)");
    private static final Pattern LOG_PATTERN = Pattern.compile("(?i)\\[[A-Z_]+]\\s+(TRACE|DEBUG|INFO|WARN|ERROR|CRITICAL|FATAL)\\b");

    public boolean isFollowUpQuestion(String rawInput) {
        if (containsCurrentTurnStructuredEvidence(rawInput)) {
            return false;
        }
        String normalizedInput = normalizeRoutingInput(rawInput);
        if (normalizedInput.isBlank()) {
            return false;
        }
        return isFollowUpQuestionNormalized(normalizedInput);
    }

    public boolean hasResolvableSessionContext(WorkflowState state) {
        return resolve(state).map(FollowUpResolution::resolvedFromContext).orElse(false);
    }

    public Optional<FollowUpResolution> resolve(WorkflowState state) {
        String rawInput = state == null ? "" : state.rawInput();
        if (state == null || containsCurrentTurnStructuredEvidence(rawInput)) {
            return Optional.empty();
        }
        String normalizedInput = normalizeRoutingInput(rawInput);
        if (normalizedInput.isBlank() || !isFollowUpQuestionNormalized(normalizedInput)) {
            return Optional.empty();
        }

        Matcher artifactReference = ARTIFACT_REFERENCE_PATTERN.matcher(normalizedInput);
        if (artifactReference.find()) {
            return Optional.of(resolveArtifactReference(state, artifactReference));
        }
        if (CasualQueryClassifier.isPreviousFrameRecallQuestion(normalizedInput)) {
            return Optional.of(resolvePreviousFrameType(state));
        }
        if (LAST_ENTITY_RECALL_PATTERN.matcher(normalizedInput).find()) {
            return Optional.of(resolveLastEntityRecall(state));
        }
        if (CONNECTION_ROLE_PATTERN.matcher(normalizedInput).find()) {
            return Optional.of(resolveConnectionRole(state));
        }
        if (FAILURE_PATTERN.matcher(normalizedInput).find()) {
            return Optional.of(resolveFailureReason(state));
        }
        if (MEANING_PATTERN.matcher(normalizedInput).find()) {
            return Optional.of(resolveMeaning(state));
        }
        return Optional.empty();
    }

    private String normalizeRoutingInput(String rawInput) {
        return NaturalLanguageRoutingNormalizer.normalize(rawInput);
    }

    private boolean isFollowUpQuestionNormalized(String normalizedInput) {
        return CasualQueryClassifier.isPreviousFrameRecallQuestion(normalizedInput)
                || ARTIFACT_REFERENCE_PATTERN.matcher(normalizedInput).find()
                || CONNECTION_ROLE_PATTERN.matcher(normalizedInput).find()
                || LAST_ENTITY_RECALL_PATTERN.matcher(normalizedInput).find()
                || MEANING_PATTERN.matcher(normalizedInput).find()
                || FAILURE_PATTERN.matcher(normalizedInput).find();
    }

    private boolean containsCurrentTurnStructuredEvidence(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return false;
        }
        String input = rawInput.trim();
        return FRAME_PATTERN.matcher(input).find()
                || OBIS_PATTERN.matcher(input).find()
                || DIRECT_ALARM_PATTERN.matcher(input).matches()
                || XML_MARKER_PATTERN.matcher(input).find()
                || LOG_PATTERN.matcher(input).find()
                || HEX_PAYLOAD_PATTERN.matcher(input).find();
    }

    private FollowUpResolution resolvePreviousFrameType(WorkflowState state) {
        Optional<SessionEvent> currentDecodeFrameEvent = currentDecodeFrameEvent(state);
        Optional<SessionEvent> lastFrameNarrativeEvent = latestFrameNarrativeEvent(state);

        String frameLabel = currentFrameLabel(state)
                .or(() -> currentDecodeFrameEvent.map(SessionEvent::apduType))
                .or(() -> lastFrameNarrativeEvent.map(SessionEvent::apduType))
                .filter(this::isUsefulFrameLabel)
                .orElse(null);

        if (frameLabel == null) {
            return new FollowUpResolution(
                    FollowUpKind.PREVIOUS_FRAME_TYPE,
                    noContextAnswer("previous decoded frame"),
                    false
            );
        }

        String friendlyType = humanizeFrameLabel(frameLabel);
        String associationState = currentAssociationState(state)
                .or(() -> currentDecodeFrameEvent.map(SessionEvent::associationState))
                .or(() -> lastFrameNarrativeEvent.map(SessionEvent::associationState))
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("What happened: The last decoded frame in this conversation was ")
                .append(friendlyType)
                .append(".\n");
        sb.append("Can I trust it: Yes. This comes from the session state captured from the previous deterministic decode.\n");
        if (associationState != null && !associationState.isBlank() && !"UNKNOWN".equalsIgnoreCase(associationState)) {
            sb.append("Next step: The session state after that frame was ")
                    .append(associationState)
                    .append(". Ask for the role of the frame if you want more protocol detail.\n");
        } else {
            sb.append("Next step: Ask for the role of that frame or send another payload if you want a deeper decode.\n");
        }
        return new FollowUpResolution(FollowUpKind.PREVIOUS_FRAME_TYPE, sb.toString(), true);
    }

    private FollowUpResolution resolveMeaning(WorkflowState state) {
        if (state.decodeResult() instanceof DecodeResult decodeResult) {
            String answer = meaningFromDecode(decodeResult);
            if (answer != null) {
                return new FollowUpResolution(FollowUpKind.PREVIOUS_MEANING, answer, true);
            }
        }
        if (state.siconiaResult() != null) {
            String answer = meaningFromSiconia(state.siconiaResult());
            if (answer != null) {
                return new FollowUpResolution(FollowUpKind.PREVIOUS_MEANING, answer, true);
            }
        }

        Optional<SessionEvent> latest = latestNarrativeEvent(state);
        if (latest.isPresent()) {
            String answer = meaningFromNarrative(latest.get());
            if (answer != null) {
                return new FollowUpResolution(FollowUpKind.PREVIOUS_MEANING, answer, true);
            }
        }

        return new FollowUpResolution(
                FollowUpKind.PREVIOUS_MEANING,
                noContextAnswer("previous structured result"),
                false
        );
    }

    private FollowUpResolution resolveConnectionRole(WorkflowState state) {
        Optional<SessionEvent> currentDecodeFrameEvent = currentDecodeFrameEvent(state);
        Optional<SessionEvent> lastFrameNarrativeEvent = latestFrameNarrativeEvent(state);

        String frameLabel = currentFrameLabel(state)
                .or(() -> currentDecodeFrameEvent.map(SessionEvent::apduType))
                .or(() -> lastFrameNarrativeEvent.map(SessionEvent::apduType))
                .filter(this::isUsefulFrameLabel)
                .orElse(null);
        if (frameLabel == null && state.decodeResult() instanceof DecodeResult decodeResult
                && decodeResult.apduType() != null
                && decodeResult.apduType() != ApduType.UNKNOWN) {
            frameLabel = decodeResult.apduType().name();
        }
        if (frameLabel == null) {
            return new FollowUpResolution(
                    FollowUpKind.CONNECTION_ROLE,
                    noContextAnswer("previous connection role"),
                    false
            );
        }

        String associationState = currentAssociationState(state)
                .or(() -> currentDecodeFrameEvent.map(SessionEvent::associationState))
                .or(() -> lastFrameNarrativeEvent.map(SessionEvent::associationState))
                .orElse(null);

        String answer;
        if (frameLabel.startsWith("U_FRAME")) {
            String subtype = extractSubtype(frameLabel);
            if ("SNRM".equalsIgnoreCase(subtype)) {
                answer = "What happened: The last decoded frame was U-frame (SNRM), which is an HDLC link-layer setup request.\n"
                        + "Can I trust it: Yes. This comes from the previous deterministic decode and the stored session state.\n"
                        + "Next step: It is establishing the HDLC link layer. The peer is being asked to enter normal response mode so the session can move into "
                        + (associationState == null || associationState.isBlank() ? "link setup" : associationState)
                        + " and then continue to association APDUs such as AARQ/AARE.\n";
            } else if ("UA".equalsIgnoreCase(subtype)) {
                answer = "What happened: The last decoded frame was U-frame (UA), which acknowledges a prior HDLC link-state request.\n"
                        + "Can I trust it: Yes. UA is a deterministic control-frame subtype from the previous decode.\n"
                        + "Next step: It confirms the peer accepted the requested link-layer transition, so you can verify whether the session moved into "
                        + (associationState == null || associationState.isBlank() ? "the expected next state" : associationState)
                        + " before DLMS association traffic continues.\n";
            } else if ("DISC".equalsIgnoreCase(subtype) || "DM".equalsIgnoreCase(subtype)) {
                answer = "What happened: The last decoded frame was " + humanizeFrameLabel(frameLabel) + ", which is part of HDLC link release or disconnected-mode handling.\n"
                        + "Can I trust it: Yes. This reflects deterministic link-layer control state, not a documentation guess.\n"
                        + "Next step: It is not establishing a new connection; it is signalling teardown or refusal of normal response mode communication.\n";
            } else {
                answer = "What happened: The last decoded frame was " + humanizeFrameLabel(frameLabel) + ", an HDLC unnumbered control frame.\n"
                        + "Can I trust it: Yes. Its role comes from the previous deterministic frame decode.\n"
                        + "Next step: This is controlling HDLC link state rather than opening a TCP socket or carrying application data.\n";
            }
            return new FollowUpResolution(FollowUpKind.CONNECTION_ROLE, answer, true);
        }

        if (frameLabel.startsWith("S_FRAME")) {
            answer = "What happened: The last decoded frame was " + humanizeFrameLabel(frameLabel) + ", which manages HDLC flow-control or acknowledgement state.\n"
                    + "Can I trust it: Yes. Supervisory frames are deterministic link-layer control messages.\n"
                    + "Next step: It is not establishing a new association by itself; it is managing receive state for an existing HDLC session.\n";
            return new FollowUpResolution(FollowUpKind.CONNECTION_ROLE, answer, true);
        }

        if (frameLabel.contains("AARQ")) {
            answer = "What happened: The last decoded message was AARQ, the DLMS/COSEM Association Request APDU.\n"
                    + "Can I trust it: Yes. That comes from the previous deterministic decode.\n"
                    + "Next step: It is establishing the application association on top of an already available link layer, not opening a separate physical or TCP connection.\n";
            return new FollowUpResolution(FollowUpKind.CONNECTION_ROLE, answer, true);
        }
        if (frameLabel.contains("AARE")) {
            answer = "What happened: The last decoded message was AARE, the DLMS/COSEM Association Response APDU.\n"
                    + "Can I trust it: Yes. That role comes from the previous deterministic decode.\n"
                    + "Next step: It is confirming or rejecting the application association rather than creating a new transport connection.\n";
            return new FollowUpResolution(FollowUpKind.CONNECTION_ROLE, answer, true);
        }
        if (frameLabel.contains("GET_RESPONSE")) {
            answer = "What happened: The last decoded message was GET_RESPONSE, which is application data returned over an existing DLMS association.\n"
                    + "Can I trust it: Yes. That APDU type comes from the previous deterministic decode.\n"
                    + "Next step: No new connection is being established here; inspect the returned AXDR or OBIS payload details instead.\n";
            return new FollowUpResolution(FollowUpKind.CONNECTION_ROLE, answer, true);
        }

        return new FollowUpResolution(
                FollowUpKind.CONNECTION_ROLE,
                "What happened: The last decoded message was " + frameLabel + ".\n"
                        + "Can I trust it: Yes. This comes from the stored session context, not from a documentation lookup.\n"
                        + "Next step: It represents protocol state within the current DLMS/COSEM session rather than a fresh network connection unless the surrounding frames show a new setup exchange.\n",
                true
        );
    }

    private FollowUpResolution resolveLastEntityRecall(WorkflowState state) {
        String obis = currentOrLastObis(state);
        if (obis == null) {
            return new FollowUpResolution(
                    FollowUpKind.LAST_ENTITY_RECALL,
                    noContextAnswer("previous OBIS code"),
                    false
            );
        }

        String description = currentOrLastObisDescription(state, obis);
        StringBuilder answer = new StringBuilder();
        answer.append("What happened: The last OBIS code in this conversation was ").append(obis);
        if (description != null && !description.isBlank()) {
            answer.append(" (").append(description).append(")");
        }
        answer.append(".\n");
        answer.append("Can I trust it: Yes. This comes from the previous deterministic decode and stored session state.\n");
        answer.append("Next step: Ask for the interface class, unit, scaler, or the enclosing APDU if you want more detail about that object.\n");
        return new FollowUpResolution(FollowUpKind.LAST_ENTITY_RECALL, answer.toString(), true);
    }

    private FollowUpResolution resolveFailureReason(WorkflowState state) {
        if (state.decodeResult() instanceof DecodeResult decodeResult) {
            String answer = failureFromDecode(decodeResult);
            if (answer != null) {
                return new FollowUpResolution(FollowUpKind.PREVIOUS_FAILURE_REASON, answer, true);
            }
        }
        if (state.siconiaResult() != null) {
            String answer = failureFromSiconia(state.siconiaResult());
            if (answer != null) {
                return new FollowUpResolution(FollowUpKind.PREVIOUS_FAILURE_REASON, answer, true);
            }
        }

        Optional<SessionEvent> latest = latestNarrativeEvent(state);
        if (latest.isPresent()) {
            String answer = failureFromNarrative(latest.get());
            if (answer != null) {
                return new FollowUpResolution(FollowUpKind.PREVIOUS_FAILURE_REASON, answer, true);
            }
        }

        return new FollowUpResolution(
                FollowUpKind.PREVIOUS_FAILURE_REASON,
                noContextAnswer("previous failure"),
                false
        );
    }

    private FollowUpResolution resolveArtifactReference(WorkflowState state, Matcher artifactReference) {
        int artifactIndex;
        try {
            artifactIndex = Integer.parseInt(artifactReference.group(1));
        } catch (NumberFormatException ex) {
            return new FollowUpResolution(
                    FollowUpKind.ARTIFACT_RECALL,
                    noContextAnswer("previous artifact result"),
                    false
            );
        }

        List<ArtifactResultPayload> artifactResults = state.recentArtifactResults();
        if (artifactResults == null || artifactResults.isEmpty()) {
            return new FollowUpResolution(
                    FollowUpKind.ARTIFACT_RECALL,
                    noContextAnswer("previous artifact result"),
                    false
            );
        }
        if (artifactIndex < 1 || artifactIndex > artifactResults.size()) {
            return new FollowUpResolution(
                    FollowUpKind.ARTIFACT_RECALL,
                    "What happened: I only have " + artifactResults.size() + " artifact result"
                            + (artifactResults.size() == 1 ? "" : "s")
                            + " recorded for the last multi-artifact turn.\n"
                            + "Can I trust it: Yes. That count comes from the stored batch result for this conversation.\n"
                            + "Next step: Ask about an artifact number between 1 and " + artifactResults.size() + ".\n",
                    true
            );
        }

        ArtifactResultPayload artifact = artifactResults.get(artifactIndex - 1);
        String label = artifactLabel(artifact);
        String detail = artifact.explanation() == null ? "" : artifact.explanation().trim();

        StringBuilder answer = new StringBuilder();
        answer.append("What happened: Artifact ").append(artifactIndex).append(" in the last multi-artifact turn");
        if (hasText(label)) {
            answer.append(" decoded as ").append(label);
        }
        answer.append(".\n");
        answer.append("Can I trust it: Yes. This comes from the stored structured result for that artifact.\n");
        if (hasText(detail)) {
            answer.append("Detail:\n").append(detail).append("\n");
        } else {
            answer.append("Next step: Reopen the structured artifact result if you want the full decode details for that artifact.\n");
        }
        return new FollowUpResolution(FollowUpKind.ARTIFACT_RECALL, answer.toString(), true);
    }

    private Optional<String> currentFrameLabel(WorkflowState state) {
        if (!(state.decodeResult() instanceof DecodeResult decodeResult) || decodeResult.hdlcFrame() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(frameLabelFromDecode(decodeResult));
    }

    private Optional<String> currentAssociationState(WorkflowState state) {
        if (state.associationState() == null || state.associationState().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(state.associationState());
    }

    private Optional<SessionEvent> latestNarrativeEvent(WorkflowState state) {
        List<SessionEvent> narrative = state.narrativeContext();
        if (narrative == null || narrative.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(narrative.getLast());
    }

    private Optional<SessionEvent> latestFrameNarrativeEvent(WorkflowState state) {
        List<SessionEvent> narrative = state.narrativeContext();
        if (narrative == null || narrative.isEmpty()) {
            return Optional.empty();
        }
        for (int i = narrative.size() - 1; i >= 0; i--) {
            SessionEvent event = narrative.get(i);
            if (event != null && isFrameNarrativeLabel(event.apduType())) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    private Optional<SessionEvent> currentDecodeFrameEvent(WorkflowState state) {
        return currentFrameLabel(state)
                .filter(this::isFrameNarrativeLabel)
                .map(label -> new SessionEvent(
                        state.sessionId(),
                        java.time.Instant.EPOCH,
                        0,
                        label,
                        "COMPLETE",
                        state.associationState(),
                        state.lastObis(),
                        state.lastIc(),
                        List.of(),
                        List.of(),
                        List.of()
                ));
    }

    private boolean isUsefulFrameLabel(String value) {
        return value != null && !value.isBlank() && !"UNKNOWN".equalsIgnoreCase(value);
    }

    private boolean isFrameNarrativeLabel(String value) {
        return value != null && (value.startsWith("U_FRAME") || value.startsWith("S_FRAME") || value.startsWith("I_FRAME"));
    }

    private String artifactLabel(ArtifactResultPayload artifact) {
        if (artifact == null) {
            return null;
        }
        if (artifact.decodeResult() instanceof Map<?, ?> decodeMap) {
            Map<?, ?> hdlcFrame = mapValue(decodeMap, "hdlcFrame");
            if (hdlcFrame != null) {
                String frameType = stringValue(hdlcFrame, "frameType");
                String subtype = stringValue(hdlcFrame, "uFrameType");
                if (!hasText(subtype)) {
                    subtype = stringValue(hdlcFrame, "sFrameType");
                }
                return hasText(subtype) ? frameType + " (" + subtype + ")" : frameType;
            }

            String apduType = stringValue(decodeMap, "apduType");
            if (hasText(apduType) && !"UNKNOWN".equalsIgnoreCase(apduType)) {
                String obis = firstArtifactObis(decodeMap);
                return hasText(obis) ? apduType + " for OBIS `" + obis + "`" : apduType;
            }

            Map<?, ?> axdrTree = mapValue(decodeMap, "axdrTree");
            if (axdrTree != null) {
                String type = stringValue(axdrTree, "type");
                Object value = axdrTree.get("value");
                if ("boolean".equalsIgnoreCase(type)) {
                    return "AXDR boolean `" + value + "`";
                }
                if ("null".equalsIgnoreCase(type)) {
                    return "AXDR `null-data`";
                }
                return "AXDR payload";
            }
        }

        if (artifact.siconiaResult() instanceof Map<?, ?> siconiaMap) {
            List<?> alarmResults = listValue(siconiaMap, "alarmResults");
            if (!alarmResults.isEmpty()) {
                return alarmResults.size() + " SICONIA alarm result" + (alarmResults.size() == 1 ? "" : "s");
            }
            Map<?, ?> logAnalysis = mapValue(siconiaMap, "logAnalysis");
            if (logAnalysis != null) {
                return stringValue(logAnalysis, "dominantLayer") + " log analysis";
            }
            Map<?, ?> xmlTrace = mapValue(siconiaMap, "xmlTrace");
            if (xmlTrace != null) {
                List<?> events = listValue(xmlTrace, "events");
                return "XML trace with " + events.size() + " event" + (events.size() == 1 ? "" : "s");
            }
        }
        return null;
    }

    private String firstArtifactObis(Map<?, ?> decodeMap) {
        List<?> obisResolutions = listValue(decodeMap, "obisResolutions");
        if (obisResolutions.isEmpty() || !(obisResolutions.getFirst() instanceof Map<?, ?> resolution)) {
            return null;
        }
        return stringValue(resolution, "obis");
    }

    private Map<?, ?> mapValue(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? map : null;
    }

    private List<?> listValue(Map<?, ?> source, String key) {
        Object value = source.get(key);
        return value instanceof List<?> list ? list : List.of();
    }

    private String stringValue(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private String meaningFromDecode(DecodeResult decodeResult) {
        if (decodeResult.hdlcFrame() != null) {
            String frameLabel = frameLabelFromDecode(decodeResult);
            if (decodeResult.hdlcFrame().frameType() == FrameType.U_FRAME) {
                return meaningForControlFrame(
                        humanizeFrameLabel(frameLabel == null ? "U_FRAME" : frameLabel),
                        decodeResult.hdlcFrame().uFrameType() != null
                                ? decodeResult.hdlcFrame().uFrameType().name()
                                : null
                );
            }
            if (decodeResult.hdlcFrame().frameType() == FrameType.S_FRAME) {
                return "What it means: The last decoded payload was an HDLC supervisory control frame used to manage link-layer flow and acknowledgement state.\n"
                        + "Impact: It does not carry a DLMS APDU payload, so its role is to control link behavior rather than application data.\n"
                        + "Next step: Inspect the structured control subtype and the adjacent frames to understand why the peer sent this supervisory response.\n";
            }
        }
        if (decodeResult.apduType() != null && !"UNKNOWN".equalsIgnoreCase(decodeResult.apduType().name())) {
            return "What it means: The last decoded payload was " + decodeResult.apduType().name() + ".\n"
                    + "Impact: That identifies the DLMS application-layer message type that was present in the previous deterministic decode.\n"
                    + "Next step: Use the structured decode details to inspect the APDU fields and any AXDR or OBIS values carried inside it.\n";
        }
        return null;
    }

    private String meaningFromNarrative(SessionEvent event) {
        if (event.apduType() == null || event.apduType().isBlank()) {
            return null;
        }
        String apduType = event.apduType();
        if (apduType.startsWith("U_FRAME")) {
            String subtype = extractSubtype(apduType);
            return meaningForControlFrame(humanizeFrameLabel(apduType), subtype);
        }
        if (apduType.startsWith("S_FRAME")) {
            return "What it means: The last decoded payload was an HDLC supervisory control frame.\n"
                    + "Impact: Supervisory frames manage acknowledgement and receive state rather than carrying DLMS APDU data.\n"
                    + "Next step: Compare it with the surrounding frames to see whether it acknowledged, paused, or rejected link-layer traffic.\n";
        }
        if (apduType.startsWith("SICONIA_")) {
            return "What it means: The last structured result in this conversation was a " + apduType.replace('_', ' ').toLowerCase(Locale.ROOT) + " analysis.\n"
                    + "Impact: Use the structured panel from that result for the exact decoded alarm, XML, or log details.\n"
                    + "Next step: Ask about the specific field, alarm code, or log issue you want clarified.\n";
        }
        return "What it means: The last decoded result in this conversation was " + apduType + ".\n"
                + "Impact: That label comes from the previous deterministic decode, not from a documentation guess.\n"
                + "Next step: Ask for the role of that message type if you want a more specific protocol explanation.\n";
    }

    private String currentOrLastObis(WorkflowState state) {
        if (state.decodeResult() instanceof DecodeResult decodeResult
                && decodeResult.obisResolutions() != null
                && !decodeResult.obisResolutions().isEmpty()) {
            return decodeResult.obisResolutions().getFirst().obis();
        }
        if (state.lastObis() != null && !state.lastObis().isBlank()) {
            return state.lastObis();
        }
        String artifactObis = latestArtifactResultObis(state);
        if (hasText(artifactObis)) {
            return artifactObis;
        }
        return latestNarrativeEvent(state)
                .map(SessionEvent::obis)
                .filter(this::hasText)
                .orElse(null);
    }

    private String currentOrLastObisDescription(WorkflowState state, String obis) {
        if (state.decodeResult() instanceof DecodeResult decodeResult
                && decodeResult.obisResolutions() != null
                && !decodeResult.obisResolutions().isEmpty()
                && hasText(decodeResult.obisResolutions().getFirst().description())) {
            return decodeResult.obisResolutions().getFirst().description();
        }
        String artifactDescription = latestArtifactResultObisDescription(state, obis);
        if (hasText(artifactDescription)) {
            return artifactDescription;
        }
        return describeObisStructurally(obis);
    }

    private String latestArtifactResultObis(WorkflowState state) {
        List<ArtifactResultPayload> artifactResults = state.recentArtifactResults();
        if (artifactResults == null || artifactResults.isEmpty()) {
            return null;
        }
        for (int i = artifactResults.size() - 1; i >= 0; i -= 1) {
            ArtifactResultPayload artifact = artifactResults.get(i);
            if (!(artifact.decodeResult() instanceof Map<?, ?> decodeMap)) {
                continue;
            }
            String obis = firstArtifactObis(decodeMap);
            if (hasText(obis)) {
                return obis;
            }
        }
        return null;
    }

    private String latestArtifactResultObisDescription(WorkflowState state, String obis) {
        if (!hasText(obis)) {
            return null;
        }
        List<ArtifactResultPayload> artifactResults = state.recentArtifactResults();
        if (artifactResults == null || artifactResults.isEmpty()) {
            return null;
        }
        for (int i = artifactResults.size() - 1; i >= 0; i -= 1) {
            ArtifactResultPayload artifact = artifactResults.get(i);
            if (!(artifact.decodeResult() instanceof Map<?, ?> decodeMap)) {
                continue;
            }
            List<?> obisResolutions = listValue(decodeMap, "obisResolutions");
            for (Object candidate : obisResolutions) {
                if (!(candidate instanceof Map<?, ?> resolution)) {
                    continue;
                }
                String candidateObis = stringValue(resolution, "obis");
                if (obis.equals(candidateObis)) {
                    String description = stringValue(resolution, "description");
                    if (hasText(description)) {
                        return description;
                    }
                }
            }
        }
        return null;
    }

    private String describeObisStructurally(String obis) {
        if (!hasText(obis)) {
            return null;
        }
        try {
            String[] parts = obis.split("\\.");
            if (parts.length != 6) {
                return null;
            }
            int a = Integer.parseInt(parts[0]);
            int c = Integer.parseInt(parts[2]);
            int e = Integer.parseInt(parts[4]);

            String medium = switch (a) {
                case 1 -> "Electricity";
                case 6 -> "Heat";
                case 7 -> "Gas";
                case 8 -> "Water";
                default -> "Utility";
            };
            String quantity = switch (c) {
                case 1 -> "active energy import";
                case 2 -> "active energy export";
                case 3 -> "reactive energy import";
                case 4 -> "reactive energy export";
                case 21 -> "instantaneous active power import";
                case 22 -> "instantaneous active power export";
                default -> null;
            };
            if (quantity == null) {
                return null;
            }
            String aggregation = e == 0 ? "total" : "tariff " + e;
            return medium + " " + quantity + " " + aggregation;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String meaningFromSiconia(SiconiaResult result) {
        if (result.alarmResults() != null && !result.alarmResults().isEmpty()) {
            if (result.alarmResults().size() == 1) {
                var alarm = result.alarmResults().getFirst();
                return "What it means: Alarm " + alarm.code() + " is " + alarm.severity() + " on " + alarm.affectedComponent() + ".\n"
                        + "Impact: " + ensureSentence(alarm.rootCause()) + "\n"
                        + "Next step: " + ensureSentence(alarm.remediation()) + "\n";
            }
            return "What it means: " + result.alarmResults().size() + " alarms were decoded from the last SICONIA input.\n"
                    + "Impact: Severity, component, root cause, and remediation are available for each alarm in the structured panel.\n"
                    + "Next step: Review the decoded alarms and address the highest-severity entries first.\n";
        }
        if (result.logAnalysis() != null) {
            String categories = result.logAnalysis().issueCategories() == null || result.logAnalysis().issueCategories().isEmpty()
                    ? "the listed issue categories"
                    : result.logAnalysis().issueCategories().stream().map(Enum::name).toList().toString().replace("[", "").replace("]", "");
            return "What it means: The last SICONIA result was dominated by " + result.logAnalysis().dominantLayer() + " issues.\n"
                    + "Impact: " + result.logAnalysis().highestSeverity() + " severity with " + result.logAnalysis().errorLineCount()
                    + " error lines out of " + result.logAnalysis().lineCount() + ".\n"
                    + "Next step: Inspect the " + result.logAnalysis().dominantLayer() + " path and " + categories + ".\n";
        }
        if (result.xmlTrace() != null) {
            int eventCount = result.xmlTrace().events() == null ? 0 : result.xmlTrace().events().size();
            return "What it means: The last SICONIA result was an XML trace with " + eventCount + " recovered event" + (eventCount == 1 ? "" : "s") + ".\n"
                    + "Impact: Recovered alarm, timestamp, device, and severity details are available in the structured panel.\n"
                    + "Next step: Inspect the structured XML fields to verify the recovered event details.\n";
        }
        return null;
    }

    private String meaningForControlFrame(String friendlyLabel, String subtype) {
        String lowerSubtype = subtype == null ? "" : subtype.toLowerCase(Locale.ROOT);
        if ("snrm".equals(lowerSubtype)) {
            return "What it means: The last decoded frame was " + friendlyLabel + ", which is the Set Normal Response Mode control frame used to start HDLC connection establishment.\n"
                    + "Impact: It initiates the link-layer session before normal DLMS APDU traffic can flow.\n"
                    + "Next step: Look for the matching UA response or later association frames to confirm the connection completed successfully.\n";
        }
        return "What it means: The last decoded frame was " + friendlyLabel + ", an HDLC control frame used for link-layer state management.\n"
                + "Impact: Control frames coordinate association and transport state rather than carrying a DLMS APDU payload.\n"
                + "Next step: Inspect the subtype and adjacent frames in the structured decode to understand what the peer was signalling.\n";
    }

    private String failureFromDecode(DecodeResult decodeResult) {
        if (decodeResult.hdlcFrame() != null && !decodeResult.hdlcFrame().fcsValid()) {
            return "What happened: The last deterministic decode failed integrity validation because the HDLC frame checksum did not match.\n"
                    + "Can I trust it: No. Treat only the outer frame bytes as tentative because payload details may be corrupted.\n"
                    + "Next step: Re-capture or retransmit the frame, then inspect the communication path if checksum failures keep repeating.\n";
        }
        if (decodeResult.parseErrors() != null && !decodeResult.parseErrors().isEmpty()) {
            return "What happened: The last deterministic decode failed because " + ensureSentence(decodeResult.parseErrors().getFirst()) + "\n"
                    + "Can I trust it: Not fully. The parser reported a concrete decode error, so do not infer protocol meaning beyond the validated fields.\n"
                    + "Next step: Correct the payload formatting or re-capture the bytes, then retry the decode.\n";
        }
        return null;
    }

    private String failureFromNarrative(SessionEvent event) {
        if (event.errors() != null && !event.errors().isEmpty()) {
            return "What happened: The last recorded decode failed because " + ensureSentence(event.errors().getFirst()) + "\n"
                    + "Can I trust it: Not fully. That explanation comes from the stored deterministic parser error for the previous result.\n"
                    + "Next step: Fix the input or resend the payload, then retry the decode.\n";
        }
        if (event.warnings() != null && !event.warnings().isEmpty()) {
            return "What happened: The previous result carried a warning: " + ensureSentence(event.warnings().getFirst()) + "\n"
                    + "Can I trust it: Partially. Review the warning alongside the structured result before drawing conclusions.\n"
                    + "Next step: Inspect the structured details or resend the payload with clearer framing.\n";
        }
        if (event.anomalies() != null && !event.anomalies().isEmpty()) {
            return "What happened: The previous result triggered an anomaly: " + ensureSentence(event.anomalies().getFirst()) + "\n"
                    + "Can I trust it: Use caution. An anomaly means the previous session state did not look normal.\n"
                    + "Next step: Compare the previous result with adjacent frames or session state to isolate the cause.\n";
        }
        return null;
    }

    private String failureFromSiconia(SiconiaResult result) {
        if (result.xmlTrace() != null && result.xmlTrace().parseErrors() != null && !result.xmlTrace().parseErrors().isEmpty()) {
            return "What happened: The last SICONIA XML analysis reported " + ensureSentence(result.xmlTrace().parseErrors().getFirst()) + "\n"
                    + "Can I trust it: Only partially. The XML parser recovered what it could, but some fields may be missing or malformed.\n"
                    + "Next step: Inspect the raw XML and retry with a cleaner trace if you need fully structured details.\n";
        }
        if (result.processingMetadata() != null && result.processingMetadata().warnings() != null
                && !result.processingMetadata().warnings().isEmpty()) {
            return "What happened: The last SICONIA result carried a warning: "
                    + ensureSentence(result.processingMetadata().warnings().getFirst()) + "\n"
                    + "Can I trust it: Partially. Review the warning alongside the structured panel before acting on the result.\n"
                    + "Next step: Recheck the source input or inspect the structured details to confirm the diagnosis.\n";
        }
        return null;
    }

    private String noContextAnswer(String subject) {
        return "What happened: I do not have " + subject + " recorded in this conversation yet.\n"
                + "Can I trust it: Not yet. Session recall only works after a deterministic result has been captured in the same session.\n"
                + "Next step: Send or resend the payload you want to inspect, then ask the follow-up again in the same conversation.\n";
    }

    private String frameLabelFromDecode(DecodeResult decodeResult) {
        if (decodeResult.hdlcFrame() == null) {
            return decodeResult.apduType() == null ? null : decodeResult.apduType().name();
        }
        if (decodeResult.hdlcFrame().frameType() == FrameType.U_FRAME) {
            if (decodeResult.hdlcFrame().uFrameType() != null && decodeResult.hdlcFrame().uFrameType() != UFrameType.UNKNOWN) {
                return "U_FRAME (" + decodeResult.hdlcFrame().uFrameType().name() + ")";
            }
            return "U_FRAME";
        }
        if (decodeResult.hdlcFrame().frameType() == FrameType.S_FRAME) {
            if (decodeResult.hdlcFrame().sFrameType() != null) {
                return "S_FRAME (" + decodeResult.hdlcFrame().sFrameType().name() + ")";
            }
            return "S_FRAME";
        }
        if (decodeResult.hdlcFrame().frameType() == FrameType.I_FRAME) {
            if (decodeResult.apduType() != null && !"UNKNOWN".equalsIgnoreCase(decodeResult.apduType().name())) {
                return "I_FRAME (" + decodeResult.apduType().name() + ")";
            }
            return "I_FRAME";
        }
        return decodeResult.hdlcFrame().frameType() == null ? null : decodeResult.hdlcFrame().frameType().name();
    }

    private String humanizeFrameLabel(String frameLabel) {
        return frameLabel
                .replace("I_FRAME", "I-frame")
                .replace("U_FRAME", "U-frame")
                .replace("S_FRAME", "S-frame");
    }

    private String extractSubtype(String frameLabel) {
        int open = frameLabel.indexOf('(');
        int close = frameLabel.indexOf(')');
        if (open >= 0 && close > open) {
            return frameLabel.substring(open + 1, close);
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String ensureSentence(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith(".") ? trimmed : trimmed + ".";
    }

    public enum FollowUpKind {
        ARTIFACT_RECALL,
        PREVIOUS_FRAME_TYPE,
        CONNECTION_ROLE,
        LAST_ENTITY_RECALL,
        PREVIOUS_MEANING,
        PREVIOUS_FAILURE_REASON
    }

    public record FollowUpResolution(
            FollowUpKind kind,
            String answer,
            boolean resolvedFromContext
    ) {}
}
