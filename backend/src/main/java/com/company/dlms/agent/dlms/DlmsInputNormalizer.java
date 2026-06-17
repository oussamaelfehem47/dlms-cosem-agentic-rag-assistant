package com.company.dlms.agent.dlms;

import com.company.dlms.agent.decoder.ApduClassifier;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrBitString;
import com.company.dlms.domain.decoder.AxdrBoolean;
import com.company.dlms.domain.decoder.AxdrCompactArray;
import com.company.dlms.domain.decoder.AxdrDate;
import com.company.dlms.domain.decoder.AxdrDateTime;
import com.company.dlms.domain.decoder.AxdrEnum;
import com.company.dlms.domain.decoder.AxdrFloat32;
import com.company.dlms.domain.decoder.AxdrFloat64;
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
import com.company.dlms.domain.decoder.AxdrUtf8String;
import com.company.dlms.domain.decoder.AxdrVisibleString;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.siconia.ParseProvenance;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DlmsInputNormalizer {

    private static final Pattern EMBEDDED_HDLC_FRAME_PATTERN = Pattern.compile(
            "(?i)7E(?:[0-9A-F]{2}|[\\s:]){5,}7E"
    );
    private static final Pattern OBIS_NOTATION_PATTERN = Pattern.compile(
            "\\b\\d{1,3}(?:\\.\\d{1,3}){5}\\b"
    );
    private static final Pattern HEX_PAYLOAD_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])(?:0x)?(?:[0-9A-F]{2}(?:[\\s:])?){1,}(?![A-Z0-9])"
    );
    private static final Set<Integer> AXDR_TAGS = Set.of(
            AxdrNull.TAG,
            AxdrArray.TAG,
            AxdrStructure.TAG,
            AxdrBoolean.TAG,
            AxdrBitString.TAG,
            AxdrInt32.TAG,
            AxdrUint32.TAG,
            AxdrOctetString.TAG,
            AxdrVisibleString.TAG,
            AxdrUtf8String.TAG,
            AxdrInt8.TAG,
            AxdrInt16.TAG,
            AxdrUint8.TAG,
            AxdrUint16.TAG,
            AxdrCompactArray.TAG,
            AxdrInt64.TAG,
            AxdrUint64.TAG,
            AxdrEnum.TAG,
            AxdrFloat32.TAG,
            AxdrFloat64.TAG,
            AxdrDateTime.TAG,
            AxdrDate.TAG,
            AxdrTime.TAG
    );

    public DlmsInputNormalization normalize(String rawInput, InputClass hintedInputClass) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            return null;
        }

        DlmsInputNormalization frame = normalizeFrame(input, hintedInputClass);
        if (frame != null) {
            return frame;
        }

        DlmsInputNormalization obis = normalizeObis(input);
        if (obis != null) {
            return obis;
        }

        return normalizePayload(input);
    }

    private DlmsInputNormalization normalizeFrame(String input, InputClass hintedInputClass) {
        String normalized = normalizeHex(input);
        if (isFrameHex(normalized)) {
            return new DlmsInputNormalization(
                    normalized,
                    DlmsNormalizedKind.FRAME_HEX,
                    ParseProvenance.STRUCTURED_DIRECT,
                    List.of(),
                    null,
                    false
            );
        }

        Set<String> candidates = new LinkedHashSet<>();
        Matcher matcher = EMBEDDED_HDLC_FRAME_PATTERN.matcher(input);
        while (matcher.find()) {
            String candidate = normalizeHex(matcher.group());
            if (isFrameHex(candidate)) {
                candidates.add(candidate);
            }
        }

        if (candidates.size() > 1) {
            return new DlmsInputNormalization(
                    null,
                    DlmsNormalizedKind.FRAME_HEX,
                    ParseProvenance.STRUCTURED_HEURISTIC,
                    List.of("Multiple DLMS payload candidates were found in the request"),
                    "Detected more than one HDLC frame candidate",
                    true
            );
        }
        if (candidates.size() == 1) {
            return new DlmsInputNormalization(
                    candidates.iterator().next(),
                    DlmsNormalizedKind.FRAME_HEX,
                    ParseProvenance.STRUCTURED_HEURISTIC,
                    List.of(),
                    "Recovered embedded HDLC frame from wrapped prose input",
                    false
            );
        }

        return null;
    }

    private DlmsInputNormalization normalizeObis(String input) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = OBIS_NOTATION_PATTERN.matcher(input);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            return new DlmsInputNormalization(
                    null,
                    DlmsNormalizedKind.OBIS_QUERY,
                    ParseProvenance.STRUCTURED_HEURISTIC,
                    List.of("Multiple OBIS codes were found in the request"),
                    "Detected more than one OBIS code candidate",
                    true
            );
        }
        String obis = matches.iterator().next();
        ParseProvenance provenance = input.equals(obis)
                ? ParseProvenance.STRUCTURED_DIRECT
                : ParseProvenance.STRUCTURED_HEURISTIC;
        return new DlmsInputNormalization(
                obis,
                DlmsNormalizedKind.OBIS_QUERY,
                provenance,
                List.of(),
                provenance == ParseProvenance.STRUCTURED_HEURISTIC ? "Recovered OBIS code from wrapped prose input" : null,
                false
        );
    }

    private DlmsInputNormalization normalizePayload(String input) {
        boolean directHexInput = isPureHexPayload(input);
        boolean dlmsContext = directHexInput || containsDlmsKeyword(input);
        boolean explicitApduPrompt = hasExplicitPayloadKeyword(input, "apdu");
        boolean explicitAxdrPrompt = hasExplicitPayloadKeyword(input, "axdr");

        if (!dlmsContext) {
            return null;
        }

        Set<PayloadCandidate> candidates = new LinkedHashSet<>();
        Matcher matcher = HEX_PAYLOAD_PATTERN.matcher(input);
        while (matcher.find()) {
            String normalized = normalizeHex(matcher.group());
            boolean allowShortPayload = directHexInput || explicitApduPrompt || explicitAxdrPrompt;
            if ((normalized.length() < 10 && !allowShortPayload) || isFrameHex(normalized) || (normalized.length() & 1) == 1) {
                continue;
            }
            DlmsNormalizedKind kind = classifyPayload(normalized, explicitApduPrompt, explicitAxdrPrompt);
            if (kind != null) {
                candidates.add(new PayloadCandidate(normalized, kind));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            PayloadCandidate first = candidates.iterator().next();
            return new DlmsInputNormalization(
                    null,
                    first.kind(),
                    ParseProvenance.STRUCTURED_HEURISTIC,
                    List.of("Multiple DLMS payload candidates were found in the request"),
                    "Detected more than one APDU/AXDR payload candidate",
                    true
            );
        }

        PayloadCandidate candidate = candidates.iterator().next();
        ParseProvenance provenance = directHexInput && normalizeHex(input).equals(candidate.normalized())
                ? ParseProvenance.STRUCTURED_DIRECT
                : ParseProvenance.STRUCTURED_HEURISTIC;
        String extractorNote = provenance == ParseProvenance.STRUCTURED_HEURISTIC
                ? switch (candidate.kind()) {
                    case APDU_HEX -> "Recovered APDU payload from wrapped prose input";
                    case AXDR_HEX -> "Recovered AXDR payload from wrapped prose input";
                    case FRAME_HEX -> "Recovered embedded HDLC frame from wrapped prose input";
                    case OBIS_QUERY -> "Recovered OBIS code from wrapped prose input";
                }
                : null;
        return new DlmsInputNormalization(
                candidate.normalized(),
                candidate.kind(),
                provenance,
                List.of(),
                extractorNote,
                false
        );
    }

    private DlmsNormalizedKind classifyPayload(String normalizedHex, boolean explicitApduPrompt, boolean explicitAxdrPrompt) {
        byte[] bytes;
        try {
            bytes = java.util.HexFormat.of().parseHex(normalizedHex);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (explicitAxdrPrompt) {
            return DlmsNormalizedKind.AXDR_HEX;
        }
        ApduType apduType = ApduClassifier.classify(bytes);
        if (explicitApduPrompt) {
            return DlmsNormalizedKind.APDU_HEX;
        }
        if (apduType != ApduType.UNKNOWN) {
            return DlmsNormalizedKind.APDU_HEX;
        }
        if (bytes.length > 0 && AXDR_TAGS.contains(bytes[0] & 0xFF)) {
            return DlmsNormalizedKind.AXDR_HEX;
        }
        return null;
    }

    private boolean hasExplicitPayloadKeyword(String input, String keyword) {
        return input.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean containsDlmsKeyword(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.contains("dlms")
                || lower.contains("cosem")
                || lower.contains("hdlc")
                || lower.contains("frame")
                || lower.contains("apdu")
                || lower.contains("axdr")
                || lower.contains("obis")
                || lower.contains("payload")
                || lower.contains("bytes")
                || lower.contains("hex")
                || lower.contains("data")
                || lower.contains("decode");
    }

    private boolean isPureHexPayload(String input) {
        String normalized = normalizeHex(input);
        return !normalized.isBlank() && normalized.matches("(?i)^[0-9A-F]+$") && (normalized.length() & 1) == 0;
    }

    private boolean isFrameHex(String normalizedHex) {
        return normalizedHex.matches("(?i)^7E[0-9A-F]{10,}7E$") && (normalizedHex.length() & 1) == 0;
    }

    private String normalizeHex(String raw) {
        return raw == null ? "" : raw.trim()
                .replaceFirst("(?i)^0x", "")
                .replace(" ", "")
                .replace(":", "")
                .replace("\n", "")
                .replace("\r", "");
    }

    private record PayloadCandidate(String normalized, DlmsNormalizedKind kind) {}
}
