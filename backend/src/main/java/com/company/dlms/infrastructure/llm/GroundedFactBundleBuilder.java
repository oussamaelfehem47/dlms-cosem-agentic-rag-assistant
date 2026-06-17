package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.answer.AnswerMode;
import com.company.dlms.domain.answer.AnswerTopicFamily;
import com.company.dlms.domain.answer.GroundedFactBundle;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrBoolean;
import com.company.dlms.domain.decoder.AxdrDate;
import com.company.dlms.domain.decoder.AxdrDateTime;
import com.company.dlms.domain.decoder.AxdrInt16;
import com.company.dlms.domain.decoder.AxdrInt32;
import com.company.dlms.domain.decoder.AxdrInt64;
import com.company.dlms.domain.decoder.AxdrInt8;
import com.company.dlms.domain.decoder.AxdrNull;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrTime;
import com.company.dlms.domain.decoder.AxdrUint16;
import com.company.dlms.domain.decoder.AxdrUint32;
import com.company.dlms.domain.decoder.AxdrUint64;
import com.company.dlms.domain.decoder.AxdrUint8;
import com.company.dlms.domain.decoder.AxdrValue;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.SFrameType;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.workflow.WorkflowState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class GroundedFactBundleBuilder {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern HEADING_LIKE_PREFIX = Pattern.compile("^\\d+\\s*\\|");
    private static final Pattern METADATA_LIKE_PREFIX = Pattern.compile("^(function:|table of contents|contents\\b|index\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BROKEN_WORD_SPACING = Pattern.compile("\\b\\p{L}{4,}\\s+\\p{L}\\b");

    public GroundedFactBundle build(WorkflowState state) {
        if (state == null || state.groundedAnswerContext() == null) {
            return GroundedFactBundle.empty();
        }

        AnswerMode mode = state.groundedAnswerContext().mode();
        if (mode == AnswerMode.DETERMINISTIC_DECODE) {
            return buildDecodeBundle(state);
        }
        if (mode == AnswerMode.DETERMINISTIC_SICONIA) {
            return buildSiconiaBundle(state.siconiaResult());
        }
        if (mode != AnswerMode.RETRIEVAL_DOCS && mode != AnswerMode.RETRIEVAL_SECURITY) {
            return GroundedFactBundle.empty();
        }

        String rawInput = state.rawInput() == null ? "" : state.rawInput().trim();
        String lower = rawInput.toLowerCase(Locale.ROOT);

        if ((lower.contains("aarq") || lower.contains("aare")) && !lower.contains("diagnostic")) {
            return bundle(
                    AnswerTopicFamily.ASSOCIATION_APDUS,
                    "Association APDUs",
                    selectQuote(state.retrievalResults(), Set.of("association", "aarq", "aare", "request", "response", "result", "diagnostic")),
                    List.of(
                            "AARQ is the Association Request APDU sent by the COSEM client to initiate an application-layer association with the server.",
                            "AARQ carries proposal fields such as the application context name, authentication value, and ACSE requirements.",
                            "AARE is the Association Response APDU sent by the COSEM server back to the client in response to the AARQ.",
                            "AARE carries the association result and diagnostic outcome, including accepted, rejected-permanent, or rejected-transient semantics when setup fails or is constrained."
                    )
            );
        }

        if (lower.contains("aare") && lower.contains("diagnostic")) {
            return bundle(
                    AnswerTopicFamily.ASSOCIATION_DIAGNOSTIC,
                    "Association Reject Diagnostic",
                    selectQuote(state.retrievalResults(), Set.of("aare", "diagnostic", "association", "authentication")),
                    List.of(
                            "An AARE diagnostic is returned by the server when the association is rejected or constrained.",
                            "Diagnostic failures usually point to mismatched application context, authentication choice, credentials, or negotiated security parameters.",
                            "Troubleshooting should compare the client's proposed association parameters with a known-good AARE/AARQ exchange."
                    )
            );
        }

        if (lower.contains("green book")) {
            return bundle(
                    AnswerTopicFamily.STANDARDS_BOOK,
                    "DLMS Green Book",
                    selectQuote(state.retrievalResults(), Set.of("green book", "architecture", "terms", "communication", "profiles")),
                    List.of(
                            "The DLMS Green Book defines the DLMS/COSEM system architecture, terminology, conformance concepts, and communication profiles.",
                            "It is the standards reference used for how DLMS/COSEM systems interoperate at the architecture and transport/profile level.",
                            "It complements the Blue Book, which focuses more on COSEM interface classes, object models, and data semantics."
                    )
            );
        }

        if (lower.contains("blue book")) {
            return bundle(
                    AnswerTopicFamily.STANDARDS_BOOK,
                    "DLMS Blue Book",
                    selectQuote(state.retrievalResults(), Set.of("blue book", "interface classes", "obis", "cosem")),
                    List.of(
                            "The DLMS Blue Book defines COSEM interface classes, object models, and much of the application-layer semantic model.",
                            "It is the standards reference typically used when interpreting OBIS-linked objects, attributes, and actions.",
                            "It complements the Green Book, which covers the broader architecture and communication framework."
                    )
            );
        }

        if (lower.contains("hdlc") && lower.contains("frame structure")) {
            return bundle(
                    AnswerTopicFamily.PROTOCOL_HDLC_STRUCTURE,
                    "HDLC Frame Structure",
                    selectQuote(state.retrievalResults(), Set.of("hdlc", "frame", "control", "checksum", "flag")),
                    List.of(
                            "An HDLC frame is bounded by opening and closing flag bytes.",
                            "Its main fields are frame format/length, addressing, control, information, and frame check sequence.",
                            "Those fields delimit the frame, carry link-layer state, and transport DLMS APDUs when an I-frame carries application data."
                    )
            );
        }

        if (lower.contains("hls") && lower.contains("authentication")) {
            return bundle(
                    AnswerTopicFamily.SECURITY_HLS,
                    "HLS Authentication",
                    selectQuote(state.retrievalResults(), Set.of("hls", "challenge-response", "authentication", "gmac", "aes-gcm")),
                    List.of(
                            "HLS is DLMS/COSEM high-level security based on challenge-response proof rather than only a static shared secret.",
                            "A typical HLS flow is: the client requests HLS in the AARQ, the server responds in the AARE and supplies challenge/context, the client computes a cryptographic reply, the server verifies it and may send its own authenticated response, and normal application traffic continues only after successful verification.",
                            "Depending on the negotiated suite and mechanism, the authenticated proof can rely on GMAC or AES-GCM-128-based security rather than plain-text password checks."
                    )
            );
        }

        if (lower.contains("security suite 1") || lower.matches(".*\\bsuite\\s*1\\b.*")) {
            return bundle(
                    AnswerTopicFamily.SECURITY_SUITE,
                    "DLMS Security Suite 1",
                    selectQuote(state.retrievalResults(), Set.of("suite 1", "aes-gcm", "authentication", "encryption")),
                    List.of(
                            "DLMS security suite 1 provides both authentication and encryption.",
                            "Its standard authenticated-encryption mechanism is AES-GCM-128.",
                            "In practice, suite 1 combines confidentiality, integrity, and freshness checks so protected messages cannot simply be replayed with stale counters."
                    )
            );
        }

        if (lower.contains("replay protection") || (lower.contains("replay") && lower.contains("dlms"))) {
            return bundle(
                    AnswerTopicFamily.SECURITY_REPLAY,
                    "DLMS Replay Protection",
                    selectQuote(state.retrievalResults(), Set.of("replay", "frame counter", "invocation counter", "stale")),
                    List.of(
                            "Replay protection in DLMS relies on a frame or invocation counter carried in the security header.",
                            "The receiver accepts only fresh, monotonically increasing counter values and rejects stale or reused ones as replay attempts.",
                            "That freshness check is evaluated together with the authenticated security envelope rather than as a standalone transport heuristic."
                    )
            );
        }

        return GroundedFactBundle.empty();
    }

    private GroundedFactBundle buildDecodeBundle(WorkflowState state) {
        if (!(state.decodeResult() instanceof DecodeResult decodeResult)) {
            return GroundedFactBundle.empty();
        }

        if (decodeResult.hdlcFrame() != null) {
            boolean tentative = !decodeResult.hdlcFrame().fcsValid()
                    || (decodeResult.parseErrors() != null && !decodeResult.parseErrors().isEmpty());
            if (tentative) {
                return buildTentativeFrameBundle(decodeResult);
            }
            if (decodeResult.hdlcFrame().frameType() == FrameType.U_FRAME) {
                return buildUFrameBundle(decodeResult);
            }
            if (decodeResult.hdlcFrame().frameType() == FrameType.S_FRAME) {
                return buildSFrameBundle(decodeResult);
            }
        }

        DlmsNormalizedKind normalizedKind = decodeResult.processingMetadata() != null
                ? decodeResult.processingMetadata().normalizedKind()
                : null;
        if (normalizedKind == DlmsNormalizedKind.OBIS_QUERY) {
            return buildObisBundle(decodeResult);
        }
        if (normalizedKind == DlmsNormalizedKind.AXDR_HEX) {
            return buildAxdrBundle(decodeResult);
        }
        if ((normalizedKind == DlmsNormalizedKind.APDU_HEX || decodeResult.apduType() != ApduType.UNKNOWN)
                && decodeResult.apduType() != null) {
            return buildApduBundle(decodeResult);
        }
        return GroundedFactBundle.empty();
    }

    private GroundedFactBundle buildSiconiaBundle(SiconiaResult result) {
        if (result == null) {
            return GroundedFactBundle.empty();
        }
        if (result.processingMetadata() != null
                && result.processingMetadata().provenance() == ParseProvenance.RAW_FALLBACK) {
            return GroundedFactBundle.empty();
        }
        if (result.alarmResults() != null && !result.alarmResults().isEmpty()) {
            return buildSiconiaAlarmBundle(result.alarmResults());
        }
        if (result.logAnalysis() != null) {
            return bundle(
                    AnswerTopicFamily.SICONIA_LOG_SUMMARY,
                    "SICONIA Log Summary",
                    "",
                    List.of(
                            "The log analysis identified the dominant layer as " + result.logAnalysis().dominantLayer() + ".",
                            "The highest severity observed was " + result.logAnalysis().highestSeverity() + " with "
                                    + result.logAnalysis().errorLineCount() + " error lines out of " + result.logAnalysis().lineCount() + ".",
                            result.logAnalysis().issueCategories() == null || result.logAnalysis().issueCategories().isEmpty()
                                    ? "No issue categories were extracted beyond the dominant layer."
                                    : "The extracted issue categories were "
                                    + result.logAnalysis().issueCategories().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", ")) + "."
                    )
            );
        }
        if (result.xmlTrace() != null) {
            int eventCount = result.xmlTrace().events() == null ? 0 : result.xmlTrace().events().size();
            int parseErrorCount = result.xmlTrace().parseErrors() == null ? 0 : result.xmlTrace().parseErrors().size();
            return bundle(
                    AnswerTopicFamily.SICONIA_XML_SUMMARY,
                    "SICONIA XML Summary",
                    "",
                    List.of(
                            "The XML trace was parsed into " + eventCount + " recovered event" + plural(eventCount) + ".",
                            "Structured alarm, timestamp, device, and severity details should be taken from the recovered XML fields.",
                            "The parser reported " + parseErrorCount + " XML parse error" + plural(parseErrorCount) + "."
                    )
            );
        }
        return GroundedFactBundle.empty();
    }

    private GroundedFactBundle bundle(
            AnswerTopicFamily family,
            String topicLabel,
            String preferredQuote,
            List<String> facts
    ) {
        return new GroundedFactBundle(family, topicLabel, preferredQuote, facts);
    }

    private GroundedFactBundle buildUFrameBundle(DecodeResult decodeResult) {
        UFrameType subtype = decodeResult.hdlcFrame().uFrameType() == null
                ? UFrameType.UNKNOWN
                : decodeResult.hdlcFrame().uFrameType();
        List<String> facts = new ArrayList<>();
        facts.add("The deterministic decode classified the frame as an HDLC U-frame with subtype " + subtype + ".");
        switch (subtype) {
            case SNRM -> {
                facts.add("SNRM means Set Normal Response Mode and is used to begin HDLC link-layer session establishment.");
                facts.add("SNRM is the standard HDLC link-setup request that asks the peer to enter normal response mode before DLMS association APDUs such as AARQ/AARE can proceed.");
                facts.add("SNRM by itself does not prove that the peer accepted normal response mode; a confirming UA frame would show that.");
            }
            case UA -> {
                facts.add("UA means Unnumbered Acknowledge and confirms a prior HDLC link-state change request.");
                facts.add("UA typically appears as the control-frame response that confirms setup or release at the HDLC layer.");
            }
            case DISC -> {
                facts.add("DISC means Disconnect and requests release of the HDLC link layer.");
                facts.add("DISC is a link teardown control frame rather than an application payload carrier.");
            }
            case DM -> {
                facts.add("DM means Disconnected Mode and indicates the peer is not available for normal response mode communication.");
                facts.add("DM signals refusal or absence of an established HDLC link instead of a DLMS APDU exchange.");
            }
            default -> {
                facts.add("U-frames are HDLC link-layer control frames and do not carry normal DLMS APDU payloads.");
                facts.add("The subtype determines the exact link-state transition being requested or confirmed.");
            }
        }
        return bundle(AnswerTopicFamily.DECODE_HDLC_U_FRAME, "HDLC U-frame Role", "", facts);
    }

    private GroundedFactBundle buildSFrameBundle(DecodeResult decodeResult) {
        SFrameType subtype = decodeResult.hdlcFrame().sFrameType();
        String subtypeLabel = subtype == null ? "UNKNOWN" : subtype.name();
        List<String> facts = new ArrayList<>();
        facts.add("The deterministic decode classified the frame as an HDLC supervisory frame with subtype " + subtypeLabel + ".");
        switch (subtype) {
            case RR -> facts.add("RR means Receive Ready and carries receive-ready flow-control meaning at the HDLC link layer.");
            case RNR -> facts.add("RNR means Receive Not Ready and carries temporary receive-pause flow-control meaning at the HDLC link layer.");
            case REJ -> facts.add("REJ means Reject and carries retransmission-control meaning after a delivery or sequencing problem.");
            default -> facts.add("Supervisory frames communicate flow-control state at the HDLC link layer.");
        }
        facts.add("S-frames are control frames and do not carry normal DLMS APDU payloads.");
        return bundle(AnswerTopicFamily.DECODE_HDLC_S_FRAME, "HDLC S-frame Role", "", facts);
    }

    private GroundedFactBundle buildTentativeFrameBundle(DecodeResult decodeResult) {
        FrameType frameType = decodeResult.hdlcFrame().frameType();
        String subtype = frameType == FrameType.U_FRAME
                ? (decodeResult.hdlcFrame().uFrameType() == null ? "UNKNOWN" : decodeResult.hdlcFrame().uFrameType().name())
                : frameType == FrameType.S_FRAME
                ? (decodeResult.hdlcFrame().sFrameType() == null ? "UNKNOWN" : decodeResult.hdlcFrame().sFrameType().name())
                : frameType.name();
        List<String> facts = new ArrayList<>();
        facts.add("Only the outer HDLC classification is trustworthy because the frame is tentative.");
        facts.add("The outer frame classification is " + frameType + " with subtype " + subtype + ".");
        facts.add("FCS validity is " + decodeResult.hdlcFrame().fcsValid() + " and payload interpretation must not be trusted.");
        if (frameType == FrameType.U_FRAME) {
            facts.add(controlRoleSentenceForTentativeUFrame(decodeResult.hdlcFrame().uFrameType()));
        } else if (frameType == FrameType.S_FRAME) {
            facts.add(controlRoleSentenceForTentativeSFrame(decodeResult.hdlcFrame().sFrameType()));
        } else {
            facts.add("No higher-layer payload meaning should be inferred from this malformed or checksum-failed capture.");
        }
        if (decodeResult.parseErrors() != null && !decodeResult.parseErrors().isEmpty()) {
            facts.add("Reported structural issues: " + String.join("; ", decodeResult.parseErrors()) + ".");
        }
        return bundle(AnswerTopicFamily.DECODE_HDLC_TENTATIVE_OUTER_ROLE, "Tentative HDLC Outer Role", "", facts);
    }

    private GroundedFactBundle buildApduBundle(DecodeResult decodeResult) {
        String apduLabel = decodeResult.apduType() == null ? "UNKNOWN" : decodeResult.apduType().name();
        List<String> facts = new ArrayList<>();
        if (decodeResult.apduType() == ApduType.GET_RESPONSE) {
            facts.add("The deterministic decode identified the payload as the DLMS APDU GET_RESPONSE.");
            facts.add("GET_RESPONSE is the server's reply to a prior GET_REQUEST and carries the requested attribute value or returned structure.");
            facts.add("GET_RESPONSE is not a request.");
            if (isObjectIdentifierOnlyGetResponse(decodeResult)) {
                facts.add("In this decode, the AXDR content identifies the returned object reference or structure rather than proving a live meter reading or current measurement value.");
                facts.add("The six-byte octet-string here is the returned OBIS/object identifier, so do not describe it as the specific meter value.");
            }
        } else {
            facts.add("The deterministic decode identified the payload as the DLMS APDU " + apduLabel + ".");
            facts.add("The APDU interpretation comes from deterministic Java decoding rather than freeform inference.");
        }
        if (decodeResult.obisResolutions() != null && !decodeResult.obisResolutions().isEmpty()) {
            var firstObis = decodeResult.obisResolutions().getFirst();
            if ("1.0.1.8.0.255".equals(firstObis.obis())) {
                facts.add("The decoded payload includes OBIS 1.0.1.8.0.255, which means Active energy import total.");
            } else {
                facts.add("The decoded payload includes OBIS " + firstObis.obis() + " (" + firstObis.description() + ").");
            }
        }
        if (decodeResult.axdrTree() != null) {
            facts.add("Any embedded AXDR structure should be explained from the decoded tree rather than guessed from raw bytes.");
        }
        return bundle(AnswerTopicFamily.DECODE_APDU_OPERATION, "DLMS APDU Operation", "", facts);
    }

    private GroundedFactBundle buildAxdrBundle(DecodeResult decodeResult) {
        AxdrValue value = decodeResult.axdrTree();
        List<String> facts = new ArrayList<>();
        facts.add("The deterministic decode treated the input as raw AXDR without an HDLC or APDU envelope.");
        facts.add("The top-level AXDR value is " + axdrValueSummary(value) + ".");
        if (decodeResult.obisResolutions() != null && !decodeResult.obisResolutions().isEmpty()) {
            var firstObis = decodeResult.obisResolutions().getFirst();
            facts.add("The decoded AXDR content also exposed OBIS " + firstObis.obis() + " (" + firstObis.description() + ").");
        }
        return bundle(AnswerTopicFamily.DECODE_AXDR_VALUE, "AXDR Value", "", facts);
    }

    private GroundedFactBundle buildObisBundle(DecodeResult decodeResult) {
        if (decodeResult.obisResolutions() == null || decodeResult.obisResolutions().isEmpty()) {
            return bundle(
                    AnswerTopicFamily.DECODE_OBIS_LOOKUP,
                    "OBIS Lookup",
                    "",
                    List.of("The input resolved to a deterministic OBIS lookup.", "No resolved OBIS details were available beyond the lookup classification.")
            );
        }
        var resolution = decodeResult.obisResolutions().getFirst();
        return bundle(
                AnswerTopicFamily.DECODE_OBIS_LOOKUP,
                "OBIS Lookup",
                "",
                List.of(
                        "The input resolved to a deterministic OBIS lookup for " + resolution.obis() + ".",
                        "The resolved meaning is " + resolution.description() + ".",
                        "The interface class is " + resolution.ic() + " and the unit/scaler are " + resolution.unit() + " with scaler " + resolution.scaler() + "."
                )
        );
    }

    private GroundedFactBundle buildSiconiaAlarmBundle(List<AlarmDecodeResult> alarmResults) {
        if (alarmResults.size() == 1) {
            AlarmDecodeResult alarm = alarmResults.getFirst();
            List<String> facts = new ArrayList<>();
            facts.add("Alarm " + alarm.code() + " was decoded deterministically as " + alarm.rootCause() + ".");
            facts.add("Its severity is " + alarm.severity() + " and the affected component is " + alarm.affectedComponent() + ".");
            if (isCommunicationAlarm(alarm)) {
                facts.add("This alarm indicates a communication-path disruption and can interrupt dependent traffic until the link is restored.");
            }
            facts.add("The recommended remediation is " + ensureSentence(alarm.remediation()));
            return bundle(
                    AnswerTopicFamily.SICONIA_ALARM_SUMMARY,
                    "SICONIA Alarm Summary",
                    "",
                    facts
            );
        }
        List<String> facts = new ArrayList<>();
        facts.add("The input decoded into " + alarmResults.size() + " separate SICONIA alarms.");
        for (AlarmDecodeResult alarm : alarmResults) {
            facts.add("Alarm " + alarm.code() + " is " + alarm.severity() + " on " + alarm.affectedComponent()
                    + " with root cause " + ensureSentence(alarm.rootCause()));
        }
        return bundle(AnswerTopicFamily.SICONIA_ALARM_SUMMARY, "SICONIA Alarm Summary", "", facts);
    }

    private String selectQuote(List<RetrievalResult> results, Set<String> preferredTerms) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<String> sentences = new ArrayList<>();
        for (RetrievalResult result : results) {
            if (result == null || result.chunk() == null || result.chunk().content() == null) {
                continue;
            }
            String content = result.chunk().content().replace('\n', ' ').trim();
            if (content.isBlank()) {
                continue;
            }
            for (String sentence : SENTENCE_SPLIT.split(content)) {
                String trimmed = sentence.trim();
                if (!trimmed.isBlank()) {
                    sentences.add(trimmed);
                }
            }
        }
        if (sentences.isEmpty()) {
            return "";
        }
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            boolean matches = preferredTerms.stream().anyMatch(lower::contains);
            if (matches && isReadableQuote(sentence)) {
                return trimToSentence(sentence);
            }
        }
        for (String sentence : sentences) {
            if (isReadableQuote(sentence)) {
                return trimToSentence(sentence);
            }
        }
        return "";
    }

    private String trimToSentence(String sentence) {
        String trimmed = sentence == null ? "" : sentence.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
            return trimmed;
        }
        return trimmed + ".";
    }

    private boolean isReadableQuote(String sentence) {
        String trimmed = sentence == null ? "" : sentence.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if (HEADING_LIKE_PREFIX.matcher(trimmed).find() || METADATA_LIKE_PREFIX.matcher(trimmed).find()) {
            return false;
        }
        if (BROKEN_WORD_SPACING.matcher(trimmed).find()) {
            return false;
        }

        char firstMeaningful = firstMeaningfulChar(trimmed);
        if (firstMeaningful == 0) {
            return false;
        }
        if (Character.isLetter(firstMeaningful) && Character.isLowerCase(firstMeaningful)) {
            return false;
        }
        return true;
    }

    private String controlRoleSentenceForTentativeUFrame(UFrameType subtype) {
        if (subtype == null) {
            return "U-frames are HDLC link-state control frames, but the exact subtype role is not trustworthy here.";
        }
        return switch (subtype) {
            case SNRM -> "If the outer subtype is correct, SNRM is the HDLC link setup request used to begin normal response mode.";
            case UA -> "If the outer subtype is correct, UA is the unnumbered acknowledgement frame used to confirm a link-state request.";
            case DISC -> "If the outer subtype is correct, DISC is the link disconnect request.";
            case DM -> "If the outer subtype is correct, DM indicates disconnected mode or refusal of normal response mode communication.";
            default -> "U-frames are HDLC link-state control frames, but the exact subtype role is not trustworthy here.";
        };
    }

    private String controlRoleSentenceForTentativeSFrame(SFrameType subtype) {
        if (subtype == null) {
            return "Supervisory frames carry flow-control state, but the subtype-specific meaning is only tentative here.";
        }
        return switch (subtype) {
            case RR -> "If the outer subtype is correct, RR means Receive Ready and carries receive-ready flow-control meaning.";
            case RNR -> "If the outer subtype is correct, RNR means Receive Not Ready and carries temporary receive-pause flow-control meaning.";
            case REJ -> "If the outer subtype is correct, REJ carries retransmission-control meaning after a sequencing or delivery problem.";
        };
    }

    private boolean isCommunicationAlarm(AlarmDecodeResult alarm) {
        String rootCause = alarm.rootCause() == null ? "" : alarm.rootCause().toLowerCase(Locale.ROOT);
        String remediation = alarm.remediation() == null ? "" : alarm.remediation().toLowerCase(Locale.ROOT);
        return rootCause.contains("comm")
                || rootCause.contains("communication")
                || remediation.contains("link")
                || remediation.contains("connect");
    }

    private boolean isObjectIdentifierOnlyGetResponse(DecodeResult decodeResult) {
        if (decodeResult == null || decodeResult.apduType() != ApduType.GET_RESPONSE) {
            return false;
        }
        if (decodeResult.axdrTree() instanceof AxdrStructure structure && structure.elements().size() == 1) {
            return structure.elements().getFirst() instanceof AxdrOctetString octetString
                    && octetString.value() != null
                    && octetString.value().length == 6;
        }
        return decodeResult.axdrTree() instanceof AxdrOctetString octetString
                && octetString.value() != null
                && octetString.value().length == 6;
    }

    private String axdrValueSummary(AxdrValue value) {
        if (value == null) {
            return "an untyped AXDR value";
        }
        if (value instanceof AxdrNull) {
            return "AXDR null-data";
        }
        if (value instanceof AxdrBoolean bool) {
            return "AXDR boolean " + bool.value();
        }
        if (value instanceof AxdrDateTime dateTime) {
            return "AXDR date-time " + dateTime.year() + "-" + pad(Byte.toUnsignedInt(dateTime.month())) + "-" + pad(Byte.toUnsignedInt(dateTime.dom()))
                    + "T" + pad(Byte.toUnsignedInt(dateTime.hour())) + ":" + pad(Byte.toUnsignedInt(dateTime.min())) + ":" + pad(Byte.toUnsignedInt(dateTime.sec()));
        }
        if (value instanceof AxdrDate date) {
            return "AXDR date " + date.year() + "-" + pad(Byte.toUnsignedInt(date.month())) + "-" + pad(Byte.toUnsignedInt(date.dom()));
        }
        if (value instanceof AxdrTime time) {
            return "AXDR time " + pad(Byte.toUnsignedInt(time.hour())) + ":" + pad(Byte.toUnsignedInt(time.min())) + ":" + pad(Byte.toUnsignedInt(time.sec()));
        }
        if (value instanceof AxdrInt8 number) {
            return "AXDR int8 " + number.value();
        }
        if (value instanceof AxdrInt16 number) {
            return "AXDR int16 " + number.value();
        }
        if (value instanceof AxdrInt32 number) {
            return "AXDR int32 " + number.value();
        }
        if (value instanceof AxdrInt64 number) {
            return "AXDR int64 " + number.value();
        }
        if (value instanceof AxdrUint8 number) {
            return "AXDR uint8 " + number.value();
        }
        if (value instanceof AxdrUint16 number) {
            return "AXDR uint16 " + number.value();
        }
        if (value instanceof AxdrUint32 number) {
            return "AXDR uint32 " + number.value();
        }
        if (value instanceof AxdrUint64 number) {
            return "AXDR uint64 " + number.value();
        }
        if (value instanceof AxdrOctetString octets) {
            return "AXDR octet-string " + HexFormat.of().withUpperCase().formatHex(octets.value());
        }
        if (value instanceof AxdrStructure structure) {
            return "an AXDR structure with " + structure.elements().size() + " element" + plural(structure.elements().size());
        }
        if (value instanceof AxdrArray array) {
            return "an AXDR array with " + array.elements().size() + " element" + plural(array.elements().size());
        }
        return "a decoded AXDR value of type " + value.getClass().getSimpleName();
    }

    private String ensureSentence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        return trimmed.endsWith(".") ? trimmed : trimmed + ".";
    }

    private String plural(int count) {
        return count == 1 ? "" : "s";
    }

    private String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private char firstMeaningfulChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                return current;
            }
        }
        return 0;
    }
}
