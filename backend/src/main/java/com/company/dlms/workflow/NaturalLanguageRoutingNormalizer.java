package com.company.dlms.workflow;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routing-only typo recovery for natural-language prompts.
 * This helper must never be used as a payload normalizer.
 */
public final class NaturalLanguageRoutingNormalizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\S+");
    private static final Pattern OBIS_PATTERN = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){5}$");
    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)^(?:0x)?[0-9a-f]{2,}$");
    private static final Pattern ALARM_PATTERN = Pattern.compile("(?i)^0x[0-9a-f]{2,8}$");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}.*$");
    private static final Pattern LETTER_PATTERN = Pattern.compile(".*[A-Za-z].*");

    private static final Set<String> ROUTING_VOCABULARY = Set.of(
            "what", "how", "why", "explain", "describe", "tell", "can", "does", "did",
            "difference", "differences", "define", "artifact", "compare",
            "mean", "frame", "response", "returned", "connection", "established", "last", "previous",
            "obis", "dlms", "hdlc", "apdu", "axdr", "aarq", "aare", "hls", "lls", "gmac",
            "replay", "security", "suite"
    );
    private static final Map<String, String> APPROVED_TYPO_CORRECTIONS = Map.ofEntries(
            Map.entry("hat", "what"),
            Map.entry("waht", "what"),
            Map.entry("wht", "what"),
            Map.entry("deos", "does"),
            Map.entry("repsonse", "response"),
            Map.entry("fram", "frame"),
            Map.entry("artifat", "artifact"),
            Map.entry("conection", "connection"),
            Map.entry("esablished", "established"),
            Map.entry("diference", "difference"),
            Map.entry("diferences", "differences")
    );

    private NaturalLanguageRoutingNormalizer() {
    }

    public static String normalize(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return rawInput == null ? "" : rawInput;
        }

        StringBuilder normalized = new StringBuilder(rawInput.length());
        Matcher matcher = TOKEN_PATTERN.matcher(rawInput);
        int lastEnd = 0;
        while (matcher.find()) {
            normalized.append(rawInput, lastEnd, matcher.start());
            normalized.append(normalizeToken(matcher.group()));
            lastEnd = matcher.end();
        }
        normalized.append(rawInput.substring(lastEnd));
        return normalized.toString();
    }

    private static String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return token;
        }
        if (looksStructuredToken(token)) {
            return token;
        }

        int start = firstCoreIndex(token);
        int end = lastCoreIndex(token);
        if (start < 0 || end < start) {
            return token;
        }

        String prefix = token.substring(0, start);
        String core = token.substring(start, end + 1);
        String suffix = token.substring(end + 1);

        if (!LETTER_PATTERN.matcher(core).matches()) {
            return token;
        }
        if (looksStructuredCore(core)) {
            return token;
        }

        String lowerCore = core.toLowerCase(Locale.ROOT);
        if (ROUTING_VOCABULARY.contains(lowerCore)) {
            return token;
        }

        String correction = approvedCorrection(lowerCore);
        if (correction == null) {
            return token;
        }

        return prefix + applyCasePattern(correction, core) + suffix;
    }

    private static boolean looksStructuredToken(String token) {
        return token.indexOf('<') >= 0
                || token.indexOf('>') >= 0
                || token.indexOf('[') >= 0
                || token.indexOf(']') >= 0
                || token.indexOf('/') >= 0
                || TIMESTAMP_PATTERN.matcher(token).matches();
    }

    private static boolean looksStructuredCore(String core) {
        String lower = core.toLowerCase(Locale.ROOT);
        return core.indexOf('.') >= 0
                || core.indexOf(':') >= 0
                || ALARM_PATTERN.matcher(lower).matches()
                || OBIS_PATTERN.matcher(core).matches()
                || HEX_PATTERN.matcher(lower).matches()
                || core.chars().anyMatch(Character::isDigit);
    }

    private static int firstCoreIndex(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastCoreIndex(String token) {
        for (int i = token.length() - 1; i >= 0; i--) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return i;
            }
        }
        return -1;
    }

    private static String approvedCorrection(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (!ROUTING_VOCABULARY.contains(token) && APPROVED_TYPO_CORRECTIONS.containsKey(token)) {
            return APPROVED_TYPO_CORRECTIONS.get(token);
        }
        return null;
    }

    private static String applyCasePattern(String replacement, String template) {
        if (template.equals(template.toUpperCase(Locale.ROOT))) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(template.charAt(0))
                && template.substring(1).equals(template.substring(1).toLowerCase(Locale.ROOT))) {
            return replacement.substring(0, 1).toUpperCase(Locale.ROOT)
                    + replacement.substring(1);
        }
        return replacement;
    }
}
