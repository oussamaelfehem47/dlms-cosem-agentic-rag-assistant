package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrInt16;
import com.company.dlms.domain.decoder.AxdrInt8;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrUint16;
import com.company.dlms.domain.decoder.AxdrUint32;
import com.company.dlms.domain.decoder.AxdrUint8;
import com.company.dlms.domain.decoder.AxdrValue;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.HdlcFrame;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pure static utility for extracting STM fields from a DecodeResult.
 */
public class StmFieldExtractor {

    private StmFieldExtractor() {}

    /**
     * Extracts all 10 STM fields from the decode result.
     */
    public static Map<String, Object> extract(DecodeResult result) {
        Map<String, Object> fields = new HashMap<>();
        HdlcFrame frame = result.hdlcFrame();

        // 1 & 2: SAPs
        fields.put("hdlcClientSap", frame == null ? null : String.valueOf(frame.clientSap()));
        fields.put("hdlcServerSap", frame == null ? null : String.valueOf(frame.serverSap()));

        // 3, 4 & 5: Frame Counter and IC from Security Header
        Long fc = extractFrameCounter(result.axdrTree());
        Integer ic = extractLastIc(result.axdrTree());

        // Bug 2 & 5: Ciphered APDU extraction (header is plaintext)
        if (fc == null
                && frame != null
                && com.company.dlms.agent.decoder.CipheredApduExtractor.isCipheredType(result.apduType())) {
            byte[] info = frame.informationField();
            if (info != null && info.length > 3) {
                // Skip LLC header (3 bytes)
                byte[] apdu = java.util.Arrays.copyOfRange(info, 3, info.length);
                com.company.dlms.agent.decoder.CipheredApduExtractor.SecurityHeader header = 
                    com.company.dlms.agent.decoder.CipheredApduExtractor.extractSecurityHeader(apdu);
                if (header != null) {
                    fc = header.frameCounter();
                    fields.put("securitySuite", header.securitySuite());
                }
            }
        }

        if (fc == null && frame != null && frame.informationField() != null) {
            fc = extractFrameCounterFromRaw(frame.informationField());
        }

        fields.put("frameCounter", fc);
        fields.put("frameCounterHex", fc != null ? String.format("%08X", fc) : null);
        fields.put("lastIc", ic != null ? String.valueOf(ic) : null);

        // 6: Association State
        fields.put("associationState", deriveAssociationState(result.apduType()));

        // 7 & 8: Security Suite and Max PDU Size (AARQ only)
        if (result.apduType() == ApduType.AARQ) {
            if (fields.get("securitySuite") == null) {
                fields.put("securitySuite", extractSecuritySuite(result.axdrTree()));
            }
            fields.put("maxPduSize", extractMaxPduSize(result.axdrTree()));
        }

        // 9: Invoke ID
        fields.put("invokeId", extractInvokeId(frame, result.apduType()));

        // 10: Last OBIS
        fields.put("lastObis", result.obisResolutions().isEmpty() ? null : result.obisResolutions().get(0).obis());

        return fields;
    }

    private static Long extractFrameCounter(AxdrValue root) {
        return findSecurityHeader(root).map(h -> {
            if (h.elements().size() > 1 && h.elements().get(1) instanceof AxdrUint32 fc) {
                return fc.value();
            }
            return null;
        }).orElse(null);
    }

    private static Integer extractLastIc(AxdrValue root) {
        return findSecurityHeader(root).map(h -> {
            if (h.elements().size() > 2 && h.elements().get(2) instanceof AxdrUint32 ic) {
                return (int) ic.value();
            }
            return null;
        }).orElse(null);
    }

    private static Long extractFrameCounterFromRaw(byte[] info) {
        if (info == null || info.length < 8) return null; // LLC(3) + SC(1) + FC(4) = 8 min
        
        // Skip LLC header (E6 E6 00)
        int offset = 0;
        if (info.length >= 3 && (info[0] & 0xFF) == 0xE6 && (info[1] & 0xFF) == 0xE6) {
            offset = 3;
        }
        
        if (info.length < offset + 5) return null;
        
        int scVal = info[offset] & 0xFF;
        if (scVal == 0x30 || scVal == 0x10 || scVal == 0x20) {
            // Frame counter is at offset + 1 (4 bytes)
            long fc = ((info[offset + 1] & 0xFFL) << 24) |
                      ((info[offset + 2] & 0xFFL) << 16) |
                      ((info[offset + 3] & 0xFFL) << 8) |
                      (info[offset + 4] & 0xFFL);
            return fc;
        }
        return null;
    }

    private static java.util.Optional<AxdrStructure> findSecurityHeader(AxdrValue root) {
        if (root instanceof AxdrStructure st) {
            // Security header pattern: Structure with [0]=Uint8(0x30 or 0x10 or 0x20) and [1]=Uint32
            if (st.elements().size() >= 2 
                    && st.elements().get(0) instanceof AxdrUint8 sc 
                    && st.elements().get(1) instanceof AxdrUint32) {
                int scVal = sc.value() & 0xFF;
                if (scVal == 0x30 || scVal == 0x10 || scVal == 0x20) {
                    return java.util.Optional.of(st);
                }
            }
            for (AxdrValue child : st.elements()) {
                java.util.Optional<AxdrStructure> found = findSecurityHeader(child);
                if (found.isPresent()) return found;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Extracts the security suite from an AARQ's Axdr tree.
     * The proposed-xdlms-context is the LAST small integer (0, 1, or 2)
     * within the xDLMS-Initiate-request structure, appearing after
     * proposed-context-name in the SEQUENCE.
     * Scans sub-structures at depth <= 3. Returns the LAST match so that
     * proposed-xdlms-context (the security suite) wins over the earlier
     * proposed-context-name value.
     * Returns null if not found.
     */
    public static Integer extractSecuritySuite(AxdrValue root) {
        if (root == null) return null;
        return scanForIntInRange(root, 0, 2, 3);
    }

    /**
     * Extracts the max PDU size from an AARQ's Axdr tree.
     * The proposed-max-receive-pdu-size is within the xDLMS-Initiate-request.
     * Scans sub-structures at depth <= 3 for Uint16 values >= 128.
     * Returns null if not found or not an AARQ frame.
     */
    public static Integer extractMaxPduSize(AxdrValue root) {
        if (root == null) return null;
        return scanForMaxPduSize(root, 3);
    }

    /**
     * Scans the Axdr tree for integer values in the target range [min..max]
     * at bounded depth. Returns the LAST matching value found, so that
     * the security suite (which appears after the context name in the
     * xDLMS-Initiate-request SEQUENCE) is correctly identified.
     */
    private static Integer scanForIntInRange(AxdrValue v, int min, int max, int maxDepth) {
        return scanForIntInRange(v, min, max, maxDepth, 0);
    }

    private static Integer scanForIntInRange(AxdrValue v, int min, int max, int maxDepth, int depth) {
        if (v == null || depth > maxDepth) return null;

        Integer result = null;

        // Check if current node is an integer type within range
        Integer val = extractIntValue(v);
        if (val != null && val >= min && val <= max) {
            result = val;
        }

        // Recurse into structures and arrays, preferring later matches
        if (v instanceof AxdrStructure st) {
            for (AxdrValue child : st.elements()) {
                Integer found = scanForIntInRange(child, min, max, maxDepth, depth + 1);
                if (found != null) {
                    result = found; // later match overwrites earlier
                }
            }
        } else if (v instanceof AxdrArray arr) {
            for (AxdrValue child : arr.elements()) {
                Integer found = scanForIntInRange(child, min, max, maxDepth, depth + 1);
                if (found != null) {
                    result = found; // later match overwrites earlier
                }
            }
        }

        return result;
    }

    /**
     * Scans the Axdr tree for a Uint16 value >= 128 (typical PDU sizes).
     * Used to find proposed-max-receive-pdu-size within AARQ.
     */
    private static Integer scanForMaxPduSize(AxdrValue v, int maxDepth) {
        return scanForMaxPduSize(v, maxDepth, 0);
    }

    private static Integer scanForMaxPduSize(AxdrValue v, int maxDepth, int depth) {
        if (v == null || depth > maxDepth) return null;

        // Look for Uint16 with a reasonable PDU size value
        if (v instanceof AxdrUint16 u16 && u16.value() >= 128) {
            return u16.value();
        }

        // Recurse
        if (v instanceof AxdrStructure st) {
            for (AxdrValue child : st.elements()) {
                Integer found = scanForMaxPduSize(child, maxDepth, depth + 1);
                if (found != null) return found;
            }
        } else if (v instanceof AxdrArray arr) {
            for (AxdrValue child : arr.elements()) {
                Integer found = scanForMaxPduSize(child, maxDepth, depth + 1);
                if (found != null) return found;
            }
        }

        return null;
    }

    /**
     * Extracts an integer value from any Axdr integer type, or null if not an integer type.
     */
    public static Integer extractIntValue(AxdrValue v) {
        if (v instanceof AxdrUint8 u) return u.value() & 0xFF;
        if (v instanceof AxdrUint16 u) return u.value();
        if (v instanceof AxdrUint32 u) {
            long val = u.value();
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) return (int) val;
            return null;
        }
        if (v instanceof AxdrInt8 i) return (int) i.value();
        if (v instanceof AxdrInt16 i) return (int) i.value();
        return null;
    }

    /**
     * Extracts the result-source-diagnostic from an AARE's Axdr tree.
     * Scans for a non-zero small integer within the AARE structure.
     * Returns null if no diagnostic found (treats as success).
     */
    public static Integer extractAareResultDiagnostic(AxdrValue root) {
        if (root == null) return null;
        return scanForIntInRange(root, 1, 255, 4);
    }

    private static String deriveAssociationState(ApduType apduType) {
        if (apduType == null) return null;
        return switch (apduType) {
            case AARQ -> "ASSOCIATING";
            case AARE -> "ASSOCIATED";
            case RLRQ -> "DISCONNECTING";
            case RLRE -> "DISCONNECTED";
            default -> null;
        };
    }

    private static String extractInvokeId(HdlcFrame frame, ApduType apduType) {
        if (frame == null) {
            return null;
        }
        if (apduType == ApduType.GET_REQUEST || apduType == ApduType.SET_REQUEST || apduType == ApduType.ACTION_REQUEST 
                || apduType == ApduType.GET_RESPONSE || apduType == ApduType.SET_RESPONSE || apduType == ApduType.ACTION_RESPONSE) {
            byte[] info = frame.informationField();
            if (info != null && info.length > 3) {
                // APDU starts after LLC (3 bytes: E6 E6 00)
                // Tag is at index 3, invoke-id-and-priority is at index 4
                int invokeIdByte = info[4] & 0xFF;
                int invokeId = (invokeIdByte >> 2) & 0x3F; // Top 6 bits
                return String.valueOf(invokeId);
            }
        }
        return null;
    }
}
