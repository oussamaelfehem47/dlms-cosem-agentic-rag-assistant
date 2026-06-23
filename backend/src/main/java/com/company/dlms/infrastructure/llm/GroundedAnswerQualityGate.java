package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.answer.AnswerTopicFamily;
import com.company.dlms.domain.answer.GroundedFactBundle;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.workflow.WorkflowState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GroundedAnswerQualityGate {

    private static final String[] DISALLOWED_QUOTED_OPENINGS = {
            "\"this clause",
            "\"function:",
            "\"ng the",
            "\"10 |",
            "\"all the features defined",
            "\"the aarq, aare, rlrq and rlre apdus",
            "“this clause",
            "“function:",
            "“ng the",
            "“10 |",
            "“all the features defined",
            "“the aarq, aare, rlrq and rlre apdus"
    };

    private static final Pattern DISALLOWED_DOCUMENT_OPENING = Pattern.compile(
            "^(?:section\\b|clause\\b|function:\\b|contents\\b|index\\b|table\\s+\\d+\\b|\\d+\\s*\\||"
                    + "this clause\\b|all the features defined\\b|the aarq, aare, rlrq and rlre apdus\\b|"
                    + "if protection_?parameters\\b|electricity metering data exchange\\b)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEADING_INTEGER = Pattern.compile("(\\d+)");
    private static final Pattern STANDALONE_ACK = Pattern.compile("\\back\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STANDALONE_RCV = Pattern.compile("\\brcv\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GET_RESPONSE_AS_REQUEST = Pattern.compile(
            "\\bget[_ -]?response\\b[^.\\n]{0,80}\\b(?:is|acts as|serves as|represents|as)\\b[^.\\n]{0,40}\\b(?:a\\s+)?request\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GET_RESPONSE_CLIENT_REQUEST = Pattern.compile(
            "\\bget[_ -]?response\\b[^.\\n]{0,80}\\bclient\\b[^.\\n]{0,40}\\brequest\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SNRM_UA_REPLY_SPECULATION = Pattern.compile(
            "\\b(?:reply|replies|respond|responds|response|next)\\b[^.\\n]{0,40}\\bua\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SNRM_NORMAL_RESPONSE_MODE_OVERCLAIM = Pattern.compile(
            "\\b(?:link|session|peer)\\b[^.\\n]{0,80}\\b(?:is now|now in|already in|has entered|entered|ready in)\\b[^.\\n]{0,40}\\bnormal response mode\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SNRM_APDU_READINESS_OVERCLAIM = Pattern.compile(
            "\\b(?:ready|awaiting|prepared|able|set up)\\b[^.\\n]{0,80}\\b(?:apdu|apdus|aarq|aare|association traffic|subsequent communication)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TENTATIVE_SERVER_CLIENT_ATTRIBUTION = Pattern.compile(
            "\\b(?:server|client|peer)\\b[^.\\n]{0,60}\\b(?:acknowledge(?:d|s|ment)?|ready to receive|receive more i-frames|receive-ready)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TENTATIVE_PREVIOUS_REQUEST_SPECULATION = Pattern.compile(
            "\\b(?:previous|prior)\\b[^.\\n]{0,40}\\b(?:request|acknowledg(?:e|ment))\\b|\\bunsent request\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INVENTED_METER_READING = Pattern.compile(
            "\\b(?:meter\\s+reading|actual\\s+value|returned\\s+value|consumption\\s+value)\\b[^.\\n]{0,60}\\b\\d+(?:\\.\\d+)?\\s*(?:kwh|wh|kw|w)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GET_RESPONSE_OBJECT_VALUE_OVERCLAIM = Pattern.compile(
            "\\b(?:requested attribute value|current state|current value|live value|meter is indicating|returned the value|specific value(?: being returned)?|confirms the current state|measurement value|specific details returned)\\b"
                    + "|\\b(?:represents|contains|shows|carries)\\b[^.\\n]{0,40}\\b(?:the\\s+)?value\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final String LEADING_QUOTES = "\"'“”‘’";

    private final boolean preferGroundedFamilyFallback;

    public GroundedAnswerQualityGate(
            @Value("${spring.ai.ollama.chat.model:qwen2.5:3b}") String modelName
    ) {
        String normalizedModel = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        this.preferGroundedFamilyFallback = normalizedModel.contains("lfm")
                || normalizedModel.contains("qwen2.5:3b");
    }

    public enum Decision {
        ACCEPT_GENERATED,
        USE_GROUNDED_FALLBACK
    }

    public record Evaluation(Decision decision, String reason) {}

    public Evaluation evaluate(WorkflowState state, GroundedFactBundle bundle, String generatedAnswer) {
        String answer = generatedAnswer == null ? "" : generatedAnswer.trim();
        String lower = answer.toLowerCase(Locale.ROOT);

        if (preferGroundedFamilyFallback && shouldPreferGroundedFallback(bundle.family())) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "weak synthesis model prefers grounded family summary");
        }

        if (answer.isBlank()) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "blank answer");
        }
        if (lower.contains("confidence:")) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "confidence leak");
        }
        if (lower.contains("sources:")) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "sources leaked into answer body");
        }
        if (bundle.family() != AnswerTopicFamily.NONE && startsWithDisallowedQuotedOpening(answer, lower)) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "quoted document fragment opening");
        }
        if (bundle.family() == AnswerTopicFamily.ASSOCIATION_APDUS && lower.contains("acknowledge")) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "misdefined AARQ/AARE semantics");
        }
        if (bundle.family() == AnswerTopicFamily.DECODE_HDLC_TENTATIVE_OUTER_ROLE
                && (lower.contains("the payload decodes")
                || lower.contains("apdu type is")
                || lower.contains("obis ")
                || lower.contains("octet-string")
                || lower.contains("axdr ")
                || lower.contains("get_response"))) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "tentative frame answer interpreted payload");
        }
        if (bundle.family() == AnswerTopicFamily.DECODE_HDLC_U_FRAME
                && containsUnsupportedUFrameSpeculation(state, lower)) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "unsupported u-frame protocol speculation");
        }
        if (bundle.family() == AnswerTopicFamily.DECODE_HDLC_TENTATIVE_OUTER_ROLE
                && containsTentativeOuterRoleOverreach(lower)) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "tentative outer-role overreach");
        }
        if (bundle.family() == AnswerTopicFamily.DECODE_APDU_OPERATION
                && contradictsGetResponseSemantics(state, lower)) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "get_response semantics drift");
        }
        if (sentenceCount(answer) < 2 && bundle.family() != AnswerTopicFamily.NONE) {
            return new Evaluation(Decision.USE_GROUNDED_FALLBACK, "insufficient detail");
        }

        return switch (bundle.family()) {
            case ASSOCIATION_APDUS -> containsAll(lower, "request", "response")
                    && (lower.contains("client") || lower.contains("meter"))
                    && (lower.contains("server") || lower.contains("head-end"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "association detail sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing association semantics");
            case ASSOCIATION_DIAGNOSTIC -> (lower.contains("diagnostic")
                    || lower.contains("rejected")
                    || lower.contains("association"))
                    && (lower.contains("authentication") || lower.contains("application context") || lower.contains("security"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "diagnostic detail sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing reject diagnostic semantics");
            case STANDARDS_BOOK -> (lower.contains("architecture")
                    || lower.contains("communication")
                    || lower.contains("transport")
                    || lower.contains("profiles"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "standards scope sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing standards scope");
            case PROTOCOL_HDLC_STRUCTURE -> sentenceCount(answer) >= 3
                    && (lower.contains("flag") || lower.contains("checksum") || lower.contains("fcs"))
                    && lower.contains("control")
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "hdlc structure sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing hdlc structure detail");
            case SECURITY_HLS -> sentenceCount(answer) >= 4
                    && containsPhrase(lower, "challenge-response")
                    && (lower.contains("gmac") || lower.contains("aes-gcm-128"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "hls detail sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing hls flow or algorithm");
            case SECURITY_SUITE -> containsAll(lower, "authentication", "encryption")
                    && lower.contains("aes-gcm-128")
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "suite detail sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing suite semantics");
            case SECURITY_REPLAY -> (lower.contains("frame counter") || lower.contains("invocation counter"))
                    && (lower.contains("monotonic") || lower.contains("increase") || lower.contains("increment"))
                    && (lower.contains("reject") || lower.contains("stale") || lower.contains("replay"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "replay detail sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing replay semantics");
            case DECODE_HDLC_U_FRAME -> lower.contains("hdlc")
                    && (lower.contains("link-layer") || lower.contains("link layer"))
                    && (lower.contains("snrm") || lower.contains("ua") || lower.contains("disc") || lower.contains("dm") || lower.contains("unnumbered"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "u-frame explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing u-frame role");
            case DECODE_HDLC_S_FRAME -> lower.contains("hdlc")
                    && (lower.contains("flow control") || lower.contains("acknowledg") || lower.contains("receive ready") || lower.contains("retrans"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "s-frame explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing s-frame role");
            case DECODE_HDLC_TENTATIVE_OUTER_ROLE -> (lower.contains("checksum") || lower.contains("fcs") || lower.contains("tentative"))
                    && (lower.contains("hdlc") || lower.contains("supervisory") || lower.contains("link-layer") || lower.contains("receive ready"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "tentative outer-role explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing tentative frame guardrails");
            case DECODE_APDU_OPERATION -> sentenceCount(answer) >= 2
                    && (lower.contains("apdu") || lower.contains("dlms"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "apdu explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing apdu operation detail");
            case DECODE_AXDR_VALUE -> sentenceCount(answer) >= 2
                    && (lower.contains("axdr") || lower.contains("boolean") || lower.contains("null-data") || lower.contains("date-time"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "axdr explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing axdr scalar detail");
            case DECODE_OBIS_LOOKUP -> (lower.contains("obis") || lower.contains("active energy") || lower.contains("register"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "obis explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing obis lookup detail");
            case SICONIA_ALARM_SUMMARY -> {
                int decodedAlarmCount = inferredAlarmCount(bundle);
                boolean mentionsAlarmSummary = lower.contains("alarm") || lower.contains("severity") || lower.contains("root cause");
                boolean mentionsGroupedAlarmCount = decodedAlarmCount <= 1
                        || lower.contains(decodedAlarmCount + " alarms")
                        || lower.contains("multiple alarms")
                        || lower.contains("grouped alarm");
                yield sentenceCount(answer) >= 2
                        && mentionsAlarmSummary
                        && mentionsGroupedAlarmCount
                        ? new Evaluation(Decision.ACCEPT_GENERATED, "alarm explanation sufficient")
                        : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing grouped alarm summary detail");
            }
            case SICONIA_LOG_SUMMARY -> sentenceCount(answer) >= 2
                    && (lower.contains("log") || lower.contains("layer") || lower.contains("issue"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "log explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing log summary detail");
            case SICONIA_XML_SUMMARY -> sentenceCount(answer) >= 2
                    && (lower.contains("xml") || lower.contains("trace") || lower.contains("event"))
                    ? new Evaluation(Decision.ACCEPT_GENERATED, "xml explanation sufficient")
                    : new Evaluation(Decision.USE_GROUNDED_FALLBACK, "missing xml summary detail");
            case NONE -> new Evaluation(Decision.ACCEPT_GENERATED, "no family fallback");
        };
    }

    public String fallbackSummary(WorkflowState state, GroundedFactBundle bundle) {
        return switch (bundle.family()) {
            case ASSOCIATION_APDUS ->
                    "In DLMS/COSEM, AARQ is the Association Request APDU sent by the client to initiate an application association, while AARE is the Association Response APDU returned by the server.\n"
                    + "The AARQ proposes the application context, authentication choice, ACSE requirements, and related security parameters.\n"
                    + "The AARE accepts or rejects those values and carries the association result and diagnostic outcome back to the client when setup succeeds or fails.\n";
            case ASSOCIATION_DIAGNOSTIC ->
                    "An AARE diagnostic is returned by the server when the application association is rejected or constrained.\n"
                    + "In practice, that usually means the application context, authentication method, credentials, or negotiated security parameters did not match what the server expected.\n"
                    + "The right next step is to compare the client's proposed AARQ settings with a known-good association, especially the application context name, authentication level, and security suite.\n";
            case STANDARDS_BOOK -> {
                String lower = state.rawInput() == null ? "" : state.rawInput().toLowerCase(Locale.ROOT);
                if (lower.contains("blue book")) {
                    yield "The DLMS Blue Book defines COSEM interface classes, object models, and much of the application-layer semantic model used by DLMS/COSEM devices.\n"
                            + "It is the standards reference you use when interpreting OBIS-linked objects, attributes, and actions.\n"
                            + "It complements the Green Book, which covers the broader architecture and communication framework.\n";
                }
                yield "The DLMS Green Book defines the DLMS/COSEM architecture, terminology, conformance concepts, and communication profiles.\n"
                        + "It is the standards reference used for how DLMS/COSEM systems interoperate at the architectural and transport-profile level.\n"
                        + "It complements the Blue Book, which focuses more on COSEM interface classes, object models, and data semantics.\n";
            }
            case PROTOCOL_HDLC_STRUCTURE ->
                    "An HDLC frame is a link-layer envelope bounded by opening and closing flag bytes.\n"
                    + "Its major fields are the frame format/length field, destination and source addressing, the control field, an optional information field, and the frame check sequence (FCS).\n"
                    + "Those fields delimit the frame, manage link state, and carry DLMS APDUs when an I-frame transports application data.\n";
            case SECURITY_HLS ->
                    "HLS is DLMS/COSEM high-level security based on challenge-response proof rather than only a static shared secret.\n"
                    + "A typical HLS sequence is: the client requests HLS in the AARQ, the server responds in the AARE and supplies challenge/context, the client computes a cryptographic reply, the server verifies it and may return its own authenticated response, and normal traffic continues only after successful verification.\n"
                    + "Depending on the negotiated suite and mechanism, that proof can use GMAC or AES-GCM-128 based security rather than plain-text password checks.\n"
                    + "Use HLS when the deployment needs stronger mutual authentication and better replay resistance than LLS alone.\n";
            case SECURITY_SUITE ->
                    "DLMS security suite 1 provides both authentication and encryption.\n"
                    + "Its standard authenticated-encryption mechanism is AES-GCM-128.\n"
                    + "In practice, suite 1 combines confidentiality, integrity, and freshness checks so protected messages cannot simply be replayed with stale counters.\n";
            case SECURITY_REPLAY ->
                    "Replay protection in DLMS relies on a frame or invocation counter carried in the security header.\n"
                    + "The receiver accepts only fresh, monotonically increasing counter values and rejects stale or reused ones as replay attempts.\n"
                    + "That freshness check is evaluated together with the authenticated security envelope, not as a standalone transport heuristic.\n";
            case DECODE_HDLC_U_FRAME,
                 DECODE_HDLC_S_FRAME,
                 DECODE_HDLC_TENTATIVE_OUTER_ROLE,
                 DECODE_APDU_OPERATION,
                 DECODE_AXDR_VALUE,
                 DECODE_OBIS_LOOKUP,
                 SICONIA_ALARM_SUMMARY,
                 SICONIA_LOG_SUMMARY,
                 SICONIA_XML_SUMMARY -> "";
            case NONE -> "";
        };
    }

    private boolean shouldPreferGroundedFallback(AnswerTopicFamily family) {
        return switch (family) {
            case ASSOCIATION_APDUS,
                 ASSOCIATION_DIAGNOSTIC,
                 STANDARDS_BOOK,
                 PROTOCOL_HDLC_STRUCTURE,
                 SECURITY_HLS,
                 SECURITY_SUITE,
                 SECURITY_REPLAY -> true;
            case NONE,
                 DECODE_HDLC_U_FRAME,
                 DECODE_HDLC_S_FRAME,
                 DECODE_HDLC_TENTATIVE_OUTER_ROLE,
                 DECODE_APDU_OPERATION,
                 DECODE_AXDR_VALUE,
                 DECODE_OBIS_LOOKUP,
                 SICONIA_ALARM_SUMMARY,
                 SICONIA_LOG_SUMMARY,
                 SICONIA_XML_SUMMARY -> false;
        };
    }

    private boolean containsAll(String value, String... required) {
        for (String token : required) {
            if (!value.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsPhrase(String value, String phrase) {
        return value.contains(phrase) || value.contains(phrase.replace("-", " "));
    }

    private boolean containsUnsupportedUFrameSpeculation(WorkflowState state, String lower) {
        if (!(state.decodeResult() instanceof DecodeResult decodeResult) || decodeResult.hdlcFrame() == null) {
            return false;
        }
        if (lower.contains("srr")
                || STANDALONE_RCV.matcher(lower).find()
                || lower.contains("snrm reply")
                || lower.contains("reply from server")
                || lower.contains("server snrm")) {
            return true;
        }
        UFrameType subtype = decodeResult.hdlcFrame().uFrameType();
        if (subtype == UFrameType.SNRM) {
            return STANDALONE_ACK.matcher(lower).find()
                    || lower.contains("server response")
                    || SNRM_UA_REPLY_SPECULATION.matcher(lower).find()
                    || SNRM_NORMAL_RESPONSE_MODE_OVERCLAIM.matcher(lower).find()
                    || SNRM_APDU_READINESS_OVERCLAIM.matcher(lower).find();
        }
        return false;
    }

    private boolean containsTentativeOuterRoleOverreach(String lower) {
        return TENTATIVE_SERVER_CLIENT_ATTRIBUTION.matcher(lower).find()
                || TENTATIVE_PREVIOUS_REQUEST_SPECULATION.matcher(lower).find();
    }

    private boolean contradictsGetResponseSemantics(WorkflowState state, String lower) {
        if (!(state.decodeResult() instanceof DecodeResult decodeResult) || decodeResult.apduType() != ApduType.GET_RESPONSE) {
            return false;
        }
        if (GET_RESPONSE_AS_REQUEST.matcher(lower).find() || GET_RESPONSE_CLIENT_REQUEST.matcher(lower).find()) {
            return true;
        }
        if (containsActiveEnergyObis(decodeResult) && lower.contains("active power")) {
            return true;
        }
        if (isObjectIdentifierOnlyGetResponse(decodeResult)
                && GET_RESPONSE_OBJECT_VALUE_OVERCLAIM.matcher(lower).find()) {
            return true;
        }
        return INVENTED_METER_READING.matcher(lower).find();
    }

    private boolean containsActiveEnergyObis(DecodeResult decodeResult) {
        return decodeResult.obisResolutions() != null
                && decodeResult.obisResolutions().stream().anyMatch(resolution -> "1.0.1.8.0.255".equals(resolution.obis()));
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

    private int sentenceCount(String value) {
        String[] parts = value.split("(?<=[.!?])\\s+");
        int count = 0;
        for (String part : parts) {
            if (!part.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private int inferredAlarmCount(GroundedFactBundle bundle) {
        if (bundle == null || bundle.authoritativeFacts() == null || bundle.authoritativeFacts().isEmpty()) {
            return 0;
        }
        Matcher matcher = LEADING_INTEGER.matcher(bundle.authoritativeFacts().getFirst());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return bundle.authoritativeFacts().size() > 3 ? bundle.authoritativeFacts().size() - 1 : 1;
    }

    private boolean startsWithDisallowedQuotedOpening(String rawValue, String lowerValue) {
        String normalized = lowerValue == null ? "" : lowerValue.trim();
        for (String disallowed : DISALLOWED_QUOTED_OPENINGS) {
            if (normalized.startsWith(disallowed)) {
                return true;
            }
        }
        if (normalized.isBlank()) {
            return false;
        }
        char first = rawValue == null || rawValue.isBlank() ? '\0' : rawValue.trim().charAt(0);
        if (LEADING_QUOTES.indexOf(first) >= 0) {
            return true;
        }
        return DISALLOWED_DOCUMENT_OPENING.matcher(normalized).find();
    }
}
