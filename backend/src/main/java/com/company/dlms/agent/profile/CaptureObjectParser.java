package com.company.dlms.agent.profile;

import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrEnum;
import com.company.dlms.domain.decoder.AxdrInt16;
import com.company.dlms.domain.decoder.AxdrInt32;
import com.company.dlms.domain.decoder.AxdrInt64;
import com.company.dlms.domain.decoder.AxdrInt8;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrUint16;
import com.company.dlms.domain.decoder.AxdrUint32;
import com.company.dlms.domain.decoder.AxdrUint64;
import com.company.dlms.domain.decoder.AxdrUint8;
import com.company.dlms.domain.decoder.AxdrValue;
import com.company.dlms.domain.profile.CaptureObjectDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class CaptureObjectParser {

    private static final Logger log = LoggerFactory.getLogger(CaptureObjectParser.class);

    private CaptureObjectParser() {}

    public static List<CaptureObjectDef> parse(AxdrValue captureObjectsArray) {
        if (!(captureObjectsArray instanceof AxdrArray array) || array.elements() == null || array.elements().isEmpty()) {
            return List.of();
        }

        List<CaptureObjectDef> parsed = new ArrayList<>();
        for (int index = 0; index < array.elements().size(); index++) {
            AxdrValue element = array.elements().get(index);
            if (!(element instanceof AxdrStructure structure)) {
                log.warn("Skipping capture object at index {} because it is not a structure", index);
                continue;
            }
            if (!isCaptureObjectStructure(structure)) {
                log.warn("Skipping malformed capture object at index {} because it does not have 4 fields", index);
                continue;
            }

            List<AxdrValue> fields = structure.elements();
            Integer classId = asInt(fields.get(0));
            String obis = asObis(fields.get(1));
            Integer attributeIndex = asInt(fields.get(2));
            Integer dataIndex = asInt(fields.get(3));

            if (classId == null || obis == null || attributeIndex == null || dataIndex == null) {
                log.warn("Skipping malformed capture object at index {} because one or more fields are invalid", index);
                continue;
            }

            parsed.add(new CaptureObjectDef(classId, obis, attributeIndex, dataIndex));
        }

        return List.copyOf(parsed);
    }

    public static boolean isCaptureObjectArray(AxdrValue value) {
        if (!(value instanceof AxdrArray array) || array.elements() == null || array.elements().isEmpty()) {
            return false;
        }
        return array.elements().stream().allMatch(CaptureObjectParser::isCaptureObjectStructure);
    }

    private static boolean isCaptureObjectStructure(AxdrValue value) {
        return value instanceof AxdrStructure structure
                && structure.elements() != null
                && structure.elements().size() == 4;
    }

    private static Integer asInt(AxdrValue value) {
        try {
            return switch (value) {
                case AxdrInt8 v -> (int) v.value();
                case AxdrInt16 v -> (int) v.value();
                case AxdrInt32 v -> v.value();
                case AxdrInt64 v -> Math.toIntExact(v.value());
                case AxdrUint8 v -> (int) v.value();
                case AxdrUint16 v -> v.value();
                case AxdrUint32 v -> Math.toIntExact(v.value());
                case AxdrUint64 v -> v.value().intValueExact();
                case AxdrEnum v -> v.value();
                default -> null;
            };
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static String asObis(AxdrValue value) {
        if (!(value instanceof AxdrOctetString octetString) || octetString.value() == null || octetString.value().length != 6) {
            return null;
        }
        return formatObis(octetString.value());
    }

    public static String formatObis(byte[] bytes) {
        if (bytes == null || bytes.length != 6) {
            throw new IllegalArgumentException("OBIS logical name must be exactly 6 bytes");
        }
        List<String> parts = new ArrayList<>(6);
        for (byte current : bytes) {
            parts.add(Integer.toString(current & 0xFF));
        }
        return String.join(".", parts);
    }
}
