package com.company.dlms.agent.siconia;

import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.siconia.ParseProvenance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SiconiaInputNormalizer {

    private static final Pattern HEX_ALARM_CODE = Pattern.compile("(0x|0X)[0-9A-Fa-f]{1,8}");
    private static final Pattern NAMED_ALARM_CODE = Pattern.compile("\\b(?=.*[G-Z_])[A-Z][A-Z0-9_]{2,39}\\b");
    private static final Pattern EXACT_NAMED_ALARM_CODE = Pattern.compile("^(?=.*[G-Z_])[A-Z][A-Z0-9_]{2,39}$");
    private static final Pattern HEX_ALARM_CONTEXT = Pattern.compile(
            "\\b(alarm|severity|critical|high|medium|low|info|root\\s*cause|remediation|fault|error\\s*code)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAMED_ALARM_CONTEXT = Pattern.compile(
            "\\b(alarm|severity|critical|high|medium|low|info|root\\s*cause|remediation|fault|error\\s*code|what\\s+does|mean(?:ing)?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DLMS_PROTOCOL_CONTEXT = Pattern.compile(
            "\\b(hdlc|frame|obis|dlms|cosem|green\\s*book|blue\\s*book|iec\\s*62056|aarq|aare|apdu|axdr|security|suite)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\s*\\d{4}[-/]\\d{2}[-/]\\d{2}.*\\[(WAN|PLC|RF|HES|DLMS)\\].*(ERROR|WARN|INFO|DEBUG).*",
            Pattern.CASE_INSENSITIVE
    );

    public SiconiaInputNormalization normalize(String rawInput, InputClass hintedInputClass) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            return null;
        }

        SiconiaInputNormalization xml = normalizeXml(input, hintedInputClass);
        if (xml != null) {
            return xml;
        }

        SiconiaInputNormalization log = normalizeLog(input, hintedInputClass);
        if (log != null) {
            return log;
        }

        return normalizeAlarm(input, hintedInputClass);
    }

    private SiconiaInputNormalization normalizeXml(String input, InputClass hintedInputClass) {
        String direct = extractDirectXml(input);
        if (direct != null) {
            ParseProvenance provenance = input.equals(direct)
                    ? ParseProvenance.STRUCTURED_DIRECT
                    : ParseProvenance.STRUCTURED_HEURISTIC;
            return new SiconiaInputNormalization(
                    direct,
                    InputClass.XML_TRACE,
                    provenance,
                    List.of(),
                    provenance == ParseProvenance.STRUCTURED_HEURISTIC ? "Recovered embedded XML from wrapped prose input" : null
            );
        }

        if (hintedInputClass == InputClass.XML_TRACE) {
            return new SiconiaInputNormalization(
                    input,
                    InputClass.XML_TRACE,
                    ParseProvenance.STRUCTURED_DIRECT,
                    List.of(),
                    null
            );
        }

        return null;
    }

    private SiconiaInputNormalization normalizeAlarm(String input, InputClass hintedInputClass) {
        Matcher hex = HEX_ALARM_CODE.matcher(input);
        if (hex.find()
                && (hex.group().equals(input)
                || (HEX_ALARM_CONTEXT.matcher(input).find() && !DLMS_PROTOCOL_CONTEXT.matcher(input).find()))) {
            String code = hex.group();
            ParseProvenance provenance = code.equals(input)
                    ? ParseProvenance.STRUCTURED_DIRECT
                    : ParseProvenance.STRUCTURED_HEURISTIC;
            return new SiconiaInputNormalization(
                    code,
                    InputClass.ALARM_CODE,
                    provenance,
                    List.of(),
                    provenance == ParseProvenance.STRUCTURED_HEURISTIC ? "Extracted embedded alarm code from prose input" : null
            );
        }

        Matcher named = NAMED_ALARM_CODE.matcher(input);
        boolean exactNamedAlarm = EXACT_NAMED_ALARM_CODE.matcher(input).matches();
        if (named.find()
                && (exactNamedAlarm
                || (NAMED_ALARM_CONTEXT.matcher(input).find() && !DLMS_PROTOCOL_CONTEXT.matcher(input).find()))) {
            String code = named.group();
            ParseProvenance provenance = code.equals(input)
                    ? ParseProvenance.STRUCTURED_DIRECT
                    : ParseProvenance.STRUCTURED_HEURISTIC;
            return new SiconiaInputNormalization(
                    code,
                    InputClass.ALARM_CODE,
                    provenance,
                    List.of(),
                    provenance == ParseProvenance.STRUCTURED_HEURISTIC ? "Extracted named alarm token from prose input" : null
            );
        }

        if (hintedInputClass == InputClass.ALARM_CODE) {
            return new SiconiaInputNormalization(
                    input,
                    InputClass.ALARM_CODE,
                    ParseProvenance.STRUCTURED_DIRECT,
                    List.of(),
                    null
            );
        }

        return null;
    }

    private SiconiaInputNormalization normalizeLog(String input, InputClass hintedInputClass) {
        String[] lines = input.split("\\R");
        List<String> nonEmptyLines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonEmptyLines.add(trimmed);
            if (LOG_LINE.matcher(trimmed).matches()) {
                logLines.add(trimmed);
            }
        }

        boolean direct = !nonEmptyLines.isEmpty() && logLines.size() == nonEmptyLines.size();
        boolean heuristic = !logLines.isEmpty() && logLines.size() < nonEmptyLines.size();
        boolean multilineSignal = nonEmptyLines.size() >= 2 && countKeywordHits(nonEmptyLines) >= 2;

        if (direct || heuristic || hintedInputClass == InputClass.LOG_BLOCK || multilineSignal) {
            String normalized = !logLines.isEmpty()
                    ? logLines.stream().collect(Collectors.joining("\n"))
                    : input;
            ParseProvenance provenance = direct || hintedInputClass == InputClass.LOG_BLOCK
                    ? ParseProvenance.STRUCTURED_DIRECT
                    : ParseProvenance.STRUCTURED_HEURISTIC;
            return new SiconiaInputNormalization(
                    normalized,
                    InputClass.LOG_BLOCK,
                    provenance,
                    List.of(),
                    provenance == ParseProvenance.STRUCTURED_HEURISTIC ? "Recovered dominant log block from wrapped prose input" : null
            );
        }

        return null;
    }

    private String extractDirectXml(String input) {
        if (input.startsWith("<") && input.contains(">")) {
            return input;
        }
        int firstTag = input.indexOf('<');
        int lastTag = input.lastIndexOf('>');
        if (firstTag >= 0 && lastTag > firstTag) {
            String candidate = input.substring(firstTag, lastTag + 1).trim();
            String lower = candidate.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("<") && lower.contains("</")) || lower.endsWith("/>")) {
                return candidate;
            }
        }
        return null;
    }

    private int countKeywordHits(List<String> lines) {
        int hits = 0;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("warn") || lower.contains("info")
                    || lower.contains("debug") || lower.contains("alarm") || lower.contains("timeout")) {
                hits++;
            }
        }
        return hits;
    }
}
