package com.company.dlms.agent.siconia;

import com.company.dlms.domain.siconia.IssueCategory;
import com.company.dlms.domain.siconia.LogAnalysis;
import com.company.dlms.domain.siconia.LogLayer;
import com.company.dlms.domain.siconia.LogSeverity;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class LogClassifier {

    public LogAnalysis classify(String logBlock) {
        if (logBlock == null || logBlock.isBlank()) {
            return new LogAnalysis(LogLayer.UNKNOWN, LogSeverity.DEBUG, Set.of(), 0, 0);
        }

        String[] lines = logBlock.split("\\R", -1);

        Map<LogLayer, Integer> layerCounts = new EnumMap<>(LogLayer.class);
        for (LogLayer l : LogLayer.values()) layerCounts.put(l, 0);

        LogSeverity highest = LogSeverity.DEBUG;
        Set<IssueCategory> categories = EnumSet.noneOf(IssueCategory.class);
        int lineCount = 0;
        int errorLines = 0;

        for (String line : lines) {
            if (line == null) continue;
            String s = line.stripTrailing();
            if (s.isEmpty()) continue;
            lineCount++;

            String lower = s.toLowerCase(Locale.ROOT);

            Set<LogLayer> matchedLayers = EnumSet.noneOf(LogLayer.class);
            if (containsAny(lower, "wan", "ppp", "ethernet", "ip ", "tcp", "udp", "dns", "http")) matchedLayers.add(LogLayer.WAN);
            if (containsAny(lower, "plc", "g3", "prime", "s-fsk", "powerline")) matchedLayers.add(LogLayer.PLC);
            if (containsAny(lower, "rf", "wireless", "zigbee", "wm-bus", "wmbus", "radio")) matchedLayers.add(LogLayer.RF);
            if (containsAny(lower, "hes", "headend", "head-end", "amr", "mdm")) matchedLayers.add(LogLayer.HES);
            if (containsAny(lower, "dlms", "cosem", "aarq", "aare", "obis", "apdu", "association")) matchedLayers.add(LogLayer.DLMS);

            if (matchedLayers.isEmpty()) matchedLayers.add(LogLayer.UNKNOWN);
            for (LogLayer l : matchedLayers) {
                layerCounts.put(l, layerCounts.getOrDefault(l, 0) + 1);
            }

            LogSeverity sev = classifySeverity(lower);
            highest = LogSeverity.max(highest, sev);
            if (sev == LogSeverity.ERROR) errorLines++;

            if (containsAny(lower, "timeout", "unreachable", "disconnect", "no route")) categories.add(IssueCategory.CONNECTIVITY);
            if (containsAny(lower, "auth", "authentication", "unauthorized", "forbidden", "key")) categories.add(IssueCategory.SECURITY);
            if (containsAny(lower, "crc", "fcs", "checksum", "corrupt", "invalid frame")) categories.add(IssueCategory.FRAME_INTEGRITY);
            if (containsAny(lower, "association", "aarq", "aare", "release")) categories.add(IssueCategory.ASSOCIATION);
            if ((lower.contains("aarq") || lower.contains("aare")) &&
                (lower.contains("reject") || lower.contains("denied") || lower.contains("failed"))) {
                categories.add(IssueCategory.SECURITY);
            }
            if (containsAny(lower, "profile", "capture", "readout", "get-response", "no data")) categories.add(IssueCategory.DATA_RETRIEVAL);
        }

        LogLayer dominant = dominantLayer(layerCounts);
        return new LogAnalysis(dominant, highest, Set.copyOf(categories), lineCount, errorLines);
    }

    private static LogSeverity classifySeverity(String lower) {
        if (containsAny(lower, "error", "exception", "failed", "failure", "fatal")) return LogSeverity.ERROR;
        if (containsAny(lower, "warn", "warning", "timeout", "retry", "degraded")) return LogSeverity.WARN;
        if (containsAny(lower, "info", "success", "connected", "established")) return LogSeverity.INFO;
        if (containsAny(lower, "debug", "trace", "verbose")) return LogSeverity.DEBUG;
        return LogSeverity.DEBUG;
    }

    private static LogLayer dominantLayer(Map<LogLayer, Integer> counts) {
        int best = -1;
        LogLayer bestLayer = LogLayer.UNKNOWN;
        for (LogLayer layer : LogLayer.values()) {
            if (layer == LogLayer.UNKNOWN) continue;
            int c = counts.getOrDefault(layer, 0);
            if (c > best) {
                best = c;
                bestLayer = layer;
            } else if (c == best && c > 0) {
                bestLayer = tieBreak(bestLayer, layer);
            }
        }
        if (best <= 0) return LogLayer.UNKNOWN;
        return bestLayer;
    }

    private static LogLayer tieBreak(LogLayer a, LogLayer b) {
        return priority(b) < priority(a) ? b : a;
    }

    private static int priority(LogLayer l) {
        return switch (l) {
            case DLMS -> 0;
            case HES -> 1;
            case PLC -> 2;
            case RF -> 3;
            case WAN -> 4;
            case UNKNOWN -> 5;
        };
    }

    private static boolean containsAny(String haystackLower, String... needlesLower) {
        for (String n : needlesLower) {
            if (haystackLower.contains(n)) return true;
        }
        return false;
    }
}
