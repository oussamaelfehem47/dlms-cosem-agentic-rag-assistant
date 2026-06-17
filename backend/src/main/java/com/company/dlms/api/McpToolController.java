package com.company.dlms.api;

import com.company.dlms.agent.decoder.ApduClassifier;
import com.company.dlms.agent.decoder.AxdrDecodeException;
import com.company.dlms.agent.decoder.AxdrDecoder;
import com.company.dlms.agent.decoder.HdlcParser;
import com.company.dlms.agent.decoder.LlcExtractor;
import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.agent.siconia.AlarmDecoder;
import com.company.dlms.agent.siconia.LogClassifier;
import com.company.dlms.agent.siconia.XmlTraceParser;
import com.company.dlms.domain.decoder.*;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.LogAnalysis;
import com.company.dlms.domain.siconia.SiconiaXmlTrace;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Non-streaming REST endpoints for MCP tool calls.
 * The Python MCP server proxies tool invocations to these endpoints
 * instead of importing non-existent Python decoder modules.
 */
@RestController
@RequestMapping("/api/mcp/tools")
public class McpToolController {

    private final AlarmDecoder alarmDecoder = new AlarmDecoder();
    private final XmlTraceParser xmlTraceParser = new XmlTraceParser();
    private final LogClassifier logClassifier = new LogClassifier();
    private final ObisResolver obisResolver;

    public McpToolController(ObisResolver obisResolver) {
        this.obisResolver = obisResolver;
    }

    // ── DLMS Tools ─────────────────────────────────────────────────────────

    /**
     * Parse a raw DLMS/COSEM HDLC frame from hex string.
     * POST /api/mcp/tools/dlms.parse_hdlc
     */
    @PostMapping("/dlms.parse_hdlc")
    public Mono<Map<String, Object>> parseHdlc(@RequestBody Map<String, String> body) {
        return Mono.fromSupplier(() -> {
            String frameHex = body.getOrDefault("frame_hex", "").strip()
                    .replace(" ", "").replace(":", "");
            Map<String, Object> result = new LinkedHashMap<>();

            if (frameHex.length() < 14) {
                result.put("errors", List.of("Frame too short"));
                result.put("warnings", List.of());
                result.put("hcs_valid", false);
                result.put("fcs_valid", false);
                return result;
            }
            if (frameHex.length() > 8192) {
                result.put("errors", List.of("Frame exceeds maximum size (4096 bytes)"));
                result.put("warnings", List.of());
                result.put("hcs_valid", false);
                result.put("fcs_valid", false);
                return result;
            }

            try {
                byte[] frameBytes = HexFormat.of().parseHex(frameHex);
                HdlcFrame hdlc;
                List<String> errors = new ArrayList<>();
                try {
                    hdlc = HdlcParser.parse(frameBytes);
                } catch (HdlcParseException e) {
                    if (HdlcParser.ERR_UNEXPECTED_INFO_ON_SUPERVISORY_FRAME.equals(e.getMessage())) {
                        hdlc = HdlcParser.parseLenientOuterFrame(frameBytes);
                        addUnique(errors, e.getMessage());
                    } else {
                        throw e;
                    }
                }
                if (!hdlc.fcsValid()) {
                    addUnique(errors, "FCS invalid");
                }
                result.put("frame_type", hdlc.frameType().name());
                // Return both key variants so Java-side MCP consumers remain
                // compatible with older Python-shaped payloads.
                result.put("client_address", hdlc.clientSap());
                result.put("server_address", hdlc.serverSap());
                result.put("client_sap", hdlc.clientSap());
                result.put("server_sap", hdlc.serverSap());
                result.put("fcs_valid", hdlc.fcsValid());
                // U-frames have no information field — guard NPE
                byte[] infoField = hdlc.informationField();
                if (infoField != null) {
                    result.put("information_hex", HexFormat.of().formatHex(infoField));
                    result.put("information_length", infoField.length);
                } else {
                    result.put("information_hex", "");
                    result.put("information_length", 0);
                }
                result.put("raw_hex", HexFormat.of().formatHex(hdlc.rawBytes()));
                result.put("errors", errors);
                result.put("warnings", List.of());
                if (hdlc.uFrameType() != null) {
                    result.put("u_frame_type", hdlc.uFrameType().name());
                }
                if (hdlc.sFrameType() != null) {
                    result.put("s_frame_type", hdlc.sFrameType().name());
                }
            } catch (HdlcParseException e) {
                result.put("errors", List.of(e.getMessage()));
                result.put("warnings", List.of());
                result.put("hcs_valid", false);
                result.put("fcs_valid", false);
                result.put("information_hex", "");
                result.put("information_length", 0);
            } catch (Exception e) {
                result.put("errors", List.of("Internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                result.put("warnings", List.of());
                result.put("hcs_valid", false);
                result.put("fcs_valid", false);
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Extract LLC header and classify APDU type from HDLC information field.
     * POST /api/mcp/tools/dlms.decode_apdu
     */
    @PostMapping("/dlms.decode_apdu")
    public Mono<Map<String, Object>> decodeApdu(@RequestBody Map<String, String> body) {
        return Mono.fromSupplier(() -> {
            String infoHex = body.getOrDefault("information_hex", "").strip().replace(" ", "");
            Map<String, Object> result = new LinkedHashMap<>();

            if (infoHex.length() < 6) {
                result.put("apdu_type", "UNKNOWN");
                result.put("payload_hex", "");
                result.put("warnings", List.of("Information field too short for LLC"));
                return result;
            }

            try {
                byte[] infoBytes = HexFormat.of().parseHex(infoHex);
                byte[] apduBytes = LlcExtractor.extract(infoBytes);
                ApduType apduType = ApduClassifier.classify(apduBytes);
                result.put("apdu_type", apduType.name());
                result.put("payload_hex", HexFormat.of().formatHex(apduBytes));
                result.put("warnings", List.of());
            } catch (Exception e) {
                result.put("apdu_type", "UNKNOWN");
                result.put("payload_hex", "");
                result.put("warnings", List.of("Decode error: " + e.getMessage()));
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Recursively decode AXDR-encoded DLMS attribute data.
     * POST /api/mcp/tools/dlms.decode_axdr
     */
    @PostMapping("/dlms.decode_axdr")
    public Mono<Map<String, Object>> decodeAxdr(@RequestBody Map<String, Object> body) {
        return Mono.fromSupplier(() -> {
            String axdrHex = (String) body.getOrDefault("axdr_hex", "");
            Map<String, Object> result = new LinkedHashMap<>();

            try {
                byte[] data = HexFormat.of().parseHex(axdrHex.strip().replace(" ", ""));
                // AxdrDecoder.decode takes a single byte[] argument
                AxdrValue decoded = AxdrDecoder.decode(data);
                result.put("decoded", axdrToMap(decoded));
                result.put("warnings", List.of());

                // Also extract hex dump for UI display
                result.put("hex", axdrHex);
            } catch (AxdrDecodeException e) {
                result.put("decoded", Map.of("error", e.getMessage()));
                result.put("warnings", List.of());
            } catch (Exception e) {
                result.put("decoded", Map.of("error", "Internal error: " + e.getMessage()));
                result.put("warnings", List.of());
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Resolve an OBIS code to its Interface Class, attribute definitions,
     * and measurement semantics using the DLMS Knowledge Graph.
     * POST /api/mcp/tools/dlms.resolve_obis
     */
    @PostMapping("/dlms.resolve_obis")
    public Mono<Map<String, Object>> resolveObis(@RequestBody Map<String, String> body) {
        return Mono.fromSupplier(() -> {
            String obisStr = body.getOrDefault("obis_str", "").strip();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("obis", obisStr);

            try {
                // resolve() takes (String obis, String sessionId) and returns Mono<ObisResolution>
                var resolvedOpt = obisResolver.resolve(obisStr, "").block();
                if (resolvedOpt != null) {
                    result.put("found", true);
                    result.put("interface_class", resolvedOpt.ic());
                    result.put("description", resolvedOpt.description());
                    result.put("unit", resolvedOpt.unit());
                    result.put("scaler", resolvedOpt.scaler());
                    result.put("tier", resolvedOpt.tierUsed().name());
                } else {
                    result.put("found", false);
                    result.put("warnings", List.of("OBIS code not found in knowledge graph"));
                }
            } catch (Exception e) {
                result.put("found", false);
                result.put("warnings", List.of("Resolution error: " + e.getMessage()));
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Reassemble a complete DLMS profile from all GBT blocks and decode the result.
     * POST /api/mcp/tools/dlms.assemble_gbt
     */
    @PostMapping("/dlms.assemble_gbt")
    public Mono<Map<String, Object>> assembleGbt(@RequestBody Map<String, Object> body) {
        return Mono.fromSupplier(() -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) body.getOrDefault("blocks", List.of());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("block_count", blocks.size());
            result.put("warnings", List.of("GBT assembly via MCP tool is not yet implemented — use Java-side GbtAssembler"));
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── SICONIA Tools ───────────────────────────────────────────────────────

    /**
     * Decode a SICONIA alarm code (hex string) into one or more alarm definitions.
     * POST /api/mcp/tools/siconia.decode_alarm
     */
    @PostMapping("/siconia.decode_alarm")
    public Mono<Map<String, Object>> decodeAlarm(@RequestBody Map<String, String> body) {
        return Mono.fromSupplier(() -> {
            String alarmCode = body.getOrDefault("alarm_code", "").strip();
            Map<String, Object> result = new LinkedHashMap<>();

            try {
                List<AlarmDecodeResult> alarms = alarmDecoder.decode(alarmCode);
                List<Map<String, Object>> alarmList = alarms.stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", a.code());
                    m.put("severity", a.severity().name());
                    m.put("root_cause", a.rootCause());
                    m.put("remediation", a.remediation());
                    m.put("affected_component", a.affectedComponent().name());
                    return m;
                }).collect(Collectors.toList());
                result.put("alarms", alarmList);
                result.put("alarm_count", alarmList.size());
            } catch (Exception e) {
                result.put("alarms", List.of());
                result.put("alarm_count", 0);
                result.put("error", e.getMessage());
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Classify a SICONIA log block (text) to identify layer, severity, and issues.
     * POST /api/mcp/tools/siconia.classify_log
     */
    @PostMapping("/siconia.classify_log")
    public Mono<Map<String, Object>> classifyLog(@RequestBody Map<String, String> body) {
        return Mono.fromSupplier(() -> {
            String logText = firstNonBlank(body, "log_text", "log_content");
            Map<String, Object> result = new LinkedHashMap<>();

            try {
                LogAnalysis analysis = logClassifier.classify(logText);
                // Use field names that SiconiaAgentNode.mapMcpLogAnalysis() expects
                result.put("dominant_layer", analysis.dominantLayer().name());
                result.put("highest_severity", analysis.highestSeverity().name());
                result.put("total_lines", analysis.lineCount());
                result.put("error_lines", analysis.errorLineCount());
                result.put("issue_categories",
                    analysis.issueCategories().stream()
                        .map(Enum::name)
                        .collect(Collectors.toList()));
            } catch (Exception e) {
                result.put("dominant_layer", "UNKNOWN");
                result.put("highest_severity", "INFO");
                result.put("total_lines", 0);
                result.put("error_lines", 0);
                result.put("issue_categories", List.of());
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Parse a SICONIA XML trace string into structured events.
     * POST /api/mcp/tools/siconia.parse_xml
     */
    @PostMapping("/siconia.parse_xml")
    public Mono<Map<String, Object>> parseXml(@RequestBody Map<String, String> body) {
        return Mono.fromSupplier(() -> {
            String xmlText = firstNonBlank(body, "xml_text", "xml_content");
            Map<String, Object> result = new LinkedHashMap<>();

            try {
                SiconiaXmlTrace trace = xmlTraceParser.parse(xmlText);
                List<Map<String, Object>> eventList = trace.events().stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    // XmlEvent record fields: type, code, timestamp (String), deviceId, errorCode
                    m.put("type", e.type());
                    m.put("code", e.code());
                    m.put("timestamp", e.timestamp());
                    m.put("deviceId", e.deviceId());
                    m.put("errorCode", e.errorCode());
                    return m;
                }).collect(Collectors.toList());
                result.put("events", eventList);
                result.put("event_count", eventList.size());
                result.put("parse_errors", trace.parseErrors());
            } catch (Exception e) {
                result.put("events", List.of());
                result.put("event_count", 0);
                result.put("parse_errors", List.of(e.getMessage()));
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Convert an AxdrValue tree into a plain Map/List structure suitable for JSON serialization.
     */
    @SuppressWarnings("unchecked")
    private Object axdrToMap(AxdrValue v) {
        if (v instanceof AxdrStructure s) {
            List<Object> items = new ArrayList<>();
            for (AxdrValue e : s.elements()) {
                items.add(axdrToMap(e));
            }
            return Map.of(
                    "tag", s.tag(),
                    "type", "STRUCTURE",
                    "elements", items
            );
        } else if (v instanceof AxdrOctetString o) {
            return Map.of(
                    "tag", o.tag(),
                    "type", "OCTET_STRING",
                    "hex", HexFormat.of().formatHex(o.value()),
                    "ascii", new String(o.value(), java.nio.charset.StandardCharsets.US_ASCII).replace("\uFFFD", ".")
            );
        } else if (v instanceof AxdrInt32 i) {
            return Map.of(
                    "tag", i.tag(),
                    "type", "INT32",
                    "value", i.value()
            );
        } else if (v instanceof AxdrUint32 u) {
            return Map.of(
                    "tag", u.tag(),
                    "type", "UINT32",
                    "value", u.value() & 0xFFFFFFFFL
            );
        } else if (v instanceof AxdrInt16 i) {
            return Map.of(
                    "tag", i.tag(),
                    "type", "INT16",
                    "value", i.value()
            );
        } else if (v instanceof AxdrUint16 u) {
            return Map.of(
                    "tag", u.tag(),
                    "type", "UINT16",
                    "value", u.value()
            );
        } else if (v instanceof AxdrInt8 i) {
            return Map.of(
                    "tag", i.tag(),
                    "type", "INT8",
                    "value", i.value()
            );
        } else if (v instanceof AxdrUint8 u) {
            return Map.of(
                    "tag", u.tag(),
                    "type", "UINT8",
                    "value", u.value()
            );
        } else if (v instanceof AxdrEnum e) {
            return Map.of(
                    "tag", e.tag(),
                    "type", "ENUM",
                    "value", e.value()
            );
        } else if (v instanceof AxdrBoolean b) {
            return Map.of(
                    "tag", b.tag(),
                    "type", "BOOLEAN",
                    "value", b.value()
            );
        } else if (v instanceof AxdrNull) {
            return Map.of(
                    "tag", -1,
                    "type", "NULL",
                    "value", "null"
            );
        } else if (v instanceof AxdrBitString bs) {
            return Map.of(
                    "tag", bs.tag(),
                    "type", "BIT_STRING",
                    "hex", HexFormat.of().formatHex(bs.value()),
                    "unused_bits", bs.unusedBits()
            );
        } else if (v instanceof AxdrFloat32 f) {
            return Map.of(
                    "tag", f.tag(),
                    "type", "FLOAT32",
                    "value", f.value()
            );
        } else if (v instanceof AxdrFloat64 d) {
            return Map.of(
                    "tag", d.tag(),
                    "type", "FLOAT64",
                    "value", d.value()
            );
        } else if (v instanceof AxdrVisibleString vs) {
            return Map.of(
                    "tag", vs.tag(),
                    "type", "VISIBLE_STRING",
                    "value", vs.value()
            );
        } else if (v instanceof AxdrUtf8String utf8) {
            return Map.of(
                    "tag", utf8.tag(),
                    "type", "UTF8_STRING",
                    "value", utf8.value()
            );
        } else if (v instanceof AxdrArray arr) {
            List<Object> items = new ArrayList<>();
            for (AxdrValue e : arr.elements()) {
                items.add(axdrToMap(e));
            }
            return Map.of(
                    "tag", arr.tag(),
                    "type", "ARRAY",
                    "elements", items
            );
        } else if (v instanceof AxdrCompactArray ca) {
            // AxdrCompactArray stores raw bytes — no decoded() method exists
            return Map.of(
                    "tag", ca.tag(),
                    "type", "COMPACT_ARRAY",
                    "raw_hex", HexFormat.of().formatHex(ca.rawData()),
                    "raw_length", ca.rawData().length
            );
        } else {
            return Map.of(
                    "type", v.getClass().getSimpleName(),
                    "value", v.toString()
            );
        }
    }

    private static String firstNonBlank(Map<String, String> body, String... keys) {
        for (String key : keys) {
            String value = body.get(key);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static void addUnique(List<String> errors, String error) {
        if (error == null) {
            return;
        }
        String trimmed = error.trim();
        if (trimmed.isEmpty() || errors.contains(trimmed)) {
            return;
        }
        errors.add(trimmed);
    }
}
