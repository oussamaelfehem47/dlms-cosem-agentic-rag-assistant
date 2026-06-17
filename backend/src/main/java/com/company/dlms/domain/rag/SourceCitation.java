package com.company.dlms.domain.rag;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

public record SourceCitation(
        String docType,
        String sourceFile,
        int pageNumber,
        String sectionTitle,
        String pageTitle,
        String spaceName,
        double spaceWeight,
        String formatted
) implements Serializable {

    public static SourceCitation fromMetadata(Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        String docType = string(safeMetadata, "doc_type");
        String sourceFile = string(safeMetadata, "source_file");
        int pageNumber = integer(safeMetadata, "page_number");
        String sectionTitle = string(safeMetadata, "section_title");
        String pageTitle = string(safeMetadata, "page_title");
        String spaceName = string(safeMetadata, "space_name");
        double spaceWeight = doubleVal(safeMetadata, "space_weight", 1.0);
        String formatted = buildFormatted(docType, pageTitle, spaceName, sourceFile, sectionTitle);
        return new SourceCitation(docType, sourceFile, pageNumber, sectionTitle, pageTitle, spaceName, spaceWeight, formatted);
    }

    private static String buildFormatted(
            String docType,
            String pageTitle,
            String spaceName,
            String sourceFile,
            String sectionTitle
    ) {
        if (isConfluenceCitation(docType, pageTitle, spaceName, sourceFile)) {
            String title = hasText(pageTitle) ? pageTitle : sourceFile;
            String space = hasText(spaceName) ? spaceName : "Unknown";
            return "Confluence \u2014 " + title + " (" + space + ")";
        }
        String section = hasText(sectionTitle) ? " \u2014 \u00A7" + sectionTitle : "";
        return "DLMS Standard" + section;
    }

    private static boolean isConfluenceCitation(
            String docType,
            String pageTitle,
            String spaceName,
            String sourceFile
    ) {
        if ("confluence".equalsIgnoreCase(docType)) {
            return true;
        }

        String normalizedSource = sourceFile == null ? "" : sourceFile.toLowerCase(Locale.ROOT);
        boolean htmlSource = normalizedSource.endsWith(".html") || normalizedSource.endsWith(".htm");
        return hasText(pageTitle) || hasText(spaceName) || htmlSource;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : "";
    }

    private static int integer(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static double doubleVal(Map<String, Object> metadata, String key, double defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
