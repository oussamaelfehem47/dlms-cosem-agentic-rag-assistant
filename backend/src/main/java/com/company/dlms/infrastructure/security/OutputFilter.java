package com.company.dlms.infrastructure.security;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OutputFilter {

    private static final Pattern HEX_32_OR_64 = Pattern.compile("\\b(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{64})\\b");
    private static final Pattern CONFIDENCE_LEAK = Pattern.compile("(?i)\\bconfidence\\s*:\\s*\\d+(?:\\.\\d+)?\\.?\\s*");
    private static final Pattern SUITE_NUMBER = Pattern.compile("(?i)\\bsuite\\s*([0-9])\\b");
    private static final Pattern WITH_BIT_LENGTH = Pattern.compile("(?i)\\bwith(?=\\d+-bit\\b)");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{2,6}\\s+(.+?)\\s*$");
    private static final Pattern SIMPLE_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern GLUED_SOURCES_FOOTER = Pattern.compile("(?i)([.!?])\\s*Sources:\\s*");
    private static final Pattern EMPTY_SOURCES_LINE = Pattern.compile("(?im)^Sources\\s*:?\\s*$");
    private static final Pattern NUMBERED_SOURCES_BLOCK = Pattern.compile(
            "(?is)(?:\\n|^)Sources\\s*:?[ \\t]*(?:\\r?\\n\\s*\\d+\\s*\\r?\\n\\s*[^\\r\\n]+)+\\s*$"
    );
    private static final Pattern TRAILING_NUMBERED_SOURCES_RESIDUE = Pattern.compile(
            "(?is)\\n+Sources\\s*:?\\s*(?:\\r?\\n|\\s+)\\d+\\s*\\r?\\n\\s*[^\\r\\n]+(?:\\r?\\n\\s*\\d+\\s*\\r?\\n\\s*[^\\r\\n]+)*\\s*$"
    );
    private static final Pattern DLMS_CITATION_LABEL = Pattern.compile("DLMS Standard\\s+[^\\p{Alnum}§\\n]+");
    private static final Pattern DLMS_CITATION_SECTION = Pattern.compile("DLMS Standard\\s+—\\s*§*");
    private static final Pattern CONFLUENCE_CITATION_LABEL = Pattern.compile("Confluence\\s+[^\\p{Alnum}(\\n]+");
    private static final Pattern MALICIOUS_GUIDANCE = Pattern.compile(
            "(?i)\\b(?:how\\s+to|steps?\\s+to|instructions?\\s+to|guide\\s+to|ways?\\s+to|launch\\s+an?|execute\\s+an?)\\s+"
                    + "(?:exploit|bypass|attack|payload|inject|weaponize)\\b|\\b(?:exploit|bypass)\\s+(?:this|the)\\b|\\b(?:attack|exploit)\\s+steps?\\b|\\bmalicious\\s+payload\\b");
    private static final Pattern SECURITY_BYPASS_GUIDANCE = Pattern.compile(
            "(?i)\\b(?:disable|circumvent|evade)\\b.{0,24}\\b(?:auth|authentication|security|filter|protection)\\b");
    private static final Pattern SECRET_INSTRUCTION = Pattern.compile(
            "(?is)\\b(?:set|configure|use|supply)\\b.{0,48}\\b(?:key|password|secret|token|challenge)\\b.{0,80}\\b[0-9a-fA-F]{16,}\\b");

    public FilterResult filter(String content) {
        String safeContent = content == null ? "" : content;

        if (MALICIOUS_GUIDANCE.matcher(safeContent).find()
                || SECURITY_BYPASS_GUIDANCE.matcher(safeContent).find()
                || SECRET_INSTRUCTION.matcher(safeContent).find()) {
            return new FilterResult(true, true, "", "BLOCKED: unsafe output detected");
        }

        String normalized = normalizeFormattingArtifacts(safeContent);
        String redacted = HEX_32_OR_64.matcher(normalized).replaceAll("[REDACTED-KEY]");
        boolean filtered = !redacted.equals(safeContent);
        return new FilterResult(filtered, false, redacted, filtered ? "REDACTED" : "");
    }

    private String normalizeFormattingArtifacts(String content) {
        String normalized = CONFIDENCE_LEAK.matcher(content).replaceAll("");
        normalized = normalizeMojibake(normalized);
        normalized = normalized.replace("â€”", "—").replace("Â§", "§").replace('\uFFFD', ' ');
        normalized = DLMS_CITATION_LABEL.matcher(normalized).replaceAll("DLMS Standard — §");
        normalized = DLMS_CITATION_SECTION.matcher(normalized).replaceAll("DLMS Standard — §");
        normalized = CONFLUENCE_CITATION_LABEL.matcher(normalized).replaceAll("Confluence — ");
        normalized = flattenMarkdownHeadings(normalized);
        normalized = SIMPLE_BOLD.matcher(normalized).replaceAll("$1");
        normalized = SUITE_NUMBER.matcher(normalized).replaceAll("suite $1");
        normalized = WITH_BIT_LENGTH.matcher(normalized).replaceAll("with ");
        normalized = normalized.replace("\\r\\n", "\n").replace("\\n", "\n");
        normalized = GLUED_SOURCES_FOOTER.matcher(normalized).replaceAll("$1\n\nSources: ");
        normalized = NUMBERED_SOURCES_BLOCK.matcher(normalized).replaceAll("");
        normalized = TRAILING_NUMBERED_SOURCES_RESIDUE.matcher(normalized).replaceAll("");
        normalized = EMPTY_SOURCES_LINE.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("[ \\t]{2,}", " ");
        normalized = normalized.replaceAll(" ?\\n ?", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        normalized = normalized.replaceAll("\\s+([.,;:])", "$1");
        return normalized.trim();
    }

    private String normalizeMojibake(String content) {
        return content
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â", "—")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â", "—")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â", "—")
                .replace("ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â", "—")
                .replace("ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â", "—")
                .replace("Ã¢â‚¬â€", "—")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§", "§")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§", "§")
                .replace("ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§", "§")
                .replace("ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§", "§")
                .replace("Ãƒâ€šÃ‚Â§", "§")
                .replace("Ã‚Â§", "§")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ ", " ")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¹ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ", "'")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¾ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢", "'")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã¢â‚¬Å“", "\"")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â", "\"")
                .replace("ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢", "->");
    }

    private String flattenMarkdownHeadings(String content) {
        Matcher matcher = MARKDOWN_HEADING.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String heading = matcher.group(1).trim();
            if (!heading.endsWith(":")) {
                heading = heading + ":";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(heading));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public record FilterResult(boolean filtered, boolean blocked, String content, String reason) {}
}
