package com.company.dlms.infrastructure.rag;

import java.util.Locale;
import java.util.regex.Pattern;

final class SearchTextNormalizer {

    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[\\p{Punct}\\s]+");
    private static final Pattern SEPARATORS = Pattern.compile("[-_&]+");
    private static final Pattern VERSION_NOISE = Pattern.compile("\\bvv?\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_PUNCTUATION = Pattern.compile("[\\p{Punct}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SearchTextNormalizer() {
    }

    static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String normalized = input.toLowerCase(Locale.ROOT);
        normalized = LEADING_PUNCTUATION.matcher(normalized).replaceFirst("");
        normalized = SEPARATORS.matcher(normalized).replaceAll(" ");
        normalized = VERSION_NOISE.matcher(normalized).replaceAll(" ");
        normalized = OTHER_PUNCTUATION.matcher(normalized).replaceAll(" ");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }
}
