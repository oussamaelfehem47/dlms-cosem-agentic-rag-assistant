package com.company.dlms.agent;

import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.RouterResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RouterAgent {

    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    private static final Pattern HEX_FRAME_PATTERN = Pattern.compile("^([0-9A-F]{2}[\\s]?)+$");
    // Named alarm codes must contain at least one non-hex character (G-Z) or underscore
    // to avoid matching pure hex strings like "A023210313A5E57E"
    private static final Pattern ALARM_CODE_PATTERN = Pattern.compile("^(?=.*[G-Z_])[A-Z][A-Z0-9_]{2,39}$");
    private static final Pattern HEX_ALARM_CODE_PATTERN = Pattern.compile("^(0x|0X)[0-9A-Fa-f]+$");
    private static final Pattern HEX_ALARM_TOKEN_PATTERN = Pattern.compile("\\b(0x|0X)[0-9A-Fa-f]{1,8}\\b");
    private static final Pattern NAMED_ALARM_TOKEN_PATTERN = Pattern.compile("\\b(?=.*[G-Z_])[A-Z][A-Z0-9_]{2,39}\\b");
    private static final Pattern OBIS_NOTATION_PATTERN =
            Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern HEX_ALARM_CONTEXT_PATTERN = Pattern.compile(
            "\\b(alarm|severity|critical|high|medium|low|info|root\\s*cause|remediation|fault|error\\s*code)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAMED_ALARM_CONTEXT_PATTERN = Pattern.compile(
            "\\b(alarm|severity|critical|high|medium|low|info|root\\s*cause|remediation|fault|error\\s*code|what\\s+does|mean(?:ing)?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DLMS_PROTOCOL_CONTEXT_PATTERN = Pattern.compile(
            "\\b(hdlc|frame|obis|dlms|cosem|green\\s*book|blue\\s*book|iec\\s*62056|aarq|aare|apdu|axdr|security|suite)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Single-line log block pattern: timestamp + [LAYER] + severity
    // Matches: "2026-04-30 10:15:30 [WAN] ERROR Connection timeout"
    // Also matches: "2026/04/30 10:15:30 [PLC] WARN Signal degradation"
    private static final Pattern LOG_SINGLE_LINE_PATTERN = Pattern.compile(
            "\\d{4}[-/]\\d{2}[-/]\\d{2}.*\\[(WAN|PLC|RF|HES|DLMS)\\].*(ERROR|WARN|INFO|DEBUG)",
            Pattern.CASE_INSENSITIVE
    );

    // Intent patterns (lowercased input)
    private static final Pattern P_FRAME_REFERENCE = Pattern.compile(
            "\\b(hdlc|frame|snrm|disc|ua\\b|aarq|aare|rlrq|rlre|frame\\s+structure|frame\\s+format)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_DECODE_IMPERATIVE = Pattern.compile(
            "\\b(decode|parse|analy[sz]e|interpret)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_OBIS_LOOKUP = Pattern.compile(
            "\\b(obis)\\b|\\b(energy|voltage|current|power|frequency|active|reactive|apparent)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_APDU_ANALYSIS = Pattern.compile(
            "\\b(apdu|get-request|set-request|action-request|get-response|data-notification)\\b|\\b(invoke.?id|pdu|axdr|tlv|tag\\s+0x)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_SICONIA = Pattern.compile(
            "\\b(siconia|hes|dcu|concentrator|alarm|fault|error.?code|disconnected|unreachable)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_SICONIA_TROUBLESHOOT = Pattern.compile(
            "\\b(troubleshoot|root\\s*cause|why|fix|failure|failed|issue|problem|timeout|retry|reject|"
                    + "disconnected|unreachable|alarm|fault|error|cannot|can't|unable)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_PROFILE = Pattern.compile(
            "\\b(load.?profile|billing.?profile|profile.?generic|capture.?object|interval)\\b|\\b(ic\\s*7|interface.?class\\s*7|class\\s*7)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_SECURITY_EXPLAIN = Pattern.compile(
            "\\b(lls|hls|gmac|authentication|security\\s*suite|encryption|frame\\s*counter|replay|challenge|key\\s*agreement|suite\\s*[0-3]|ciphering|security\\s*policy|wrong\\s*password|password|counter\\s*mismatch|association\\s*rejected|aare\\s*diagnostic|diagnostic\\s*\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern P_DOCUMENTATION = Pattern.compile(
            "\\b(what\\s+is|what\\s+are|what\\s+does|explain|describe|how\\s+does|define|definition|meaning|meaning\\s+of|difference(?:s)?\\s+between|standard|blue.?book|green.?book|structure|format|framing|can\\s+you\\s+explain|tell\\s+me)\\b|\\b(clause|section|iec\\s*62056|cosem|dlms.?ua)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public RouterResult route(String rawInput) {
        return route(rawInput, null);
    }

    public RouterResult route(String rawInput, InputClass hintedInputClass) {
        return route(rawInput, hintedInputClass, null);
    }

    public RouterResult route(String rawInput, InputClass hintedInputClass, DlmsInputNormalization dlmsNormalization) {
        String input = rawInput == null ? "" : rawInput;
        String trimmed = input.trim();

        InputClass inputClass = resolveInputClass(trimmed, hintedInputClass, dlmsNormalization);
        DlmsIntent intent = detectIntent(trimmed, inputClass, dlmsNormalization);

        log.info("RouterAgent: input='{}' detected inputClass={} intent={}", trimmed, inputClass, intent);

        return new RouterResult(intent, inputClass);
    }

    private InputClass resolveInputClass(String trimmed, InputClass hintedInputClass, DlmsInputNormalization dlmsNormalization) {
        if (dlmsNormalization != null
                && dlmsNormalization.kind() == DlmsNormalizedKind.FRAME_HEX
                && !dlmsNormalization.ambiguous()) {
            return InputClass.HEX_FRAME;
        }
        return hintedInputClass != null && hintedInputClass != InputClass.QUERY
                ? hintedInputClass
                : detectInputClass(trimmed);
    }

    private InputClass detectInputClass(String trimmed) {
        if (trimmed.isEmpty()) return InputClass.QUERY;

        String upper = trimmed.toUpperCase(Locale.ROOT);
        String compact = upper.replace(" ", "");

        // 1. Check for XML (must start with tag)
        if (trimmed.startsWith("<") && trimmed.contains(">")) {
            return InputClass.XML_TRACE;
        }

        // 2. Check for HDLC frame (strict match)
        if (HEX_FRAME_PATTERN.matcher(upper).matches()
                && compact.startsWith("7E")
                && compact.length() >= 14) {
            return InputClass.HEX_FRAME;
        }

        // 3. Log Block (multiline/long)
        if (trimmed.contains("\n") && trimmed.length() > 100) {
            return InputClass.LOG_BLOCK;
        }

        // 4. Log Block (single-line with timestamp + [LAYER] + severity)
        // Matches patterns like: "2026-04-30 10:15:30 [WAN] ERROR Connection timeout"
        if (LOG_SINGLE_LINE_PATTERN.matcher(trimmed).find()) {
            return InputClass.LOG_BLOCK;
        }

        // 5. Check for Alarm Codes (relaxed search)
        // Check for hex alarm codes (0x1342) anywhere in the string
        if (HEX_ALARM_CODE_PATTERN.matcher(trimmed).matches()) {
            return InputClass.ALARM_CODE;
        }
        if (HEX_ALARM_TOKEN_PATTERN.matcher(trimmed).find()
                && HEX_ALARM_CONTEXT_PATTERN.matcher(trimmed).find()
                && !DLMS_PROTOCOL_CONTEXT_PATTERN.matcher(trimmed).find()) {
            return InputClass.ALARM_CODE;
        }
        // Check for named alarm codes using whole-input or explicit alarm context only.
        if (ALARM_CODE_PATTERN.matcher(trimmed).matches()) {
            return InputClass.ALARM_CODE;
        }
        if (NAMED_ALARM_TOKEN_PATTERN.matcher(trimmed).find()
                && NAMED_ALARM_CONTEXT_PATTERN.matcher(trimmed).find()
                && !DLMS_PROTOCOL_CONTEXT_PATTERN.matcher(trimmed).find()) {
            return InputClass.ALARM_CODE;
        }

        // 6. Query fallback (pure hex check to avoid false positives for codes vs queries)
        if (upper.matches("^[0-9A-F\\s]+$") && !compact.startsWith("7E")) {
            return InputClass.QUERY;
        }

        return InputClass.QUERY;
    }

    private DlmsIntent detectIntent(String trimmed, InputClass inputClass, DlmsInputNormalization dlmsNormalization) {
        if (dlmsNormalization != null) {
            return switch (dlmsNormalization.kind()) {
                case FRAME_HEX -> DlmsIntent.FRAME_DECODE;
                case APDU_HEX, AXDR_HEX -> DlmsIntent.APDU_ANALYSIS;
                case OBIS_QUERY -> DlmsIntent.OBIS_LOOKUP;
            };
        }
        if (inputClass == InputClass.HEX_FRAME) {
            return DlmsIntent.FRAME_DECODE;
        }
        if (inputClass == InputClass.XML_TRACE) {
            return DlmsIntent.SICONIA_TROUBLESHOOT;
        }
        if (inputClass == InputClass.ALARM_CODE || inputClass == InputClass.LOG_BLOCK) {
            return DlmsIntent.SICONIA_TROUBLESHOOT;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        boolean frameReference = P_FRAME_REFERENCE.matcher(lower).find();
        boolean decodeImperative = P_DECODE_IMPERATIVE.matcher(lower).find();
        boolean apdu = P_APDU_ANALYSIS.matcher(lower).find();
        boolean obis = OBIS_NOTATION_PATTERN.matcher(lower).find() || P_OBIS_LOOKUP.matcher(lower).find();
        boolean siconia = P_SICONIA.matcher(lower).find();
        boolean siconiaTroubleshooting = siconia && P_SICONIA_TROUBLESHOOT.matcher(lower).find();
        boolean profile = P_PROFILE.matcher(lower).find();
        boolean security = P_SECURITY_EXPLAIN.matcher(lower).find();
        boolean doc = P_DOCUMENTATION.matcher(lower).find();
        boolean conceptualProtocolDocumentation = doc && (frameReference || apdu);
        boolean siconiaDocumentation = siconia && !siconiaTroubleshooting;

        // Priority order (checked in order), with explicit tie-breakers:
        // - SECURITY_EXPLAIN beats generic documentation and AARQ/AARE keyword matches
        // - conceptual protocol explanations beat decode bias unless the query is imperative
        // - OBIS_LOOKUP vs DOCUMENTATION => OBIS_LOOKUP
        if (security) {
            return DlmsIntent.SECURITY_EXPLAIN;
        }
        if (obis) {
            return DlmsIntent.OBIS_LOOKUP;
        }
        if (conceptualProtocolDocumentation && !decodeImperative) {
            return DlmsIntent.DOCUMENTATION;
        }
        if (decodeImperative && frameReference) {
            return DlmsIntent.FRAME_DECODE;
        }
        if (apdu) {
            return DlmsIntent.APDU_ANALYSIS;
        }
        if (frameReference) {
            return DlmsIntent.FRAME_DECODE;
        }
        if (siconiaDocumentation) {
            return DlmsIntent.DOCUMENTATION;
        }
        if (siconia) {
            return DlmsIntent.SICONIA_TROUBLESHOOT;
        }
        if (profile) {
            return DlmsIntent.PROFILE_DECODE;
        }
        if (doc) {
            return DlmsIntent.DOCUMENTATION;
        }

        return DlmsIntent.UNKNOWN;
    }
}
