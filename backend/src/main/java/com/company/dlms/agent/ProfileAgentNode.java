package com.company.dlms.agent;

import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.agent.profile.CaptureObjectParser;
import com.company.dlms.agent.profile.ScalerUnit;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrArray;
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
import com.company.dlms.domain.decoder.AxdrValue;
import com.company.dlms.domain.decoder.AxdrVisibleString;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.profile.CaptureObjectDef;
import com.company.dlms.domain.profile.FormattedValue;
import com.company.dlms.domain.profile.ProfileCell;
import com.company.dlms.domain.profile.ProfileColumn;
import com.company.dlms.domain.profile.ProfileResult;
import com.company.dlms.domain.profile.ProfileRow;
import com.company.dlms.domain.profile.ProfileType;
import com.company.dlms.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class ProfileAgentNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(ProfileAgentNode.class);
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(5);

    private final ObisResolver obisResolver;

    public ProfileAgentNode(ObisResolver obisResolver) {
        this.obisResolver = obisResolver;
    }

    @Override
    public WorkflowState process(WorkflowState state) {
        if (!(state.decodeResult() instanceof DecodeResult decodeResult)) {
            return state;
        }
        if (decodeResult.apduType() != ApduType.GET_RESPONSE || decodeResult.axdrTree() == null) {
            return state;
        }

        try {
            ProfileArrays arrays = locateProfileArrays(decodeResult.axdrTree());
            if (arrays.captureObjects() == null || arrays.buffer() == null) {
                return handleNotProfileStructure(state);
            }

            List<CaptureObjectDef> captureObjects = CaptureObjectParser.parse(arrays.captureObjects());
            List<ProfileColumn> columns = resolveColumns(captureObjects, state.sessionId());
            ProfileDescriptor descriptor = detectProfile(decodeResult, columns);
            List<ProfileRow> rows = buildRows(arrays.buffer(), columns);

            ProfileResult profileResult = new ProfileResult(
                    descriptor.profileType(),
                    columns,
                    rows,
                    captureObjects.size(),
                    rows.size(),
                    descriptor.profileObis()
            );

            return state.withProfileResult(profileResult);
        } catch (Exception ex) {
            log.warn("ProfileAgentNode failed sessionId={} err={}", state.sessionId(), ex.toString());
            return state.addError("Profile decode failed: " + ex.getMessage());
        }
    }

    private WorkflowState handleNotProfileStructure(WorkflowState state) {
        if (state.intent() == DlmsIntent.PROFILE_DECODE) {
            return state.addError("Not an IC 7 profile structure");
        }
        return state;
    }

    private ProfileArrays locateProfileArrays(AxdrValue root) {
        if (root instanceof AxdrStructure structure && structure.elements() != null) {
            ProfileArrays direct = fromSiblingArrays(structure.elements());
            if (direct.captureObjects() != null && direct.buffer() != null) {
                return direct;
            }
        }

        List<AxdrArray> arrays = new ArrayList<>();
        collectArrays(root, arrays);
        AxdrArray captureObjects = null;
        AxdrArray buffer = null;
        for (AxdrArray array : arrays) {
            if (captureObjects == null && CaptureObjectParser.isCaptureObjectArray(array)) {
                captureObjects = array;
                continue;
            }
            if (buffer == null && looksLikeBuffer(array)) {
                buffer = array;
            }
        }
        return new ProfileArrays(captureObjects, buffer);
    }

    private ProfileArrays fromSiblingArrays(List<AxdrValue> elements) {
        AxdrArray captureObjects = null;
        AxdrArray buffer = null;
        AxdrArray emptyCandidate = null;
        for (AxdrValue element : elements) {
            if (!(element instanceof AxdrArray array)) {
                continue;
            }
            if (captureObjects == null && CaptureObjectParser.isCaptureObjectArray(array)) {
                captureObjects = array;
                continue;
            }
            if (captureObjects == null && (array.elements() == null || array.elements().isEmpty())) {
                emptyCandidate = array;
                continue;
            }
            if (buffer == null && looksLikeBuffer(array)) {
                buffer = array;
            }
        }
        if (captureObjects == null && emptyCandidate != null) {
            captureObjects = emptyCandidate;
        }
        return new ProfileArrays(captureObjects, buffer);
    }

    private void collectArrays(AxdrValue value, List<AxdrArray> arrays) {
        if (value == null) {
            return;
        }
        if (value instanceof AxdrArray array) {
            arrays.add(array);
            if (array.elements() != null) {
                array.elements().forEach(child -> collectArrays(child, arrays));
            }
            return;
        }
        if (value instanceof AxdrStructure structure && structure.elements() != null) {
            structure.elements().forEach(child -> collectArrays(child, arrays));
        }
    }

    private boolean looksLikeBuffer(AxdrArray array) {
        if (array.elements() == null || array.elements().isEmpty()) {
            return true;
        }
        if (CaptureObjectParser.isCaptureObjectArray(array)) {
            return false;
        }
        return array.elements().stream().allMatch(value -> value instanceof AxdrStructure);
    }

    private List<ProfileColumn> resolveColumns(List<CaptureObjectDef> captureObjects, String sessionId) {
        List<ProfileColumn> columns = new ArrayList<>(captureObjects.size());
        for (int index = 0; index < captureObjects.size(); index++) {
            CaptureObjectDef captureObject = captureObjects.get(index);
            ObisResolution resolution = null;
            try {
                resolution = obisResolver.resolve(captureObject.logicalName(), sessionId).block(RESOLVE_TIMEOUT);
            } catch (Exception ex) {
                throw new IllegalStateException("OBIS resolution failed for " + captureObject.logicalName(), ex);
            }

            columns.add(new ProfileColumn(
                    index,
                    captureObject.logicalName(),
                    descriptionFor(captureObject.logicalName(), resolution),
                    captureObject.classId(),
                    captureObject.attributeIndex(),
                    resolution != null ? resolution.unit() : null,
                    resolution != null ? resolution.scaler() : null
            ));
        }
        return List.copyOf(columns);
    }

    private String descriptionFor(String obis, ObisResolution resolution) {
        if (resolution != null && resolution.description() != null && !resolution.description().isBlank()) {
            return resolution.description();
        }
        return "Unknown OBIS (" + obis + ")";
    }

    private ProfileDescriptor detectProfile(DecodeResult decodeResult, List<ProfileColumn> columns) {
        List<String> candidates = new ArrayList<>();
        if (decodeResult.obisResolutions() != null) {
            decodeResult.obisResolutions().stream()
                    .map(ObisResolution::obis)
                    .filter(Objects::nonNull)
                    .forEach(candidates::add);
        }
        columns.stream().map(ProfileColumn::obis).filter(Objects::nonNull).forEach(candidates::add);

        for (String candidate : candidates) {
            ProfileType type = profileTypeFor(candidate);
            if (type != ProfileType.GENERIC_PROFILE) {
                return new ProfileDescriptor(type, candidate);
            }
        }

        String fallbackObis = candidates.isEmpty() ? null : candidates.getFirst();
        return new ProfileDescriptor(ProfileType.GENERIC_PROFILE, fallbackObis);
    }

    private ProfileType profileTypeFor(String obis) {
        if (obis == null) {
            return ProfileType.GENERIC_PROFILE;
        }
        return switch (obis) {
            case "1.0.99.1.0.255", "1.0.99.2.0.255" -> ProfileType.LOAD_PROFILE;
            case "0.0.98.1.0.255" -> ProfileType.BILLING;
            case "1.0.94.7.0.255" -> ProfileType.EVENT_LOG;
            case "1.0.99.12.0.255" -> ProfileType.POWER_QUALITY;
            default -> ProfileType.GENERIC_PROFILE;
        };
    }

    private List<ProfileRow> buildRows(AxdrArray buffer, List<ProfileColumn> columns) {
        if (buffer.elements() == null || buffer.elements().isEmpty()) {
            return List.of();
        }

        List<ProfileRow> rows = new ArrayList<>(buffer.elements().size());
        for (AxdrValue rowValue : buffer.elements()) {
            if (!(rowValue instanceof AxdrStructure rowStructure) || rowStructure.elements() == null) {
                continue;
            }

            List<ProfileCell> cells = new ArrayList<>(rowStructure.elements().size());
            String timestamp = null;
            for (int index = 0; index < rowStructure.elements().size(); index++) {
                AxdrValue cellValue = rowStructure.elements().get(index);
                ProfileColumn column = index < columns.size() ? columns.get(index) : null;
                ProfileCell cell = buildCell(index, column, cellValue);
                cells.add(cell);
                if (timestamp == null) {
                    timestamp = extractTimestamp(cellValue, column);
                }
            }
            rows.add(new ProfileRow(timestamp, List.copyOf(cells)));
        }
        return List.copyOf(rows);
    }

    private ProfileCell buildCell(int columnIndex, ProfileColumn column, AxdrValue value) {
        Integer scaler = column != null ? column.scaler() : null;
        String unit = column != null ? column.unit() : null;
        Object raw = rawValue(value);

        if (raw instanceof BigDecimal decimal) {
            FormattedValue formatted = ScalerUnit.apply(decimal, scaler, unit);
            return new ProfileCell(columnIndex, formatted.rawValue(), formatted.scaledValue(), formatted.displayString(), formatted.unit());
        }

        String displayString = raw == null ? "null" : raw.toString();
        if (unit != null && !unit.isBlank() && raw != null && !(raw instanceof String text && text.endsWith(unit))) {
            displayString = displayString + " " + unit;
        }
        return new ProfileCell(columnIndex, raw, null, displayString, unit);
    }

    private Object rawValue(AxdrValue value) {
        return switch (value) {
            case null -> null;
            case AxdrNull ignored -> null;
            case AxdrInt8 current -> BigDecimal.valueOf(current.value());
            case AxdrInt16 current -> BigDecimal.valueOf(current.value());
            case AxdrInt32 current -> BigDecimal.valueOf(current.value());
            case AxdrInt64 current -> BigDecimal.valueOf(current.value());
            case AxdrUint8 current -> BigDecimal.valueOf(current.value());
            case AxdrUint16 current -> BigDecimal.valueOf(current.value());
            case AxdrUint32 current -> BigDecimal.valueOf(current.value());
            case AxdrUint64 current -> new BigDecimal(current.value());
            case AxdrEnum current -> BigDecimal.valueOf(current.value());
            case AxdrFloat32 current -> BigDecimal.valueOf(current.value());
            case AxdrFloat64 current -> BigDecimal.valueOf(current.value());
            case AxdrVisibleString current -> current.value();
            case AxdrUtf8String current -> current.value();
            case AxdrDateTime current -> formatDateTime(current);
            case AxdrDate current -> formatDate(current);
            case AxdrTime current -> formatTime(current);
            case AxdrOctetString current -> formatOctetString(current.value());
            default -> value.toString();
        };
    }

    private String extractTimestamp(AxdrValue value, ProfileColumn column) {
        String formatted = switch (value) {
            case AxdrDateTime current -> formatDateTime(current);
            case AxdrDate current -> formatDate(current);
            case AxdrTime current -> formatTime(current);
            case AxdrOctetString current -> tryFormatDateTimeBytes(current.value());
            default -> null;
        };

        if (formatted != null) {
            return formatted;
        }

        if (column != null && "0.0.1.0.0.255".equals(column.obis()) && value instanceof AxdrVisibleString textValue) {
            return textValue.value();
        }

        return null;
    }

    private String formatOctetString(byte[] value) {
        String asTimestamp = tryFormatDateTimeBytes(value);
        if (asTimestamp != null) {
            return asTimestamp;
        }
        return value == null ? null : HEX.formatHex(value);
    }

    private String tryFormatDateTimeBytes(byte[] value) {
        if (value == null || value.length < 7) {
            return null;
        }
        int year = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
        int month = value[2] & 0xFF;
        int day = value[3] & 0xFF;
        int hour = value[5] & 0xFF;
        int minute = value[6] & 0xFF;
        int second = value.length > 7 ? value[7] & 0xFF : 0;
        if (year <= 0 || month == 0 || month > 12 || day == 0 || day > 31) {
            return null;
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second);
    }

    private String formatDateTime(AxdrDateTime value) {
        return String.format(
                Locale.ROOT,
                "%04d-%02d-%02d %02d:%02d:%02d",
                value.year(),
                value.month() & 0xFF,
                value.dom() & 0xFF,
                value.hour() & 0xFF,
                value.min() & 0xFF,
                value.sec() & 0xFF
        );
    }

    private String formatDate(AxdrDate value) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", value.year(), value.month() & 0xFF, value.dom() & 0xFF);
    }

    private String formatTime(AxdrTime value) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", value.hour() & 0xFF, value.min() & 0xFF, value.sec() & 0xFF);
    }

    private record ProfileArrays(AxdrArray captureObjects, AxdrArray buffer) {}

    private record ProfileDescriptor(ProfileType profileType, String profileObis) {}
}
