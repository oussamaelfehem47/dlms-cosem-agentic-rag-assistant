package com.company.dlms.workflow;

import com.company.dlms.domain.CasualQueryClassifier;
import com.company.dlms.domain.answer.AnswerMode;
import com.company.dlms.domain.answer.AnswerTopicFamily;
import com.company.dlms.domain.answer.ExplanationMode;
import com.company.dlms.domain.answer.GroundedFactBundle;
import com.company.dlms.domain.answer.ToolProvenance;
import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrBitString;
import com.company.dlms.domain.decoder.AxdrBoolean;
import com.company.dlms.domain.decoder.AxdrCompactArray;
import com.company.dlms.domain.decoder.AxdrDate;
import com.company.dlms.domain.decoder.AxdrDateTime;
import com.company.dlms.domain.decoder.AxdrEnum;
import com.company.dlms.domain.decoder.ApduType;
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
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.orchestration.StrategyCandidate;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.infrastructure.llm.MathMarkupFilter;
import com.company.dlms.infrastructure.llm.OllamaStreamingClient;
import com.company.dlms.infrastructure.llm.PromptAssembler;
import com.company.dlms.infrastructure.llm.GroundedAnswerQualityGate;
import com.company.dlms.infrastructure.llm.GroundedFactBundleBuilder;
import com.company.dlms.infrastructure.llm.ThinkTagFilter;
import com.company.dlms.infrastructure.security.OutputFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class StreamingWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(StreamingWorkflowService.class);

    @NonNull private final WorkflowOrchestrator orchestrator;
    @NonNull private final HybridAgenticPlannerService hybridAgenticPlannerService;
    @NonNull private final GroundedAnswerBuilder groundedAnswerBuilder;
    @NonNull private final FollowUpResolver followUpResolver;
    @NonNull private final PromptAssembler promptAssembler;
    @NonNull private final GroundedFactBundleBuilder groundedFactBundleBuilder;
    @NonNull private final GroundedAnswerQualityGate groundedAnswerQualityGate;
    @NonNull private final OllamaStreamingClient ollamaStreamingClient;
    @NonNull private final ThinkTagFilter thinkTagFilter;
    @NonNull private final MathMarkupFilter mathMarkupFilter;
    @NonNull private final OutputFilter outputFilter;
    @NonNull private final ObjectMapper objectMapper;

    public StreamingWorkflowService(
            @NonNull WorkflowOrchestrator orchestrator,
            @NonNull HybridAgenticPlannerService hybridAgenticPlannerService,
            @NonNull GroundedAnswerBuilder groundedAnswerBuilder,
            @NonNull FollowUpResolver followUpResolver,
            @NonNull PromptAssembler promptAssembler,
            @NonNull GroundedFactBundleBuilder groundedFactBundleBuilder,
            @NonNull GroundedAnswerQualityGate groundedAnswerQualityGate,
            @NonNull OllamaStreamingClient ollamaStreamingClient,
            @NonNull ThinkTagFilter thinkTagFilter,
            @NonNull MathMarkupFilter mathMarkupFilter,
            @NonNull OutputFilter outputFilter,
            @NonNull ObjectMapper objectMapper
    ) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.hybridAgenticPlannerService = Objects.requireNonNull(hybridAgenticPlannerService, "hybridAgenticPlannerService");
        this.groundedAnswerBuilder = Objects.requireNonNull(groundedAnswerBuilder, "groundedAnswerBuilder");
        this.followUpResolver = Objects.requireNonNull(followUpResolver, "followUpResolver");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler");
        this.groundedFactBundleBuilder = Objects.requireNonNull(groundedFactBundleBuilder, "groundedFactBundleBuilder");
        this.groundedAnswerQualityGate = Objects.requireNonNull(groundedAnswerQualityGate, "groundedAnswerQualityGate");
        this.ollamaStreamingClient = Objects.requireNonNull(ollamaStreamingClient, "ollamaStreamingClient");
        this.thinkTagFilter = Objects.requireNonNull(thinkTagFilter, "thinkTagFilter");
        this.mathMarkupFilter = Objects.requireNonNull(mathMarkupFilter, "mathMarkupFilter");
        this.outputFilter = Objects.requireNonNull(outputFilter, "outputFilter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Flux<ServerSentEvent<String>> streamDecode(@NonNull WorkflowRequest request) {
        return streamWorkflow(request, "decode");
    }

    public Flux<ServerSentEvent<String>> streamSiconia(@NonNull WorkflowRequest request) {
        return streamWorkflow(request, "analysis");
    }

    public Flux<ServerSentEvent<String>> streamChat(@NonNull WorkflowRequest request) {
        return streamWorkflow(request, null);
    }

    private Flux<ServerSentEvent<String>> streamWorkflow(@NonNull WorkflowRequest request, String firstType) {
        return orchestrator.executeRaw(request)
                .flatMap(hybridAgenticPlannerService::applyIfNeeded)
                .flatMapMany(result -> {
                    WorkflowState safeResult = Objects.requireNonNull(result, "workflowResult")
                            .withGroundedAnswerContext(groundedAnswerBuilder.build(result));
                    String systemPrompt = Objects.requireNonNull(promptAssembler.systemPrompt(safeResult), "systemPrompt");
                    String prompt = Objects.requireNonNull(promptAssembler.assemble(safeResult), "prompt");
                    String resolvedFirstType = resolveFirstType(safeResult, firstType);

                    // Build metadata map including decodeResult and siconiaResult as JSON
                    Map<String, Object> meta = new java.util.LinkedHashMap<>();
                    meta.put("sessionId", safeResult.sessionId());
                    meta.put("inputClass", safeResult.inputClass() != null ? safeResult.inputClass().name() : null);
                    meta.put("intent", safeResult.intent() != null ? safeResult.intent().name() : null);
                    meta.put("orchestrationMode", safeResult.orchestrationMode() != null ? safeResult.orchestrationMode().name() : null);
                    meta.put("plannerUsed", safeResult.plannerUsed());
                    if (safeResult.strategyMetadata() != null) {
                        meta.put("strategyMetadata", serializeToRawMap(safeResult.strategyMetadata()));
                    }
                    if (safeResult.toolTrace() != null && !safeResult.toolTrace().isEmpty()) {
                        meta.put("toolTrace", safeResult.toolTrace().stream().map(this::serializeToRawMap).toList());
                    }
                    if (safeResult.plannerFallbackReason() != null && !safeResult.plannerFallbackReason().isBlank()) {
                        meta.put("plannerFallbackReason", safeResult.plannerFallbackReason());
                    }
                    if (safeResult.groundedAnswerContext() != null) {
                        meta.put("answerMode", safeResult.groundedAnswerContext().mode().name());
                        meta.put("answerSelectedStrategy", safeResult.groundedAnswerContext().selectedStrategy().name());
                        meta.put("answerConfidence", safeResult.groundedAnswerContext().confidence());
                        meta.put("answerTentative", safeResult.groundedAnswerContext().tentative());
                    }

                    // Serialize decodeResult into the SSE event so the UI can render the decode tree
                    if (safeResult.decodeResult() instanceof DecodeResult dr) {
                        Map<String, Object> decodeMap = new java.util.LinkedHashMap<>(serializeToRawMap(dr));
                        if (dr.axdrTree() != null) {
                            decodeMap.put("axdrTree", serializeAxdrForUi(dr.axdrTree()));
                        }
                        if (safeResult.profileResult() != null) {
                            decodeMap.put("profileResult", serializeToRawMap(safeResult.profileResult()));
                        }
                        meta.put("decodeResult", decodeMap);
                        if (dr.processingMetadata() != null) {
                            meta.put("decodeProcessingMetadata", serializeToRawMap(dr.processingMetadata()));
                        }
                    }

                    // Serialize siconiaResult into the SSE event so the UI can render structured analysis
                    if (safeResult.siconiaResult() != null) {
                        meta.put("siconiaResult", serializeToRawMap(safeResult.siconiaResult()));
                        if (safeResult.siconiaResult().processingMetadata() != null) {
                            meta.put("siconiaProcessingMetadata", serializeToRawMap(safeResult.siconiaResult().processingMetadata()));
                        }
                    }

                    if (safeResult.anomalies() != null && !safeResult.anomalies().isEmpty()) {
                        meta.put("anomalies", safeResult.anomalies());
                    }

                    if (safeResult.decodeResult() instanceof DecodeResult dr) {
                        meta.put("frameLength", dr.rawHex() != null ? dr.rawHex().length() / 2 : 0);
                    }

                    // === DETERMINISTIC FALLBACK ===
                    // When FCS is invalid or the APDU type is unknown (and it's NOT a U-frame/S-frame),
                    // bypass the LLM entirely to prevent hallucination.
                    // U-frames and S-frames legitimately have no APDU — they are HDLC link-layer control frames.
                    boolean fallback = shouldUseFallback(safeResult);
                    ExplanationMode explanationMode = determineExplanationMode(safeResult, fallback);
                    ToolProvenance toolProvenance = determineToolProvenance(safeResult);
                    meta.put("usedFallback", fallback);
                    if (explanationMode != null) {
                        meta.put("explanationMode", explanationMode.name());
                    }
                    if (toolProvenance != null) {
                        meta.put("toolProvenance", toolProvenance.name());
                    }
                    log.debug("streamWorkflow fallback={} sessionId={} inputClass={} intent={}",
                            fallback,
                            safeResult.sessionId(),
                            safeResult.inputClass() == null ? null : safeResult.inputClass().name(),
                            safeResult.intent() == null ? null : safeResult.intent().name());
                    Flux<ServerSentEvent<String>> contentEvents = contentEventsForAnswer(
                            safeResult,
                            systemPrompt,
                            prompt,
                            fallback,
                            explanationMode
                    );

                    Flux<ServerSentEvent<String>> citationEvents = buildCitationEvents(safeResult);

                    return Flux.concat(
                            Mono.just(event(resolvedFirstType, meta)),
                            contentEvents,
                            citationEvents,
                            Mono.just(event("done", (Object) Map.of()))
                    );
                })
                .onErrorResume(ex -> Flux.concat(
                        Mono.just(event("filtered", (Object) Map.of("reason", "stream_failed"))),
                        Mono.just(event("done", (Object) Map.of()))
                ));
    }

    private Flux<ServerSentEvent<String>> contentEventsForAnswer(
            @NonNull WorkflowState state,
            @NonNull String systemPrompt,
            @NonNull String prompt,
            boolean fallback,
            ExplanationMode explanationMode
    ) {
        String groundedResponse = deterministicStructuredResponse(state);
        if (groundedResponse != null) {
            return emitVisibleText(groundedResponse);
        }
        if (explanationMode == ExplanationMode.GROUNDED_LLM
                || explanationMode == ExplanationMode.TENTATIVE_GROUNDED) {
            GroundedFactBundle bundle = groundedFactBundleBuilder.build(state);
            return groundedDeterministicContentEvents(state, systemPrompt, prompt, bundle);
        }
        if (fallback && state.decodeResult() instanceof DecodeResult dr) {
            return generateFallbackResponse(state, dr);
        }
        if (state.groundedAnswerContext() != null
                && state.groundedAnswerContext().mode() == AnswerMode.DETERMINISTIC_DECODE
                && state.decodeResult() instanceof DecodeResult dr) {
            String deterministicDecode = groundedDeterministicDecodeResponse(state, dr);
            if (deterministicDecode != null) {
                return emitVisibleText(deterministicDecode);
            }
        }
        if (state.groundedAnswerContext() != null
                && state.groundedAnswerContext().mode() == AnswerMode.DETERMINISTIC_SICONIA) {
            String deterministicSiconia = groundedDeterministicSiconiaResponse(state.siconiaResult());
            if (deterministicSiconia != null) {
                return emitVisibleText(deterministicSiconia);
            }
        }
        if (state.intent() == com.company.dlms.domain.DlmsIntent.FRAME_DECODE
                && state.errors() != null
                && !state.errors().isEmpty()) {
            return generateFrameDecodeErrorResponse(state.errors());
        }
        return llmContentEvents(state, systemPrompt, prompt);
    }

    private Flux<ServerSentEvent<String>> llmContentEvents(
            @NonNull WorkflowState state,
            @NonNull String systemPrompt,
            @NonNull String prompt
    ) {
        AnswerMode mode = state.groundedAnswerContext() == null ? null : state.groundedAnswerContext().mode();
        if (mode == AnswerMode.RETRIEVAL_DOCS || mode == AnswerMode.RETRIEVAL_SECURITY) {
            GroundedFactBundle bundle = groundedFactBundleBuilder.build(state);
            return safeOllamaStream(systemPrompt, prompt)
                    .transform(thinkTagFilter::filter)
                    .transform(mathMarkupFilter::filter)
                    .take(Duration.ofSeconds(60))
                    .collectList()
                    .flatMapMany(tokens -> {
                        String generated = String.join("", tokens);
                        String finalText = selectRetrievalAnswerText(state, bundle, generated);
                        return emitVisibleText(finalText);
                    });
        }
        return safeOllamaStream(systemPrompt, prompt)
                .transform(thinkTagFilter::filter)
                .transform(mathMarkupFilter::filter)
                // Prevent endless "thinking" streams from hanging the UI forever.
                .take(Duration.ofSeconds(60))
                .flatMap(this::emitVisibleText);
    }

    private Flux<ServerSentEvent<String>> groundedDeterministicContentEvents(
            @NonNull WorkflowState state,
            @NonNull String systemPrompt,
            @NonNull String prompt,
            @NonNull GroundedFactBundle bundle
    ) {
        return safeOllamaStream(systemPrompt, prompt)
                .transform(thinkTagFilter::filter)
                .transform(mathMarkupFilter::filter)
                .take(Duration.ofSeconds(60))
                .collectList()
                .flatMapMany(tokens -> {
                    String generated = String.join("", tokens);
                    String finalText = selectGroundedDeterministicAnswerText(state, bundle, generated);
                    return emitVisibleText(finalText);
                });
    }

    private Flux<String> safeOllamaStream(@NonNull String systemPrompt, @NonNull String prompt) {
        Flux<String> stream = ollamaStreamingClient.stream(systemPrompt, prompt);
        return stream == null ? Flux.empty() : stream;
    }

    private ExplanationMode determineExplanationMode(@NonNull WorkflowState state, boolean fallback) {
        AnswerMode mode = state.groundedAnswerContext() == null ? null : state.groundedAnswerContext().mode();
        if (mode == AnswerMode.DETERMINISTIC_DECODE && state.decodeResult() instanceof DecodeResult dr) {
            if (shouldDowngradeStructuredAgenticDecodeToDeterministicOnly(state)) {
                return ExplanationMode.DETERMINISTIC_ONLY;
            }
            if (fallback) {
                if (dr.hdlcFrame() != null) {
                    return ExplanationMode.TENTATIVE_GROUNDED;
                }
                return ExplanationMode.DETERMINISTIC_ONLY;
            }
            if (dr.hdlcFrame() != null) {
                if (!dr.hdlcFrame().fcsValid()) {
                    return ExplanationMode.TENTATIVE_GROUNDED;
                }
                if (firstNonFcsParseError(dr) != null) {
                    return ExplanationMode.TENTATIVE_GROUNDED;
                }
            }
            return ExplanationMode.GROUNDED_LLM;
        }
        if (mode == AnswerMode.DETERMINISTIC_SICONIA && state.siconiaResult() != null) {
            if (state.siconiaResult().processingMetadata() != null
                    && state.siconiaResult().processingMetadata().provenance() == com.company.dlms.domain.siconia.ParseProvenance.RAW_FALLBACK) {
                return ExplanationMode.DETERMINISTIC_ONLY;
            }
            return ExplanationMode.GROUNDED_LLM;
        }
        return null;
    }

    private ToolProvenance determineToolProvenance(@NonNull WorkflowState state) {
        boolean structured = state.decodeResult() instanceof DecodeResult || state.siconiaResult() != null;
        if (!structured) {
            return null;
        }
        boolean retrievalEnrichment = state.retrievalResults() != null
                && !state.retrievalResults().isEmpty()
                && state.toolTrace() != null
                && state.toolTrace().stream().anyMatch(entry -> "search_docs".equalsIgnoreCase(entry.toolName()));
        if (retrievalEnrichment) {
            return ToolProvenance.MIXED;
        }
        if (state.mcpUsed()) {
            return ToolProvenance.MCP;
        }
        return ToolProvenance.JAVA;
    }

    private String selectGroundedDeterministicAnswerText(
            @NonNull WorkflowState state,
            @NonNull GroundedFactBundle bundle,
            String generatedAnswer
    ) {
        OutputFilter.FilterResult filtered = outputFilter.filter(stripGeneratedSourceFooter(generatedAnswer));
        if (filtered.blocked()) {
            return deterministicStructuredFallbackText(state, bundle);
        }
        String sanitized = filtered.content() == null ? "" : filtered.content();
        var evaluation = groundedAnswerQualityGate.evaluate(state, bundle, sanitized);
        if (evaluation.decision() == GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK) {
            return deterministicStructuredFallbackText(state, bundle);
        }
        String polished = stripLeadingDocumentQuote(sanitized);
        if (polished == null || polished.isBlank()) {
            return deterministicStructuredFallbackText(state, bundle);
        }
        return polished;
    }

    private String deterministicStructuredFallbackText(
            @NonNull WorkflowState state,
            @NonNull GroundedFactBundle bundle
    ) {
        AnswerTopicFamily family = bundle.family();
        if (family == AnswerTopicFamily.DECODE_HDLC_TENTATIVE_OUTER_ROLE
                && state.decodeResult() instanceof DecodeResult dr) {
            return tentativeOuterRoleResponse(state, dr);
        }
        if ((family == AnswerTopicFamily.DECODE_HDLC_U_FRAME
                || family == AnswerTopicFamily.DECODE_HDLC_S_FRAME
                || family == AnswerTopicFamily.DECODE_APDU_OPERATION
                || family == AnswerTopicFamily.DECODE_AXDR_VALUE
                || family == AnswerTopicFamily.DECODE_OBIS_LOOKUP)
                && state.decodeResult() instanceof DecodeResult dr) {
            String deterministic = groundedDeterministicDecodeResponse(state, dr);
            if (deterministic != null && !deterministic.isBlank()) {
                return deterministic;
            }
        }
        if ((family == AnswerTopicFamily.SICONIA_ALARM_SUMMARY
                || family == AnswerTopicFamily.SICONIA_LOG_SUMMARY
                || family == AnswerTopicFamily.SICONIA_XML_SUMMARY)
                && state.siconiaResult() != null) {
            String deterministic = groundedDeterministicSiconiaResponse(state.siconiaResult());
            if (deterministic != null && !deterministic.isBlank()) {
                return deterministic;
            }
        }
        if (family == AnswerTopicFamily.NONE) {
            if (state.decodeResult() instanceof DecodeResult dr) {
                String deterministic = groundedDeterministicDecodeResponse(state, dr);
                if (deterministic != null && !deterministic.isBlank()) {
                    return deterministic;
                }
            }
            if (state.siconiaResult() != null) {
                String deterministic = groundedDeterministicSiconiaResponse(state.siconiaResult());
                if (deterministic != null && !deterministic.isBlank()) {
                    return deterministic;
                }
            }
        }
        return groundedAnswerQualityGate.fallbackSummary(state, bundle);
    }

    /**
     * Determines whether the LLM should be bypassed in favor of a deterministic
     * fallback response.
     *
     * U-frames (SNRM, UA, DM, DISC) and S-frames (RR, RNR, REJ) are HDLC
     * link-layer control frames that do NOT contain APDU payloads. Their
     * apduType is UNKNOWN by design — this is NOT an error and should NOT
     * trigger the fallback path.
     */
    private boolean shouldUseFallback(@NonNull WorkflowState state) {
        if (!(state.decodeResult() instanceof DecodeResult dr)) {
            log.debug("shouldUseFallback=false reason=noDecodeResult sessionId={}", state.sessionId());
            return false;
        }
        DlmsNormalizedKind normalizedKind = dr.processingMetadata() != null ? dr.processingMetadata().normalizedKind() : null;
        if (dr.hdlcFrame() == null) {
            if (normalizedKind == DlmsNormalizedKind.OBIS_QUERY || normalizedKind == DlmsNormalizedKind.AXDR_HEX) {
                log.debug("shouldUseFallback=false reason=directPayload kind={} apduType={} sessionId={}",
                        normalizedKind, dr.apduType(), state.sessionId());
                return false;
            }
            if (normalizedKind == DlmsNormalizedKind.APDU_HEX) {
                boolean fallback = dr.apduType() == ApduType.UNKNOWN || (dr.parseErrors() != null && !dr.parseErrors().isEmpty());
                log.debug("shouldUseFallback={} reason=directApdu kind={} apduType={} sessionId={}",
                        fallback, normalizedKind, dr.apduType(), state.sessionId());
                return fallback;
            }
            log.debug("shouldUseFallback=false reason=noHdlcFrameNoDirectKind sessionId={}", state.sessionId());
            return false;
        }
        String frameType = dr.hdlcFrame() != null && dr.hdlcFrame().frameType() != null
                ? dr.hdlcFrame().frameType().name() : "null";
        String apduType = dr.apduType() != null ? dr.apduType().name() : "null";
        // Bypass LLM when FCS is invalid — the payload may be corrupted
        if (!dr.hdlcFrame().fcsValid()) {
            log.debug("shouldUseFallback=true reason=fcsInvalid frameType={} apduType={} sessionId={}",
                    frameType, apduType, state.sessionId());
            return true;
        }
        // U-frames and S-frames have no APDU by design — do NOT bypass
        if (dr.hdlcFrame().frameType() == FrameType.U_FRAME ||
            dr.hdlcFrame().frameType() == FrameType.S_FRAME) {
            log.debug("shouldUseFallback=false reason=controlFrame frameType={} apduType={} sessionId={}",
                    frameType, apduType, state.sessionId());
            return false;
        }
        // Bypass LLM when the APDU type is unknown — prevents guessing
        if (dr.apduType() == ApduType.UNKNOWN) {
            log.debug("shouldUseFallback=true reason=unknownApdu frameType={} apduType={} sessionId={}",
                    frameType, apduType, state.sessionId());
            return true;
        }
        // Bypass LLM when there are fatal parse errors
        if (dr.parseErrors() != null && !dr.parseErrors().isEmpty()) {
            log.debug("shouldUseFallback=true reason=parseErrors frameType={} apduType={} errors={} sessionId={}",
                    frameType, apduType, dr.parseErrors(), state.sessionId());
            return true;
        }
        log.debug("shouldUseFallback=false reason=normal frameType={} apduType={} sessionId={}",
                frameType, apduType, state.sessionId());
        return false;
    }

    /**
     * Generates a deterministic, informative response for FCS-invalid or unknown
     * frames. This completely bypasses the LLM to guarantee correctness and
     * prevent hallucination.
     */
    private Flux<ServerSentEvent<String>> generateFallbackResponse(@NonNull WorkflowState state, @NonNull DecodeResult dr) {
        return emitVisibleText(fallbackResponseText(state, dr));
    }

    private String fallbackResponseText(@NonNull WorkflowState state, @NonNull DecodeResult dr) {
        StringBuilder sb = new StringBuilder();

        var hdlc = dr.hdlcFrame();
        DlmsNormalizedKind normalizedKind = dr.processingMetadata() != null ? dr.processingMetadata().normalizedKind() : null;
        String anomalySummary = summarizeAnomalies(state);
        boolean hasAnomalies = anomalySummary != null;
        boolean replayRisk = hasReplayCounterAnomaly(state);

        if (hdlc == null) {
            if (normalizedKind == DlmsNormalizedKind.APDU_HEX) {
                sb.append("What happened: The deterministic parser identified a direct APDU payload but could not fully interpret every field.\n");
                sb.append("Can I trust it: Trust the APDU type and any decoded AXDR or OBIS details, but do not infer missing HDLC framing.\n");
                sb.append("Next step: Verify whether the payload is encrypted or truncated, then inspect the structured decode details.\n");
                return sb.toString();
            }
            if (normalizedKind == DlmsNormalizedKind.AXDR_HEX) {
                sb.append("What happened: The input was treated as raw AXDR payload bytes without an APDU or HDLC envelope.\n");
                sb.append("Can I trust it: Trust only the deterministic AXDR tree and any recovered OBIS identifiers.\n");
                sb.append("Next step: Compare the structured AXDR output with the source capture before inferring protocol context.\n");
                return sb.toString();
            }
            if (normalizedKind == DlmsNormalizedKind.OBIS_QUERY) {
                sb.append("What happened: The request was resolved as an OBIS lookup rather than a frame decode.\n");
                sb.append("Can I trust it: Trust the deterministic OBIS resolution shown in the structured details.\n");
                sb.append("Next step: Use the cited OBIS meaning directly or ask for the surrounding DLMS context if needed.\n");
                return sb.toString();
            }
        }

        if (!hdlc.fcsValid()) {
            String structuralIssue = firstNonFcsParseError(dr);
            sb.append("What happened: FCS validation failed for this ")
                    .append(hdlc.frameType())
                    .append(" frame, so the checksum did not match");
            if (structuralIssue != null) {
                sb.append(", and the parser also reported ")
                        .append(structuralIssue.toLowerCase(Locale.ROOT));
            }
            if (hasAnomalies) {
                sb.append(" and deterministic anomaly detection also flagged ")
                        .append(anomalySummary)
                        .append(".");
                if (replayRisk) {
                    sb.append(" That anomaly pattern is consistent with a possible replay or counter-regression condition.");
                }
            } else {
                sb.append(".");
            }
            sb.append("\n");
            sb.append("Can I trust it: ");
            if (hasAnomalies) {
                sb.append("Treat the anomaly findings as authoritative, but only the outer HDLC fields are tentative because the checksum failure means payload details may be corrupted.\n");
            } else {
                sb.append("Only the outer HDLC fields are tentative. Payload and APDU details may be corrupted.\n");
            }
            String outerRole = tentativeOuterRoleMeaning(dr);
            if (outerRole != null) {
                sb.append("Why it still matters: ").append(outerRole).append("\n");
            }
            sb.append("Next step: Re-capture or retransmit the frame, then inspect the communication path");
            if (hasAnomalies) {
                sb.append(" and compare the frame counter/session state with prior traffic");
            }
            sb.append(" if the problem repeats.\n");
        } else if (dr.apduType() == ApduType.UNKNOWN) {
            // FCS is valid but the APDU type is unrecognized.
            // NOTE: This path is only reached for I-frames with an unknown APDU type.
            // U-frames and S-frames are excluded by shouldUseFallback() above.
            sb.append("What happened: The deterministic parser could not identify a valid DLMS APDU in this frame.\n");
            sb.append("Can I trust it: Trust the raw frame metadata, but do not infer a protocol meaning for the payload.\n");
            sb.append("Next step: Verify frame boundaries, security state, and parser compatibility before retrying the decode.");
            if (hasAnomalies) {
                sb.append(" Also investigate ").append(anomalySummary).append(".");
            }
            sb.append("\n");
        } else if (dr.parseErrors() != null && !dr.parseErrors().isEmpty()) {
            // FCS valid, APDU known, but parse errors occurred
            sb.append("What happened: The deterministic parser hit decode errors while processing this frame.\n");
            sb.append("Can I trust it: The frame is only partially decoded, so some fields may be incomplete or incorrect.\n");
            sb.append("Next step: Review the parse errors and verify the capture before relying on detailed field values.");
            if (hasAnomalies) {
                sb.append(" The anomaly findings remain authoritative: ").append(anomalySummary).append(".");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private Flux<ServerSentEvent<String>> generateFrameDecodeErrorResponse(List<String> errors) {
        String detail = errors == null || errors.isEmpty()
                ? "No valid HDLC frame could be extracted from the input"
                : errors.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("What happened: The deterministic parser could not decode a valid HDLC frame from this input.\n");
        sb.append("Can I trust it: No structured frame decode was produced, so do not infer protocol meaning from the text.\n");
        sb.append("Next step: Paste only the hex frame, or wrap a single full 7E...7E frame in the request. ");
        sb.append("Parser detail: ").append(detail).append(".\n");
        return emitVisibleText(sb.toString());
    }

    private String deterministicStructuredResponse(@NonNull WorkflowState state) {
        AnswerMode mode = state.groundedAnswerContext() == null ? null : state.groundedAnswerContext().mode();
        var followUpResolution = shouldUseSessionRecallResponse(state)
                ? followUpResolver.resolve(state)
                : java.util.Optional.<FollowUpResolver.FollowUpResolution>empty();
        if (followUpResolution.isPresent() && followUpResolution.get().resolvedFromContext()) {
            return followUpResolution.get().answer();
        }
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case CASUAL_HELP -> groundedCasualHelpResponse(state);
            case AMBIGUOUS -> deterministicAmbiguityResponse(state.strategyMetadata());
            case SESSION_RECALL -> followUpResolution.map(FollowUpResolver.FollowUpResolution::answer).orElse(null);
            case DETERMINISTIC_DECODE, DETERMINISTIC_SICONIA -> null;
            case RETRIEVAL_SECURITY, RETRIEVAL_DOCS -> null;
            case FAILURE -> null;
        };
    }

    private String groundedCasualHelpResponse(@NonNull WorkflowState state) {
        String rawInput = state.rawInput() == null ? "" : state.rawInput().trim();
        if (CasualQueryClassifier.isAssistantCapabilityQuestion(rawInput)) {
            return capabilityReply();
        }
        if (CasualQueryClassifier.isCasualNonTechnicalQuery(rawInput)) {
            return casualReply(rawInput);
        }
        return null;
    }

    private String groundedDeterministicDecodeResponse(@NonNull WorkflowState state, @NonNull DecodeResult dr) {
        DlmsNormalizedKind normalizedKind = dr.processingMetadata() != null ? dr.processingMetadata().normalizedKind() : null;
        String controlFrameStructuralIssue = firstNonFcsParseError(dr);
        if (dr.hdlcFrame() != null
                && (!dr.hdlcFrame().fcsValid() || controlFrameStructuralIssue != null)) {
            return tentativeOuterRoleResponse(state, dr);
        }
        if (dr.hdlcFrame() != null
                && dr.hdlcFrame().fcsValid()
                && (dr.hdlcFrame().frameType() == FrameType.U_FRAME || dr.hdlcFrame().frameType() == FrameType.S_FRAME)) {
            return groundedControlFrameResponse(dr);
        }
        if (normalizedKind == DlmsNormalizedKind.OBIS_QUERY) {
            String details = dr.obisResolutions() != null && !dr.obisResolutions().isEmpty()
                    ? "Use the structured panel for the resolved OBIS meaning, interface class, unit, and scaler."
                    : "Use the structured panel for the deterministic OBIS lookup details.";
            return "What it means: The request resolves to a deterministic OBIS lookup.\n"
                    + "Why it matters: The meaning comes from authoritative OBIS resolution, not from a guessed frame decode.\n"
                    + "Next step: " + details + "\n";
        }
        if (normalizedKind == DlmsNormalizedKind.APDU_HEX) {
            String apduType = dr.apduType() == null ? "UNKNOWN" : dr.apduType().name();
            if (dr.apduType() == ApduType.GET_RESPONSE) {
                String obisDetail = firstObisDetail(dr);
                return "What it means: The payload decodes deterministically as GET_RESPONSE, which is the server response to a prior GET_REQUEST, without an HDLC envelope.\n"
                        + "Why it matters: The decoded APDU, AXDR, and OBIS fields describe the returned object structure directly, without relying on freeform inference."
                        + (obisDetail == null ? "" : " The response includes " + obisDetail + ".") + "\n"
                        + "Next step: Use the structured panel to inspect the response structure, AXDR content, and resolved OBIS details.\n";
            }
            return "What it means: The payload decodes deterministically as " + apduType + " without an HDLC envelope.\n"
                    + "Why it matters: The decoded APDU, AXDR, and OBIS fields describe the DLMS operation directly, without relying on freeform inference.\n"
                    + "Next step: Use the structured panel for the APDU, AXDR, and OBIS details.\n";
        }
        if (normalizedKind == DlmsNormalizedKind.AXDR_HEX) {
            String axdrSummary = summarizeAxdrTopLevelValue(dr);
            return "What it means: The payload decodes as " + axdrSummary + " in raw AXDR form without an APDU or HDLC envelope.\n"
                    + "Why it matters: " + describeAxdrTopLevelValue(dr) + "\n"
                    + "Next step: Use the structured panel to inspect the AXDR hierarchy and any recovered OBIS or object identifiers.\n";
        }
        if (state.intent() == com.company.dlms.domain.DlmsIntent.FRAME_DECODE
                && state.errors() != null
                && !state.errors().isEmpty()) {
            return null;
        }
        return null;
    }

    private String groundedDeterministicSiconiaResponse(SiconiaResult sr) {
        if (sr == null || sr.processingMetadata() == null
                || sr.processingMetadata().provenance() == com.company.dlms.domain.siconia.ParseProvenance.RAW_FALLBACK) {
            return null;
        }
        if (sr.alarmResults() != null && !sr.alarmResults().isEmpty()) {
            if (sr.alarmResults().size() == 1) {
                var alarm = sr.alarmResults().getFirst();
                return "What it means: Alarm " + alarm.code() + " is " + alarm.severity() + " on " + alarm.affectedComponent() + ".\n"
                        + "Impact: " + siconiaAlarmImpact(alarm) + "\n"
                        + "Next step: " + ensureSentence(alarm.remediation()) + "\n";
            }
            String alarmList = sr.alarmResults().stream()
                    .map(alarm -> alarm.code() + " " + alarm.rootCause() + " on " + alarm.affectedComponent())
                    .collect(Collectors.joining("; "));
            return "What it means: " + sr.alarmResults().size() + " alarms were decoded from this input: " + alarmList + ".\n"
                    + "Impact: This is a grouped alarm condition, so you should assess the combined operational impact rather than treating only one flag in isolation.\n"
                    + "Next step: Address the highest-severity or safety-related alarms first, then review the remaining remediations in the structured panel.\n";
        }
        if (sr.logAnalysis() != null) {
            String categories = sr.logAnalysis().issueCategories() == null || sr.logAnalysis().issueCategories().isEmpty()
                    ? "the listed issue categories"
                    : sr.logAnalysis().issueCategories().stream().map(Enum::name).collect(Collectors.joining(", "));
            return "What it means: The log is dominated by " + sr.logAnalysis().dominantLayer() + " issues.\n"
                    + "Impact: " + sr.logAnalysis().highestSeverity() + " severity with " + sr.logAnalysis().errorLineCount()
                    + " error lines out of " + sr.logAnalysis().lineCount() + ".\n"
                    + "Next step: Inspect the " + sr.logAnalysis().dominantLayer() + " path and " + categories + ".\n";
        }
        if (sr.xmlTrace() != null) {
            int eventCount = sr.xmlTrace().events() == null ? 0 : sr.xmlTrace().events().size();
            return "What it means: The input was parsed as an XML trace with " + eventCount + " recovered event" + (eventCount == 1 ? "" : "s") + ".\n"
                    + "Impact: Recovered alarm, device, timestamp, and severity details are available in the structured panel.\n"
                    + "Next step: Use the structured panel to verify the recovered XML fields before acting on the trace.\n";
        }
        return null;
    }

    private String deterministicAmbiguityResponse(StrategyMetadata strategyMetadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("What happened: I found more than one plausible interpretation for this input.\n");
        if (!strategyMetadata.candidates().isEmpty()) {
            sb.append("Best candidates:\n");
            int count = 1;
            for (StrategyCandidate candidate : strategyMetadata.candidates().stream().limit(3).toList()) {
                sb.append(count)
                        .append(". ")
                        .append(candidate.label())
                        .append(" (confidence ")
                        .append(Math.round(candidate.confidence() * 100))
                        .append("%)");
                if (candidate.rationale() != null && !candidate.rationale().isBlank()) {
                    sb.append(" — ").append(candidate.rationale());
                }
                sb.append("\n");
                count += 1;
            }
        }
        sb.append("Can I trust it: Not yet. The assistant is surfacing grounded candidates instead of guessing a single decode path.\n");
        sb.append("Next step: Clarify which interpretation you want, or resend a more explicit payload so the deterministic parser can choose one route confidently.\n");
        return sb.toString();
    }

    private String selectRetrievalAnswerText(
            @NonNull WorkflowState state,
            @NonNull GroundedFactBundle bundle,
            String generatedAnswer
    ) {
        OutputFilter.FilterResult filtered = outputFilter.filter(stripGeneratedSourceFooter(generatedAnswer));
        if (filtered.blocked()) {
            return finalizeRetrievalAnswer(state, bundle, groundedAnswerQualityGate.fallbackSummary(state, bundle));
        }
        String sanitized = filtered.content() == null ? "" : filtered.content();
        var evaluation = groundedAnswerQualityGate.evaluate(state, bundle, sanitized);
        if (evaluation.decision() == GroundedAnswerQualityGate.Decision.USE_GROUNDED_FALLBACK) {
            String fallback = groundedAnswerQualityGate.fallbackSummary(state, bundle);
            if (fallback != null && !fallback.isBlank()) {
                return finalizeRetrievalAnswer(state, bundle, fallback);
            }
        }
        return finalizeRetrievalAnswer(state, bundle, sanitized);
    }

    private String finalizeRetrievalAnswer(
            @NonNull WorkflowState state,
            @NonNull GroundedFactBundle bundle,
            String answer
    ) {
        String body = answer == null ? "" : answer;
        String withoutSources = stripGeneratedSourceFooter(body);
        OutputFilter.FilterResult filtered = outputFilter.filter(withoutSources);
        if (filtered.blocked() || filtered.content() == null || filtered.content().isBlank()) {
            return groundedAnswerQualityGate.fallbackSummary(state, bundle);
        }
        String polished = stripLeadingDocumentQuote(filtered.content());
        return polished == null ? "" : polished;
    }

    private String stripGeneratedSourceFooter(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.replaceFirst("(?is)(?:\\n{1,2}|(?<=[.!?]))\\s*Sources:\\s*.+$", "").trim();
    }

    private String stripLeadingDocumentQuote(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        char first = trimmed.charAt(0);
        if (first != '"' && first != '“') {
            return trimmed;
        }

        int closingQuote = findClosingQuote(trimmed, first == '“' ? '”' : '"');
        if (closingQuote <= 0 || closingQuote >= trimmed.length() - 1) {
            return trimmed;
        }

        String remainder = trimmed.substring(closingQuote + 1).trim();
        if (remainder.isBlank()) {
            return trimmed;
        }
        return remainder;
    }

    private int findClosingQuote(String text, char expectedClosingQuote) {
        int expected = text.indexOf(expectedClosingQuote, 1);
        if (expected > 0) {
            return expected;
        }
        return text.indexOf('"', 1);
    }

    private Flux<ServerSentEvent<String>> emitVisibleText(String text) {
        OutputFilter.FilterResult filtered = outputFilter.filter(text);
        if (filtered.blocked()) {
            return Flux.just(event("filtered", (Object) Map.of("reason", filtered.reason())));
        }
        if (filtered.content() == null || filtered.content().isBlank()) {
            return Flux.empty();
        }
        return Flux.just(event("token", (Object) Map.of("t", filtered.content())));
    }

    private String summarizeAxdrTopLevelValue(DecodeResult dr) {
        AxdrValue value = dr.axdrTree();
        if (value == null) {
            return "a raw AXDR value";
        }
        if (value instanceof AxdrNull) {
            return "AXDR null-data with no embedded scalar content";
        }
        if (value instanceof AxdrBoolean bool) {
            return "AXDR boolean " + bool.value();
        }
        if (value instanceof AxdrDateTime dateTime) {
            return "AXDR date-time " + formatDateTime(dateTime);
        }
        if (value instanceof AxdrDate date) {
            return "AXDR date " + formatDate(date);
        }
        if (value instanceof AxdrTime time) {
            return "AXDR time " + formatTime(time);
        }
        if (value instanceof AxdrInt8 n) {
            return "AXDR int8 " + n.value();
        }
        if (value instanceof AxdrInt16 n) {
            return "AXDR int16 " + n.value();
        }
        if (value instanceof AxdrInt32 n) {
            return "AXDR int32 " + n.value();
        }
        if (value instanceof AxdrInt64 n) {
            return "AXDR int64 " + n.value();
        }
        if (value instanceof AxdrUint8 n) {
            return "AXDR uint8 " + n.value();
        }
        if (value instanceof AxdrUint16 n) {
            return "AXDR uint16 " + n.value();
        }
        if (value instanceof AxdrUint32 n) {
            return "AXDR uint32 " + n.value();
        }
        if (value instanceof AxdrUint64 n) {
            return "AXDR uint64 " + n.value();
        }
        if (value instanceof AxdrFloat32 n) {
            return "AXDR float32 " + n.value();
        }
        if (value instanceof AxdrFloat64 n) {
            return "AXDR float64 " + n.value();
        }
        if (value instanceof AxdrEnum n) {
            return "AXDR enum " + n.value();
        }
        if (value instanceof AxdrBitString bits) {
            return "AXDR bit-string " + HexFormat.of().formatHex(bits.value());
        }
        if (value instanceof AxdrOctetString octets) {
            return "AXDR octet-string " + HexFormat.of().formatHex(octets.value());
        }
        if (value instanceof AxdrVisibleString text) {
            return "AXDR visible-string \"" + text.value() + "\"";
        }
        if (value instanceof AxdrUtf8String text) {
            return "AXDR UTF-8 string \"" + text.value() + "\"";
        }
        if (value instanceof AxdrStructure structure) {
            return "an AXDR structure with " + structure.elements().size() + " element" + (structure.elements().size() == 1 ? "" : "s");
        }
        if (value instanceof AxdrArray array) {
            return "an AXDR array with " + array.elements().size() + " element" + (array.elements().size() == 1 ? "" : "s");
        }
        if (value instanceof AxdrCompactArray) {
            return "an AXDR compact-array value";
        }
        return "a raw AXDR value";
    }

    private String groundedControlFrameResponse(@NonNull DecodeResult dr) {
        if (dr.hdlcFrame() == null) {
            return null;
        }
        if (dr.hdlcFrame().frameType() == FrameType.U_FRAME) {
            UFrameType subtype = dr.hdlcFrame().uFrameType() == null ? UFrameType.UNKNOWN : dr.hdlcFrame().uFrameType();
            return switch (subtype) {
                case SNRM -> "What it means: This is an HDLC Set Normal Response Mode (SNRM) control frame used to begin link-layer session establishment.\n"
                        + "Why it matters: SNRM is the standard HDLC link-setup request used to place the link into normal response mode before DLMS/COSEM association traffic starts.\n"
                        + "Next step: Use the structured decode to verify the SAP values, control subtype, and FCS before relating it to the session setup sequence.\n";
                case UA -> "What it means: This is an HDLC Unnumbered Acknowledgement (UA) control frame confirming a link-layer state change request.\n"
                        + "Why it matters: UA normally confirms setup or release negotiations such as SNRM/UA exchange before application traffic continues.\n"
                        + "Next step: Use the structured decode to confirm the addresses, subtype, and FCS before relating it to the prior link-state request.\n";
                case DISC -> "What it means: This is an HDLC Disconnect (DISC) control frame used to request link-layer release.\n"
                        + "Why it matters: It signals that the current HDLC session should be torn down cleanly before any new association is started.\n"
                        + "Next step: Use the structured decode to confirm the control subtype, addressing, and FCS before treating the link as closing.\n";
                case DM -> "What it means: This is an HDLC Disconnected Mode (DM) control frame indicating the peer is not available for normal response mode communication.\n"
                        + "Why it matters: DM often appears when the link is not established or the peer is refusing the requested session state.\n"
                        + "Next step: Verify the session sequence, addressing, and disconnected state in the structured decode before inferring a wider protocol cause.\n";
                default -> "What it means: This is a deterministic HDLC unnumbered control frame.\n"
                        + "Why it matters: Unnumbered frames control link-layer state transitions rather than carrying DLMS APDU payloads.\n"
                        + "Next step: Use the structured decode to inspect the control subtype, addresses, and FCS before interpreting the session state.\n";
            };
        }
        if (dr.hdlcFrame().frameType() == FrameType.S_FRAME) {
            String subtype = dr.hdlcFrame().sFrameType() == null ? "supervisory" : dr.hdlcFrame().sFrameType().name();
            String explanation = switch (subtype) {
                case "RR" -> "It carries receive-ready flow-control meaning at the HDLC layer.";
                case "RNR" -> "It carries temporary receive-pause flow-control meaning at the HDLC layer.";
                case "REJ" -> "It carries retransmission-control meaning after a sequence or delivery problem.";
                default -> "It communicates HDLC flow-control state.";
            };
            return "What it means: This is an HDLC " + subtype + " supervisory control frame.\n"
                    + "Why it matters: " + explanation + "\n"
                    + "Next step: Use the structured decode to verify the subtype, addressing, and FCS before deciding on retransmission or link-state action.\n";
        }
        return null;
    }

    private boolean shouldDowngradeStructuredAgenticDecodeToDeterministicOnly(@NonNull WorkflowState state) {
        return state.orchestrationMode() == com.company.dlms.domain.orchestration.OrchestrationMode.STRUCTURED_PLUS_AGENTIC
                && state.decodeResult() instanceof DecodeResult
                && hasSearchDocsAttempt(state)
                && hasNoSupportingDocumentation(state);
    }

    private boolean hasSearchDocsAttempt(@NonNull WorkflowState state) {
        return state.toolTrace() != null
                && state.toolTrace().stream().anyMatch(entry -> "search_docs".equalsIgnoreCase(entry.toolName()));
    }

    private boolean hasNoSupportingDocumentation(@NonNull WorkflowState state) {
        if (state.retrievalResults() != null && !state.retrievalResults().isEmpty()) {
            return false;
        }
        if (state.toolTrace() == null || state.toolTrace().isEmpty()) {
            return true;
        }
        return state.toolTrace().stream()
                .filter(entry -> "search_docs".equalsIgnoreCase(entry.toolName()))
                .map(entry -> entry.summary() == null ? "" : entry.summary().toLowerCase(Locale.ROOT))
                .anyMatch(summary -> summary.contains("no supporting documentation snippets were recovered"));
    }

    private String firstObisDetail(@NonNull DecodeResult dr) {
        if (dr.obisResolutions() == null || dr.obisResolutions().isEmpty()) {
            return null;
        }
        var firstObis = dr.obisResolutions().getFirst();
        if ("1.0.1.8.0.255".equals(firstObis.obis())) {
            return "OBIS 1.0.1.8.0.255 (Active energy import total)";
        }
        return "OBIS " + firstObis.obis() + " (" + firstObis.description() + ")";
    }

    private String tentativeOuterRoleResponse(@NonNull WorkflowState state, @NonNull DecodeResult dr) {
        if (dr.hdlcFrame() == null) {
            return fallbackResponseText(state, dr);
        }

        StringBuilder sb = new StringBuilder();
        String structuralIssue = firstNonFcsParseError(dr);
        String frameLabel = humanizeTentativeFrameLabel(dr);
        sb.append("What happened: ");
        if (!dr.hdlcFrame().fcsValid()) {
            sb.append("FCS validation failed for this ").append(frameLabel)
                    .append(" frame, so the checksum did not match");
            if (structuralIssue != null) {
                sb.append(", and the parser also reported ")
                        .append(ensureSentence(structuralIssue).toLowerCase(Locale.ROOT));
            } else {
                sb.append(".");
            }
        } else if (structuralIssue != null) {
            sb.append("The outer HDLC header parses as ").append(frameLabel)
                    .append(", but ")
                    .append(ensureSentence(structuralIssue).toLowerCase(Locale.ROOT));
        } else {
            sb.append("The frame can only be trusted at the outer HDLC control level.");
        }
        sb.append("\n");

        String anomalySummary = summarizeAnomalies(state);
        boolean replayRisk = hasReplayCounterAnomaly(state);
        sb.append("Can I trust it: Only the outer HDLC classification is tentatively trustworthy.");
        if (anomalySummary != null) {
            sb.append(" Deterministic anomaly detection also flagged ").append(anomalySummary).append(".");
            if (replayRisk) {
                sb.append(" That anomaly pattern is consistent with a possible replay or counter-regression condition.");
            }
        }
        sb.append(" Do not trust payload, APDU, AXDR, or OBIS interpretation from this capture.\n");

        String outerRole = tentativeOuterRoleMeaning(dr);
        if (outerRole != null) {
            sb.append("Why it still matters: ").append(outerRole).append("\n");
        }

        sb.append("Next step: Re-capture the frame, compare it with adjacent traffic, and confirm whether the source is sending a malformed control frame or the capture path is corrupting bytes.\n");
        return sb.toString();
    }

    private String tentativeOuterRoleMeaning(@NonNull DecodeResult dr) {
        if (dr.hdlcFrame() == null) {
            return null;
        }
        if (dr.hdlcFrame().frameType() == FrameType.U_FRAME) {
            UFrameType subtype = dr.hdlcFrame().uFrameType() == null ? UFrameType.UNKNOWN : dr.hdlcFrame().uFrameType();
            return switch (subtype) {
                case SNRM -> "If the outer subtype is correct, SNRM is the HDLC link-setup request used to begin normal response mode before DLMS association traffic starts.";
                case UA -> "If the outer subtype is correct, UA is the unnumbered acknowledgement frame used to confirm a prior HDLC link-state request.";
                case DISC -> "If the outer subtype is correct, DISC is the HDLC disconnect request used to tear down the link layer.";
                case DM -> "If the outer subtype is correct, DM indicates disconnected mode or refusal of the requested link state.";
                default -> "U-frames are HDLC link-state control frames, but the subtype-specific meaning is only tentative here.";
            };
        }
        if (dr.hdlcFrame().frameType() == FrameType.S_FRAME) {
            String subtype = dr.hdlcFrame().sFrameType() == null ? "UNKNOWN" : dr.hdlcFrame().sFrameType().name();
            return switch (subtype) {
                case "RR" -> "If the outer subtype is correct, RR means Receive Ready and carries receive-ready flow-control meaning at the HDLC layer.";
                case "RNR" -> "If the outer subtype is correct, RNR means Receive Not Ready and carries temporary receive-pause flow-control meaning at the HDLC layer.";
                case "REJ" -> "If the outer subtype is correct, REJ carries retransmission-control meaning after a sequencing or delivery problem.";
                default -> "Supervisory frames manage HDLC flow-control state, but the subtype-specific meaning is only tentative here.";
            };
        }
        return "Only the outer HDLC frame role is trustworthy here; no higher-layer meaning should be inferred from this capture.";
    }

    private String siconiaAlarmImpact(@NonNull com.company.dlms.domain.siconia.AlarmDecodeResult alarm) {
        String base = ensureSentence(alarm.rootCause());
        String rootCause = alarm.rootCause() == null ? "" : alarm.rootCause().toLowerCase(Locale.ROOT);
        String remediation = alarm.remediation() == null ? "" : alarm.remediation().toLowerCase(Locale.ROOT);
        if (rootCause.contains("dcu")
                && (rootCause.contains("comm") || rootCause.contains("communication"))
                && alarm.affectedComponent() == com.company.dlms.domain.siconia.AffectedComponent.HES) {
            return base + " This can interrupt DCU-to-HES communication and delay downstream meter traffic until the link is restored.";
        }
        if (rootCause.contains("comm")
                || rootCause.contains("communication")
                || remediation.contains("link")
                || remediation.contains("connect")) {
            return base + " This can interrupt dependent communication traffic until the affected path is restored.";
        }
        return base;
    }

    private String humanizeTentativeFrameLabel(@NonNull DecodeResult dr) {
        if (dr.hdlcFrame() == null || dr.hdlcFrame().frameType() == null) {
            return "HDLC";
        }
        if (dr.hdlcFrame().frameType() == FrameType.U_FRAME) {
            String subtype = dr.hdlcFrame().uFrameType() == null ? "UNKNOWN" : dr.hdlcFrame().uFrameType().name();
            return "U-frame (" + subtype + ")";
        }
        if (dr.hdlcFrame().frameType() == FrameType.S_FRAME) {
            String subtype = dr.hdlcFrame().sFrameType() == null ? "UNKNOWN" : dr.hdlcFrame().sFrameType().name();
            return "S-frame (" + subtype + ")";
        }
        return dr.hdlcFrame().frameType().name();
    }

    private String describeAxdrTopLevelValue(DecodeResult dr) {
        AxdrValue value = dr.axdrTree();
        if (value == null) {
            return "Only the decoded AXDR tree and recovered identifiers are being summarized.";
        }
        if (value instanceof AxdrNull) {
            return "The top-level AXDR value is null-data with no embedded scalar content.";
        }
        if (value instanceof AxdrBoolean bool) {
            return "The top-level AXDR value is boolean " + bool.value() + ".";
        }
        if (value instanceof AxdrDateTime dateTime) {
            return "The top-level AXDR value is date-time " + formatDateTime(dateTime) + ".";
        }
        if (value instanceof AxdrDate date) {
            return "The top-level AXDR value is date " + formatDate(date) + ".";
        }
        if (value instanceof AxdrTime time) {
            return "The top-level AXDR value is time " + formatTime(time) + ".";
        }
        if (value instanceof AxdrInt8 n) {
            return "The top-level AXDR value is int8 " + n.value() + ".";
        }
        if (value instanceof AxdrInt16 n) {
            return "The top-level AXDR value is int16 " + n.value() + ".";
        }
        if (value instanceof AxdrInt32 n) {
            return "The top-level AXDR value is int32 " + n.value() + ".";
        }
        if (value instanceof AxdrInt64 n) {
            return "The top-level AXDR value is int64 " + n.value() + ".";
        }
        if (value instanceof AxdrUint8 n) {
            return "The top-level AXDR value is uint8 " + n.value() + ".";
        }
        if (value instanceof AxdrUint16 n) {
            return "The top-level AXDR value is uint16 " + n.value() + ".";
        }
        if (value instanceof AxdrUint32 n) {
            return "The top-level AXDR value is uint32 " + n.value() + ".";
        }
        if (value instanceof AxdrUint64 n) {
            return "The top-level AXDR value is uint64 " + n.value() + ".";
        }
        if (value instanceof AxdrFloat32 n) {
            return "The top-level AXDR value is float32 " + n.value() + ".";
        }
        if (value instanceof AxdrFloat64 n) {
            return "The top-level AXDR value is float64 " + n.value() + ".";
        }
        if (value instanceof AxdrVisibleString visible) {
            return "The top-level AXDR value is visible-string \"" + visible.value() + "\".";
        }
        if (value instanceof AxdrUtf8String utf8) {
            return "The top-level AXDR value is utf8-string \"" + utf8.value() + "\".";
        }
        if (value instanceof AxdrOctetString octets) {
            return "The top-level AXDR value is octet-string " + HexFormat.of().withUpperCase().formatHex(octets.value()) + ".";
        }
        if (value instanceof AxdrEnum enumValue) {
            return "The top-level AXDR value is enum " + enumValue.value() + ".";
        }
        if (value instanceof AxdrBitString bits) {
            return "The top-level AXDR value is bit-string " + HexFormat.of().withUpperCase().formatHex(bits.value()) + ".";
        }
        if (value instanceof AxdrStructure structure) {
            return "The top-level AXDR value is a structure with " + structure.elements().size() + " element" + (structure.elements().size() == 1 ? "" : "s") + ".";
        }
        if (value instanceof AxdrArray array) {
            return "The top-level AXDR value is an array with " + array.elements().size() + " element" + (array.elements().size() == 1 ? "" : "s") + ".";
        }
        if (value instanceof AxdrCompactArray compactArray) {
            return "The top-level AXDR value is a compact array with " + compactArray.rawData().length + " raw byte" + (compactArray.rawData().length == 1 ? "" : "s") + ".";
        }
        return "Only the decoded AXDR tree and recovered identifiers are being summarized.";
    }

    private String ensureSentence(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith(".") ? trimmed : trimmed + ".";
    }

    private String firstNonFcsParseError(DecodeResult dr) {
        if (dr.parseErrors() == null || dr.parseErrors().isEmpty()) {
            return null;
        }
        return dr.parseErrors().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.equalsIgnoreCase("FCS invalid"))
                .findFirst()
                .orElse(null);
    }

    private String summarizeAnomalies(@NonNull WorkflowState state) {
        if (state.anomalies() == null || state.anomalies().isEmpty()) {
            return null;
        }
        return state.anomalies().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(2)
                .collect(Collectors.joining(" and "));
    }

    private boolean hasReplayCounterAnomaly(@NonNull WorkflowState state) {
        if (state.anomalies() == null || state.anomalies().isEmpty()) {
            return false;
        }
        return state.anomalies().stream()
                .filter(Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.contains("FC-001") || value.contains("FC-003"));
    }

    /**
     * Serializes a domain object to a Map using Jackson, so it can be embedded
     * in the SSE event JSON payload.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> serializeToRawMap(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("_serializationError", e.getMessage());
        }
    }

    private Map<String, Object> serializeAxdrForUi(AxdrValue value) {
        if (value instanceof AxdrStructure structure) {
            return Map.of(
                    "tag", formatAxdrTag(structure.tag()),
                    "type", "structure",
                    "children", structure.elements().stream()
                            .map(this::serializeAxdrForUi)
                            .toList()
            );
        }
        if (value instanceof AxdrArray array) {
            return Map.of(
                    "tag", formatAxdrTag(array.tag()),
                    "type", "array",
                    "children", array.elements().stream()
                            .map(this::serializeAxdrForUi)
                            .toList()
            );
        }
        if (value instanceof AxdrOctetString octets) {
            return scalarAxdr("octet-string", octets.tag(), HexFormat.of().withUpperCase().formatHex(octets.value()));
        }
        if (value instanceof AxdrVisibleString visible) {
            return scalarAxdr("visible-string", visible.tag(), visible.value());
        }
        if (value instanceof AxdrUtf8String utf8) {
            return scalarAxdr("utf8-string", utf8.tag(), utf8.value());
        }
        if (value instanceof AxdrBoolean bool) {
            return scalarAxdr("boolean", bool.tag(), bool.value());
        }
        if (value instanceof AxdrNull nil) {
            return scalarAxdr("null", nil.tag(), null);
        }
        if (value instanceof AxdrEnum enumValue) {
            return scalarAxdr("enum", enumValue.tag(), enumValue.value());
        }
        if (value instanceof AxdrInt8 n) {
            return scalarAxdr("int8", n.tag(), n.value());
        }
        if (value instanceof AxdrInt16 n) {
            return scalarAxdr("int16", n.tag(), n.value());
        }
        if (value instanceof AxdrInt32 n) {
            return scalarAxdr("int32", n.tag(), n.value());
        }
        if (value instanceof AxdrInt64 n) {
            return scalarAxdr("int64", n.tag(), n.value());
        }
        if (value instanceof AxdrUint8 n) {
            return scalarAxdr("uint8", n.tag(), n.value());
        }
        if (value instanceof AxdrUint16 n) {
            return scalarAxdr("uint16", n.tag(), n.value());
        }
        if (value instanceof AxdrUint32 n) {
            return scalarAxdr("uint32", n.tag(), n.value());
        }
        if (value instanceof AxdrUint64 n) {
            return scalarAxdr("uint64", n.tag(), n.value());
        }
        if (value instanceof AxdrFloat32 n) {
            return scalarAxdr("float32", n.tag(), n.value());
        }
        if (value instanceof AxdrFloat64 n) {
            return scalarAxdr("float64", n.tag(), n.value());
        }
        if (value instanceof AxdrDateTime dateTime) {
            String formatted = String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                    dateTime.year(),
                    Byte.toUnsignedInt(dateTime.month()),
                    Byte.toUnsignedInt(dateTime.dom()),
                    Byte.toUnsignedInt(dateTime.hour()),
                    Byte.toUnsignedInt(dateTime.min()),
                    Byte.toUnsignedInt(dateTime.sec()));
            return scalarAxdr("date-time", dateTime.tag(), formatted);
        }
        if (value instanceof AxdrDate date) {
            String formatted = String.format("%04d-%02d-%02d",
                    date.year(),
                    Byte.toUnsignedInt(date.month()),
                    Byte.toUnsignedInt(date.dom()));
            return scalarAxdr("date", date.tag(), formatted);
        }
        if (value instanceof AxdrTime time) {
            String formatted = String.format("%02d:%02d:%02d",
                    Byte.toUnsignedInt(time.hour()),
                    Byte.toUnsignedInt(time.min()),
                    Byte.toUnsignedInt(time.sec()));
            return scalarAxdr("time", time.tag(), formatted);
        }
        if (value instanceof AxdrBitString bits) {
            return scalarAxdr("bit-string", bits.tag(), HexFormat.of().withUpperCase().formatHex(bits.value()));
        }
        if (value instanceof AxdrCompactArray compactArray) {
            return scalarAxdr("compact-array", compactArray.tag(), HexFormat.of().withUpperCase().formatHex(compactArray.rawData()));
        }
        return scalarAxdr(value.getClass().getSimpleName().toLowerCase(), value.tag(), value.toString());
    }

    private Map<String, Object> scalarAxdr(String type, int tag, Object value) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("tag", formatAxdrTag(tag));
        map.put("type", type);
        map.put("value", value);
        return map;
    }

    private String formatAxdrTag(int tag) {
        return String.format("0x%02X", tag);
    }

    private String formatDateTime(AxdrDateTime dateTime) {
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                dateTime.year(),
                Byte.toUnsignedInt(dateTime.month()),
                Byte.toUnsignedInt(dateTime.dom()),
                Byte.toUnsignedInt(dateTime.hour()),
                Byte.toUnsignedInt(dateTime.min()),
                Byte.toUnsignedInt(dateTime.sec()));
    }

    private String formatDate(AxdrDate date) {
        return String.format("%04d-%02d-%02d",
                date.year(),
                Byte.toUnsignedInt(date.month()),
                Byte.toUnsignedInt(date.dom()));
    }

    private String formatTime(AxdrTime time) {
        return String.format("%02d:%02d:%02d",
                Byte.toUnsignedInt(time.hour()),
                Byte.toUnsignedInt(time.min()),
                Byte.toUnsignedInt(time.sec()));
    }

    private Flux<ServerSentEvent<String>> buildCitationEvents(@NonNull WorkflowState state) {
        AnswerMode mode = state.groundedAnswerContext() == null ? null : state.groundedAnswerContext().mode();
        if (mode == AnswerMode.CASUAL_HELP || mode == AnswerMode.AMBIGUOUS || mode == AnswerMode.SESSION_RECALL) {
            return Flux.empty();
        }
        boolean retrievalEnrichment = state.toolTrace() != null
                && state.toolTrace().stream().anyMatch(entry -> "search_docs".equalsIgnoreCase(entry.toolName()));
        if (mode != AnswerMode.RETRIEVAL_DOCS
                && mode != AnswerMode.RETRIEVAL_SECURITY
                && !retrievalEnrichment) {
            return Flux.empty();
        }

        if (shouldUseSessionRecallResponse(state)) {
            return Flux.empty();
        }

        if (state.retrievalResults() == null || state.retrievalResults().isEmpty()) {
            return Flux.empty();
        }

        List<SourceCitation> rawCitations = state.retrievalResults().stream()
                .limit(5)
                .map(result -> result.chunk() != null ? result.chunk().citation() : null)
                .filter(Objects::nonNull)
                .toList();

        List<SourceCitation> selectedCitations = rawCitations.stream().anyMatch(citation -> !isBoilerplateCitation(citation))
                ? rawCitations.stream().filter(citation -> !isBoilerplateCitation(citation)).toList()
                : rawCitations;

        List<String> citations = selectedCitations.stream()
                .map(SourceCitation::formatted)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(citation -> !citation.isBlank())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), set -> set.stream().limit(3).toList()));

        if (citations.isEmpty()) {
            return Flux.empty();
        }

        String suffix = "\n\nSources: " + String.join("; ", citations);
        return emitVisibleText(suffix);
    }

    private boolean isBoilerplateCitation(SourceCitation citation) {
        if (citation == null) {
            return false;
        }
        return isBoilerplateLabel(citation.sectionTitle()) || isBoilerplateLabel(citation.formatted());
    }

    private boolean isBoilerplateLabel(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toUpperCase(Locale.ROOT)
                .replace("DLMS STANDARD", "")
                .replace("CONFLUENCE", "")
                .replace("—", " ")
                .replace("§", " ")
                .replace("-", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.equals("CONTENTS")
                || normalized.equals("INDEX")
                || normalized.equals("TABLE OF CONTENTS");
    }

    private boolean shouldUseSessionRecallResponse(@NonNull WorkflowState state) {
        if (state.groundedAnswerContext() != null
                && state.groundedAnswerContext().mode() == AnswerMode.SESSION_RECALL) {
            return true;
        }
        if (state.strategyMetadata() != null
                && state.strategyMetadata().selectedStrategy() == StrategyKey.SESSION_RECALL) {
            return true;
        }
        return followUpResolver.isFollowUpQuestion(state.rawInput());
    }

    private String casualReply(String rawInput) {
        String normalized = rawInput == null ? "" : rawInput.trim().toLowerCase();
        if (normalized.contains("thank")) {
            return "You're welcome. I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarms, XML traces, and communication logs.";
        }
        if (normalized.contains("help")) {
            return "I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarms, XML traces, and communication logs.";
        }
        return "Hi. I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarms, XML traces, and communication logs.";
    }

    private String capabilityReply() {
        return "I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarms, XML traces, and communication logs. For example, you can send a raw frame or AXDR/APDU payload to decode, ask protocol or security questions, paste alarm codes such as 0x1342, or share XML traces and log blocks for structured troubleshooting.";
    }

    private String resolveFirstType(@NonNull WorkflowState state, String requestedFirstType) {
        if (requestedFirstType != null && !requestedFirstType.isBlank()) {
            return requestedFirstType;
        }
        StrategyMetadata strategyMetadata = state.strategyMetadata();
        StrategyKey selectedStrategy = strategyMetadata != null ? strategyMetadata.selectedStrategy() : null;
        if (selectedStrategy == StrategyKey.SICONIA_XML_ANALYSIS
                || selectedStrategy == StrategyKey.SICONIA_ALARM_ANALYSIS
                || selectedStrategy == StrategyKey.SICONIA_LOG_ANALYSIS
                || state.siconiaResult() != null) {
            return "analysis";
        }
        if (state.inputClass() == com.company.dlms.domain.InputClass.XML_TRACE
                || state.inputClass() == com.company.dlms.domain.InputClass.ALARM_CODE
                || state.inputClass() == com.company.dlms.domain.InputClass.LOG_BLOCK) {
            return "analysis";
        }
        return "decode";
    }

    private ServerSentEvent<String> event(@NonNull String type, @NonNull Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return ServerSentEvent.builder(Objects.requireNonNull(json, "json")).event(type).build();
        } catch (Exception e) {
            return ServerSentEvent.builder("{}").event(type).build();
        }
    }
}
