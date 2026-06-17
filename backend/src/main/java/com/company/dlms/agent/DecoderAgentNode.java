package com.company.dlms.agent;

import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.decoder.ApduClassifier;
import com.company.dlms.agent.decoder.AxdrDecodeException;
import com.company.dlms.agent.decoder.AxdrDecoder;
import com.company.dlms.agent.decoder.GbtAssembler;
import com.company.dlms.agent.decoder.HdlcParser;
import com.company.dlms.agent.decoder.LlcExtractor;
import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.agent.decoder.StmFieldExtractor;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrValue;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.DlmsProcessingMetadata;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.decoder.SFrameType;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.infrastructure.mcp.McpDispatcher;
import com.company.dlms.infrastructure.mcp.McpResult;
import com.company.dlms.infrastructure.mcp.McpTools;
import com.company.dlms.memory.StmService;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DecoderAgentNode {

    private static final Logger log = LoggerFactory.getLogger(DecoderAgentNode.class);

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern EMBEDDED_HDLC_FRAME_PATTERN = Pattern.compile(
            "(?i)7E(?:[0-9A-F]{2}|[\\s:]){5,}7E"
    );
    private static final Duration PERSIST_TIMEOUT = Duration.ofSeconds(2);

    private final ObisResolver obisResolver;
    private final GbtAssembler gbtAssembler;
    private final StmService stmService;
    private final McpDispatcher mcpDispatcher;
    private final SessionEventService sessionEventService;
    private final com.company.dlms.memory.SessionNarrativeService sessionNarrativeService;

    public DecoderAgentNode(
            ObisResolver obisResolver, 
            GbtAssembler gbtAssembler, 
            StmService stmService, 
            McpDispatcher mcpDispatcher,
            SessionEventService sessionEventService,
            com.company.dlms.memory.SessionNarrativeService sessionNarrativeService
    ) {
        this.obisResolver = obisResolver;
        this.gbtAssembler = gbtAssembler;
        this.stmService = stmService;
        this.mcpDispatcher = mcpDispatcher;
        this.sessionEventService = sessionEventService;
        this.sessionNarrativeService = sessionNarrativeService;
    }

    public WorkflowState process(WorkflowState state) {
        try {
            String raw = state.rawInput();
            if (raw == null) {
                return state.addError("rawInput is null");
            }

            DlmsInputNormalization normalization = state.dlmsNormalization();
            if (normalization != null) {
                if (normalization.ambiguous()) {
                    String warning = normalization.warnings().isEmpty()
                            ? "Multiple DLMS payload candidates were found in the request"
                            : normalization.warnings().getFirst();
                    return state.addError(warning).toBuilder().decodeResult(null).build();
                }

                DlmsProcessingMetadata processingMetadata = toProcessingMetadata(normalization);
                return switch (normalization.kind()) {
                    case FRAME_HEX -> processFrameDecode(state, normalization.normalizedInput(), processingMetadata);
                    case APDU_HEX -> processApduDecode(state, normalization.normalizedInput(), processingMetadata);
                    case AXDR_HEX -> processAxdrDecode(state, normalization.normalizedInput(), processingMetadata);
                    case OBIS_QUERY -> processObisDecode(state, normalization.normalizedInput(), processingMetadata);
                };
            }

            return processFrameDecode(state, extractFrameHex(raw), null);
        } catch (Exception e) {
            log.warn("DecoderAgentNode failed sessionId={} err={}", state.sessionId(), e.toString());
            return state.addError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .toBuilder()
                    .decodeResult(null)
                    .build();
        }
    }

    private WorkflowState processFrameDecode(WorkflowState state, String normalized, DlmsProcessingMetadata processingMetadata) {
        try {
            if ((normalized.length() & 1) == 1) {
                return state.addError("Odd-length hex input").toBuilder().decodeResult(null).build();
            }
            if (!normalized.matches("^[0-9a-fA-F]*$")) {
                return state.addError("Non-hex characters in input").toBuilder().decodeResult(null).build();
            }

            DecodeResult dr = null;
            boolean mcpUsed = false;

            // [PHASE 8] Attempt MCP parse first
            McpResult mcp = mcpDispatcher.dispatch(McpTools.DLMS_PARSE_HDLC, Map.of("frame_hex", normalized))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(java.time.Duration.ofSeconds(10));

            if (mcp != null && mcp.success()) {
                dr = mapMcpHdlcResult(mcp.result(), normalized, processingMetadata);
                if (dr != null) {
                    log.info("Successfully decoded frame via MCP tool: {}", McpTools.DLMS_PARSE_HDLC);
                    mcpUsed = true;
                }
            }

            HdlcFrame frame;
            List<String> parseErrors = new ArrayList<>();
            if (dr != null) {
                frame = dr.hdlcFrame();
                dr.parseErrors().forEach(error -> addUnique(parseErrors, error));
            } else {
                // Java fallback pipeline
                byte[] bytes = HEX.parseHex(normalized);
                try {
                    frame = HdlcParser.parse(bytes);
                } catch (com.company.dlms.domain.decoder.HdlcParseException e) {
                    if (HdlcParser.ERR_UNEXPECTED_INFO_ON_SUPERVISORY_FRAME.equals(e.getMessage())) {
                        frame = HdlcParser.parseLenientOuterFrame(bytes);
                        addUnique(parseErrors, e.getMessage());
                    } else {
                        throw e;
                    }
                }
                if (!frame.fcsValid()) {
                    addUnique(parseErrors, "FCS invalid");
                }
            }

            if (frame.frameType() == FrameType.S_FRAME || frame.frameType() == FrameType.U_FRAME) {
                DecodeResult result = new DecodeResult(
                        frame,
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        normalized,
                        parseErrors,
                        processingMetadata
                );

                Map<String, Object> stmFields = com.company.dlms.agent.decoder.StmFieldExtractor.extract(result);
                WorkflowState.Builder builder = state.toBuilder()
                        .decodeResult(result)
                        .mcpUsed(mcpUsed)
                        .hdlcClientSap((String) stmFields.get("hdlcClientSap"))
                        .hdlcServerSap((String) stmFields.get("hdlcServerSap"))
                        .associationState((String) stmFields.get("associationState"));

                if (frame.frameType() == FrameType.U_FRAME) {
                    String assoc = deriveAssociationStateFromUFrame(frame.uFrameType());
                    if (assoc != null) builder.associationState(assoc);
                }

                WorkflowState updated = builder.build();
                persistSessionState(updated);

                return updated;
            }

            byte[] info = frame.informationField() == null ? new byte[0] : frame.informationField();
            byte[] apduBytes = LlcExtractor.extract(info);

            ApduType apduType = ApduClassifier.classify(apduBytes);
            boolean gbtPartial = false;

            byte[] payloadBytes = apduBytes.length > 1 ? java.util.Arrays.copyOfRange(apduBytes, 1, apduBytes.length) : new byte[0];

            if (apduType == ApduType.GBT) {
                Optional<GbtHeader> hdr = parseGbtHeader(apduBytes);
                if (hdr.isPresent()) {
                    GbtHeader h = hdr.get();
                    Optional<byte[]> assembled = Mono.fromCallable(() -> gbtAssembler.onBlock(
                                    state.sessionId(),
                                    h.blockNumber(),
                                    h.lastBlock(),
                                    h.blockData()
                            ).block())
                            .subscribeOn(Schedulers.boundedElastic())
                            .block();

                    if (assembled == null || assembled.isEmpty()) {
                        gbtPartial = true;
                        DecodeResult partial = new DecodeResult(frame, apduType, null, List.of(), true, normalized, parseErrors, processingMetadata);
                        return state.toBuilder().decodeResult(partial).build();
                    }
                    payloadBytes = assembled.get();
                }
            }

            AxdrValue axdrTree = null;
            if (com.company.dlms.agent.decoder.CipheredApduExtractor.isCipheredType(apduType)) {
                parseErrors.add("Ciphered APDU — payload encrypted, security header extracted");
            } else {
                try {
                    axdrTree = AxdrDecoder.decode(payloadBytes);
                } catch (AxdrDecodeException e) {
                    return state.addError(e.getMessage()).toBuilder().decodeResult(null).build();
                }
            }

            List<String> obisCodes = findObisCodes(axdrTree);
            Mono<List<ObisResolution>> resolutionsMono = Flux.fromIterable(obisCodes)
                    .flatMap(obis -> obisResolver.resolve(obis, state.sessionId()))
                    .collectList();

            List<ObisResolution> resolutions;
            try {
                resolutions = resolutionsMono
                        .subscribeOn(Schedulers.boundedElastic())
                        .block(java.time.Duration.ofSeconds(5));
                if (resolutions == null) resolutions = List.of();
            } catch (Exception e) {
                log.warn("OBIS resolution failed for sessionId={}: {}", state.sessionId(), e.getMessage());
                resolutions = List.of();
            }

            DecodeResult result = new DecodeResult(
                    frame,
                    apduType,
                    axdrTree,
                    resolutions,
                    gbtPartial,
                    normalized,
                    parseErrors,
                    processingMetadata
            );

            // US1: Extract all 10 STM fields using the utility
            Map<String, Object> stmFields = com.company.dlms.agent.decoder.StmFieldExtractor.extract(result);
            
            WorkflowState updatedState = state.toBuilder()
                    .decodeResult(result)
                    .mcpUsed(mcpUsed)
                    .hdlcClientSap((String) stmFields.get("hdlcClientSap"))
                    .hdlcServerSap((String) stmFields.get("hdlcServerSap"))
                    .frameCounter(stmFields.get("frameCounter") != null ? String.valueOf(stmFields.get("frameCounter")) : null)
                    .frameCounterHex((String) stmFields.get("frameCounterHex"))
                    .securitySuite(stmFields.get("securitySuite") != null ? String.valueOf(stmFields.get("securitySuite")) : null)
                    .invokeId((String) stmFields.get("invokeId"))
                    .associationState((String) stmFields.get("associationState"))
                    .maxPduSize(stmFields.get("maxPduSize") != null ? String.valueOf(stmFields.get("maxPduSize")) : null)
                    .lastObis((String) stmFields.get("lastObis"))
                    .lastIc(stmFields.get("lastIc") != null ? String.valueOf(stmFields.get("lastIc")) : null)
                    .build();

            persistSessionState(updatedState);

            return updatedState;
        } catch (Exception e) {
            log.warn("DecoderAgentNode failed sessionId={} err={}", state.sessionId(), e.toString());
            return state.addError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .toBuilder()
                    .decodeResult(null)
                    .build();
        }
    }

    private WorkflowState processApduDecode(WorkflowState state, String normalized, DlmsProcessingMetadata processingMetadata) {
        try {
            byte[] apduBytes = HEX.parseHex(normalized);
            ApduType apduType = ApduClassifier.classify(apduBytes);
            if (apduType == ApduType.UNKNOWN) {
                return state.addError("Unknown APDU tag").toBuilder().decodeResult(null).build();
            }

            List<String> parseErrors = new ArrayList<>();
            AxdrValue axdrTree = null;
            if (com.company.dlms.agent.decoder.CipheredApduExtractor.isCipheredType(apduType)) {
                parseErrors.add("Ciphered APDU - payload encrypted, security header extracted");
            } else {
                byte[] payloadBytes = apduBytes.length > 1 ? java.util.Arrays.copyOfRange(apduBytes, 1, apduBytes.length) : new byte[0];
                axdrTree = decodeAxdr(payloadBytes);
            }

            List<String> obisCodes = findObisCodes(axdrTree);
            List<ObisResolution> resolutions = resolveObisCodes(obisCodes, state.sessionId());
            DecodeResult result = new DecodeResult(
                    null,
                    apduType,
                    axdrTree,
                    resolutions,
                    false,
                    normalized,
                    parseErrors,
                    processingMetadata
            );

            Map<String, Object> stmFields = StmFieldExtractor.extract(result);
            WorkflowState updated = applyDirectStmFields(state, result, stmFields);
            if (updated.lastObis() == null || updated.lastObis().isBlank()) {
                String fallbackObis = obisCodes.isEmpty() ? null : obisCodes.getFirst();
                if (fallbackObis != null && !fallbackObis.isBlank()) {
                    updated = updated.toBuilder().lastObis(fallbackObis).build();
                }
            }

            persistSessionState(updated);

            return updated;
        } catch (IllegalArgumentException e) {
            return state.addError("Non-hex characters in input").toBuilder().decodeResult(null).build();
        } catch (AxdrDecodeException e) {
            return state.addError(e.getMessage()).toBuilder().decodeResult(null).build();
        }
    }

    private WorkflowState processAxdrDecode(WorkflowState state, String normalized, DlmsProcessingMetadata processingMetadata) {
        try {
            byte[] axdrBytes = HEX.parseHex(normalized);
            AxdrValue axdrTree = AxdrDecoder.decode(axdrBytes);
            List<String> obisCodes = findObisCodes(axdrTree);
            List<ObisResolution> resolutions = resolveObisCodes(obisCodes, state.sessionId());
            DecodeResult result = new DecodeResult(
                    null,
                    ApduType.UNKNOWN,
                    axdrTree,
                    resolutions,
                    false,
                    normalized,
                    List.of(),
                    processingMetadata
            );

            Map<String, Object> stmFields = StmFieldExtractor.extract(result);
            WorkflowState updated = applyDirectStmFields(state, result, stmFields);
            if ((updated.lastObis() == null || updated.lastObis().isBlank()) && !obisCodes.isEmpty()) {
                updated = updated.toBuilder().lastObis(obisCodes.getFirst()).build();
            }

            if (hasDirectSessionFacts(stmFields) || (updated.lastObis() != null && !updated.lastObis().isBlank())) {
                persistSessionState(updated);
            } else {
                persistNarrativeEvent(updated);
            }

            return updated;
        } catch (IllegalArgumentException e) {
            return state.addError("Non-hex characters in input").toBuilder().decodeResult(null).build();
        } catch (AxdrDecodeException e) {
            return state.addError(e.getMessage()).toBuilder().decodeResult(null).build();
        }
    }

    private WorkflowState processObisDecode(WorkflowState state, String obis, DlmsProcessingMetadata processingMetadata) {
        try {
            ObisResolution resolution = obisResolver.resolve(obis, state.sessionId())
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(java.time.Duration.ofSeconds(5));
            List<ObisResolution> resolutions = resolution == null ? List.of() : List.of(resolution);

            DecodeResult result = new DecodeResult(
                    null,
                    ApduType.UNKNOWN,
                    null,
                    resolutions,
                    false,
                    null,
                    List.of(),
                    processingMetadata
            );

            Map<String, Object> stmFields = StmFieldExtractor.extract(result);
            WorkflowState updated = applyDirectStmFields(state, result, stmFields);
            if (updated.lastObis() == null || updated.lastObis().isBlank()) {
                updated = updated.toBuilder().lastObis(obis).build();
            }

            persistSessionState(updated);

            return updated;
        } catch (Exception e) {
            return state.addError("OBIS resolution failed: " + e.getMessage()).toBuilder().decodeResult(null).build();
        }
    }

    /**
     * Derives association state from the U-frame subtype for STM persistence.
     * SNRM → CONNECTING, DISC/DM → DISCONNECTED, others → null.
     */
    private String deriveAssociationStateFromUFrame(UFrameType uFrameType) {
        if (uFrameType == null) return null;
        return switch (uFrameType) {
            case SNRM -> "CONNECTING";
            case DISC, DM -> "DISCONNECTED";
            default -> null;
        };
    }

    private WorkflowState applyDirectStmFields(WorkflowState state, DecodeResult result, Map<String, Object> stmFields) {
        WorkflowState.Builder builder = state.toBuilder()
                .decodeResult(result)
                .mcpUsed(false);

        Object frameCounterValue = stmFields.get("frameCounter");
        if (frameCounterValue instanceof Long counter) {
            builder.frameCounter(String.valueOf(counter));
        }
        setIfPresent(builder, (String) stmFields.get("frameCounterHex"), builder::frameCounterHex);
        Object securitySuite = stmFields.get("securitySuite");
        if (securitySuite != null) {
            builder.securitySuite(String.valueOf(securitySuite));
        }
        setIfPresent(builder, (String) stmFields.get("invokeId"), builder::invokeId);
        setIfPresent(builder, (String) stmFields.get("associationState"), builder::associationState);
        Object maxPduSize = stmFields.get("maxPduSize");
        if (maxPduSize != null) {
            builder.maxPduSize(String.valueOf(maxPduSize));
        }
        setIfPresent(builder, (String) stmFields.get("lastObis"), builder::lastObis);
        Object lastIc = stmFields.get("lastIc");
        if (lastIc != null) {
            builder.lastIc(String.valueOf(lastIc));
        }

        return builder.build();
    }

    private boolean hasDirectSessionFacts(Map<String, Object> stmFields) {
        return stmFields.get("frameCounter") != null
                || stmFields.get("frameCounterHex") != null
                || stmFields.get("securitySuite") != null
                || stmFields.get("invokeId") != null
                || stmFields.get("associationState") != null
                || stmFields.get("maxPduSize") != null
                || stmFields.get("lastObis") != null
                || stmFields.get("lastIc") != null;
    }

    private void setIfPresent(WorkflowState.Builder builder, String value, java.util.function.Function<String, WorkflowState.Builder> setter) {
        if (value != null && !value.isBlank()) {
            setter.apply(value);
        }
    }

    private void persistSessionState(WorkflowState updatedState) {
        try {
            stmService.saveStm(updatedState)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(PERSIST_TIMEOUT);
        } catch (Exception e) {
            log.warn("STM update failed for sessionId={}", updatedState.sessionId(), e);
        }
        persistNarrativeEvent(updatedState);
    }

    private void persistNarrativeEvent(WorkflowState updatedState) {
        try {
            sessionNarrativeService.appendEvent(sessionEventService.buildDecodeEvent(updatedState))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(PERSIST_TIMEOUT);
        } catch (Exception e) {
            log.warn("Session event append failed for sessionId={}", updatedState.sessionId(), e);
        }
    }

    private DecodeResult mapMcpHdlcResult(JsonNode root, String raw, DlmsProcessingMetadata processingMetadata) {
        try {
            JsonNode clientNode = firstPresent(root, "client_address", "client_sap");
            JsonNode serverNode = firstPresent(root, "server_address", "server_sap");

            // Validate that required fields are present
            if (root.path("frame_type").isMissingNode()
                    || clientNode == null
                    || serverNode == null) {
                log.info("MCP HDLC result missing required fields; falling back to Java parser");
                return null;
            }

            boolean fcs = root.path("fcs_valid").asBoolean(true);
            String typeStr = root.path("frame_type").asText("");
            FrameType frameType;
            try {
                frameType = FrameType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                log.info("MCP returned unrecognized frame_type='{}'; falling back to Java parser", typeStr);
                return null; // reject instead of defaulting to a bad value
            }

            int clientSap = clientNode.asInt(1);
            int serverSap = serverNode.asInt(1);

            // Bug 1: Support multiple keys for information field (MCP tool variations)
            String infoHex = root.has("information_hex") ? root.path("information_hex").asText() :
                            root.has("information_field") ? root.path("information_field").asText() :
                            root.has("payload") ? root.path("payload").asText() : null;

            byte[] infoField = (infoHex != null && !infoHex.isBlank()) ? HEX.parseHex(normalizeHex(infoHex)) : null;
            
            // Bug 1 Validation: if MCP HdlcFrame has null informationField AND frameType is I_FRAME 
            // -> reject MCP result, use Java parser instead.
            if (frameType == FrameType.I_FRAME && (infoField == null || infoField.length == 0)) {
                log.info("MCP returned I_FRAME without payload; rejecting MCP result to allow Java parser fallback");
                return null;
            }

            byte[] rawBytes = HEX.parseHex(raw);
            UFrameType uFrameType = parseEnum(root.path("u_frame_type").asText(null), UFrameType.class);
            SFrameType sFrameType = parseEnum(root.path("s_frame_type").asText(null), SFrameType.class);
            try {
                HdlcFrame parsedFrame = HdlcParser.parse(rawBytes);
                uFrameType = parsedFrame.uFrameType();
                sFrameType = parsedFrame.sFrameType();
            } catch (Exception subtypeError) {
                log.debug("Unable to derive HDLC subtype from raw bytes for MCP result: {}", subtypeError.getMessage());
            }

            HdlcFrame frame = new HdlcFrame(
                    frameType,
                    uFrameType,
                    sFrameType,
                    clientSap,
                    serverSap,
                    infoField,
                    fcs,
                    rawBytes
            );

            List<String> errors = new ArrayList<>();
            root.path("errors").forEach(e -> addUnique(errors, e.asText()));
            if (!fcs) addUnique(errors, "FCS invalid");

            // Classify APDU from infoField if present
            ApduType apduType = ApduType.UNKNOWN;
            if (infoField != null && infoField.length > 3) {
                // Skip LLC (3 bytes: E6 E6 00)
                byte[] apdu = java.util.Arrays.copyOfRange(infoField, 3, infoField.length);
                apduType = ApduClassifier.classify(apdu);
            }

            return new DecodeResult(frame, apduType, null, List.of(), false, raw, errors, processingMetadata);
        } catch (Exception e) {
            log.warn("Failed to map MCP HDLC result: {}", e.getMessage());
            return null;
        }
    }

    private static JsonNode firstPresent(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = root.path(fieldName);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> enumType) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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





    private static String normalizeHex(String raw) {
        return raw.trim()
                .replace(" ", "")
                .replace(":", "")
                .replace("\n", "")
                .replace("\r", "");
    }

    private static String extractFrameHex(String raw) {
        String normalized = normalizeHex(raw);
        if (normalized.matches("^[0-9a-fA-F]*$")) {
            return normalized;
        }

        Matcher matcher = EMBEDDED_HDLC_FRAME_PATTERN.matcher(raw);
        if (matcher.find()) {
            return normalizeHex(matcher.group());
        }

        return normalized;
    }

    private DlmsProcessingMetadata toProcessingMetadata(DlmsInputNormalization normalization) {
        return new DlmsProcessingMetadata(
                normalization.kind(),
                normalization.provenance(),
                normalization.warnings(),
                normalization.extractorNote()
        );
    }

    private AxdrValue decodeAxdr(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) {
            return null;
        }
        return AxdrDecoder.decode(payloadBytes);
    }

    private List<ObisResolution> resolveObisCodes(List<String> obisCodes, String sessionId) {
        if (obisCodes == null || obisCodes.isEmpty()) {
            return List.of();
        }
        Mono<List<ObisResolution>> resolutionsMono = Flux.fromIterable(obisCodes)
                .flatMap(obis -> obisResolver.resolve(obis, sessionId))
                .collectList();

        try {
            List<ObisResolution> resolutions = resolutionsMono
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(java.time.Duration.ofSeconds(5));
            return resolutions == null ? List.of() : resolutions;
        } catch (Exception e) {
            log.warn("OBIS resolution failed for sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private record GbtHeader(int blockNumber, boolean lastBlock, byte[] blockData) {}

    private static Optional<GbtHeader> parseGbtHeader(byte[] apduBytes) {
        if (apduBytes == null || apduBytes.length < 5) return Optional.empty();
        if ((apduBytes[0] & 0xFF) != 0xE6) return Optional.empty();
        int flags = apduBytes[1] & 0xFF;
        boolean last = (flags & 0x80) != 0;
        int blockNumber = ((apduBytes[2] & 0xFF) << 8) | (apduBytes[3] & 0xFF);
        byte[] data = java.util.Arrays.copyOfRange(apduBytes, 4, apduBytes.length);
        return Optional.of(new GbtHeader(blockNumber, last, data));
    }

    private static List<String> findObisCodes(AxdrValue root) {
        Set<String> out = new HashSet<>();
        walk(root, out);
        return List.copyOf(out);
    }

    private static void walk(AxdrValue v, Set<String> out) {
        if (v == null) return;
        if (v instanceof AxdrOctetString os) {
            byte[] b = os.value();
            if (b != null && b.length == 6) {
                out.add(toObis(b));
            }
        } else if (v instanceof AxdrArray arr) {
            for (AxdrValue e : arr.elements()) walk(e, out);
        } else if (v instanceof AxdrStructure st) {
            for (AxdrValue e : st.elements()) walk(e, out);
        }
    }

    private static String toObis(byte[] b) {
        return (b[0] & 0xFF) + "." +
                (b[1] & 0xFF) + "." +
                (b[2] & 0xFF) + "." +
                (b[3] & 0xFF) + "." +
                (b[4] & 0xFF) + "." +
                (b[5] & 0xFF);
    }
}
