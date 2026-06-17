package com.company.dlms.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CasualQueryClassifier {

    private static final Pattern OBIS_NOTATION_PATTERN =
            Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    private static final Pattern TECHNICAL_SIGNAL_PATTERN = Pattern.compile(
            "\\b(dlms|cosem|obis|hdlc|apdu|axdr|iec\\s*62056|green\\s+book|blue\\s+book|aarq|aare|rlrq|rlre|"
                    + "snrm|ua\\b|disc|frame|hex|xml|trace|log|alarm|siconia|hes|dcu|concentrator|"
                    + "security|authentication|encryption|hls|lls|gmac|frame\\s*counter|replay|"
                    + "profile|load\\s*profile|billing\\s*profile|capture\\s*object|get-request|set-request|"
                    + "action-request|data-notification|pdu|invoke.?id|meter|parameterization|clock\\s*sync)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CASUAL_COURTESY_PATTERN = Pattern.compile(
            "^(?:"
                    + "(?:hi|hello|hey|good\\s+morning|good\\s+afternoon|good\\s+evening)"
                    + "(?:\\s+(?:there|assistant|team))?"
                    + "|(?:thanks(?:\\s+a\\s+lot)?|many\\s+thanks|thank\\s+you(?:\\s+very\\s+much)?(?:\\s+for\\s+(?:your\\s+)?help)?|thanks\\s+for\\s+(?:the\\s+)?help)"
                    + "|(?:help|please\\s+help|can\\s+you\\s+help|could\\s+you\\s+help|i\\s+need\\s+help)"
                    + ")$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CAPABILITY_QUERY_PATTERN = Pattern.compile(
            "^(?:"
                    + "what\\s+can\\s+you\\s+do"
                    + "|what\\s+you\\s+can(?:\\s+actually)?\\s+do"
                    + "|what\\s+can\\s+you\\s+actually\\s+do"
                    + "|what\\s+can\\s+you\\s+help\\s+with"
                    + "|how\\s+can\\s+you\\s+help"
                    + "|what\\s+do\\s+you\\s+do"
                    + "|tell\\s+me\\s+what\\s+you\\s+can\\s+do(?:\\s+and\\s+what\\s+is\\s+your\\s+role)?"
                    + "|what\\s+is\\s+your\\s+role"
                    + "|who\\s+are\\s+you"
                    + "|tell\\s+me\\s+more"
                    + ")\\??$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern QUESTION_OPENING_PATTERN = Pattern.compile(
            "^(?:"
                    + "what(?:\\s+(?:is|are|does|s))?"
                    + "|how(?:\\s+does)?"
                    + "|why"
                    + "|explain"
                    + "|describe"
                    + "|tell\\s+me"
                    + "|can\\s+you(?:\\s+explain)?"
                    + "|define"
                    + "|difference(?:s)?\\s+between"
                    + ")\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PREVIOUS_FRAME_QUERY_PATTERN = Pattern.compile(
            "\\bframe\\s+type\\b.*\\b(just\\s+sent|last\\s+frame|previous\\s+frame)\\b|\\b(just\\s+sent|last\\s+frame|previous\\s+frame)\\b.*\\bframe\\s+type\\b",
            Pattern.CASE_INSENSITIVE
    );

    private CasualQueryClassifier() {
    }

    public static boolean isCasualNonTechnicalQuery(String rawInput) {
        if (rawInput == null) {
            return false;
        }

        String normalized = normalize(rawInput);
        if (normalized.isBlank()) {
            return false;
        }

        if (hasTechnicalSignal(normalized)) {
            return false;
        }

        return CASUAL_COURTESY_PATTERN.matcher(normalized).matches();
    }

    public static boolean isAssistantCapabilityQuestion(String rawInput) {
        if (rawInput == null) {
            return false;
        }
        String normalized = normalize(rawInput);
        return !normalized.isBlank() && CAPABILITY_QUERY_PATTERN.matcher(normalized).matches();
    }

    public static boolean isQuestionPhrasing(String rawInput) {
        if (rawInput == null) {
            return false;
        }
        String normalized = normalize(rawInput);
        return !normalized.isBlank() && QUESTION_OPENING_PATTERN.matcher(normalized).find();
    }

    public static boolean isPreviousFrameRecallQuestion(String rawInput) {
        if (rawInput == null) {
            return false;
        }
        String normalized = normalize(rawInput);
        return !normalized.isBlank() && PREVIOUS_FRAME_QUERY_PATTERN.matcher(normalized).find();
    }

    private static boolean hasTechnicalSignal(String normalized) {
        return OBIS_NOTATION_PATTERN.matcher(normalized).find()
                || TECHNICAL_SIGNAL_PATTERN.matcher(normalized).find();
    }

    private static String normalize(String rawInput) {
        return rawInput.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}&&[^.]]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
