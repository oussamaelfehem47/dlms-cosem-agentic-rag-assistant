package com.company.dlms.infrastructure.reflection;

import com.company.dlms.api.admin.FeedbackRequest;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.reflection.MessageFeedback;
import com.company.dlms.domain.reflection.PromptAdaptation;
import com.company.dlms.domain.reflection.ReflectionStat;
import com.company.dlms.domain.reflection.ReflectionStatsResponse;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    private static final Set<DlmsIntent> MCP_APPLICABLE = Set.of(
            DlmsIntent.FRAME_DECODE,
            DlmsIntent.OBIS_LOOKUP,
            DlmsIntent.APDU_ANALYSIS,
            DlmsIntent.SICONIA_TROUBLESHOOT,
            DlmsIntent.PROFILE_DECODE,
            DlmsIntent.SECURITY_EXPLAIN
    );

    private final ReflectionStatsRepository repository;
    private final MessageFeedbackRepository feedbackRepository;
    private final AdaptivePromptService adaptivePromptService;
    private final String modelName;

    public ReflectionService(
            ReflectionStatsRepository repository,
            MessageFeedbackRepository feedbackRepository,
            AdaptivePromptService adaptivePromptService,
            @Value("${spring.ai.ollama.chat.model:qwen2.5:3b}") String modelName) {
        this.repository = repository;
        this.feedbackRepository = feedbackRepository;
        this.adaptivePromptService = adaptivePromptService;
        this.modelName = modelName;
    }

    public Mono<Void> recordExecution(WorkflowState state) {
        if (state == null) return Mono.empty();

        List<Mono<Void>> upserts = new ArrayList<>();

        DlmsIntent intent = state.intent();
        String intentKey = intent != null ? intent.name() : "UNKNOWN";

        upserts.add(repository.upsertCounter("intent_count", intentKey, 1L));

        if (intent != null && MCP_APPLICABLE.contains(intent)) {
            upserts.add(repository.upsertCounter("mcp_call", "total", 1L));
            if (!state.mcpUsed()) {
                upserts.add(repository.upsertCounter("mcp_failure", "total", 1L));
            }
        }

        upserts.add(repository.upsertCounter("retrieval_total", intentKey, 1L));
        boolean emptyRetrieval = state.retrievalResults() == null || state.retrievalResults().isEmpty();
        if (emptyRetrieval) {
            upserts.add(repository.upsertCounter("retrieval_empty", intentKey, 1L));
        }

        upserts.add(repository.upsertCounter("filter_total", "total", 1L));
        if (state.outputFiltered()) {
            upserts.add(repository.upsertCounter("filter_triggered", "total", 1L));
        }

        if (state.inputClass() == InputClass.HEX_FRAME) {
            upserts.add(repository.upsertCounter("parse_total", "total", 1L));
            if (state.errors() != null && !state.errors().isEmpty()) {
                upserts.add(repository.upsertCounter("parse_error", "total", 1L));
            }
        }

        if (state.startTimeMs() > 0L) {
            long elapsed = System.currentTimeMillis() - state.startTimeMs();
            upserts.add(repository.upsertCounter("response_time_sum", intentKey, elapsed));
            upserts.add(repository.upsertCounter("response_time_count", intentKey, 1L));
        }

        if (state.siconiaResult() != null && state.siconiaResult().processingMetadata() != null) {
            ParseProvenance provenance = state.siconiaResult().processingMetadata().provenance();
            if (provenance != null) {
                upserts.add(repository.upsertCounter("siconia_provenance", provenance.name(), 1L));
            }
            if (state.siconiaResult().xmlTrace() != null
                    && (state.siconiaResult().xmlTrace().events() == null || state.siconiaResult().xmlTrace().events().isEmpty())) {
                upserts.add(repository.upsertCounter("siconia_xml_degraded", "total", 1L));
            }
        }

        return Mono.when(upserts);
    }

    public Mono<Void> recordFeedback(FeedbackRequest req, String userId) {
        if (req == null || req.feedback() == null || req.intent() == null) return Mono.empty();

        UUID userUuid;
        try {
            userUuid = UUID.fromString(userId);
        } catch (Exception e) {
            log.warn("recordFeedback: invalid userId '{}', skipping", userId);
            return Mono.empty();
        }

        String intent = req.intent();
        String feedbackType = req.feedback();

        Mono<Void> counterUpsert = repository.upsertCounter("feedback_" + feedbackType, intent, 1L);

        String truncatedPrompt = req.promptSnapshot() != null
                ? req.promptSnapshot().substring(0, Math.min(500, req.promptSnapshot().length()))
                : null;
        String truncatedResponse = req.responseSnapshot() != null
                ? req.responseSnapshot().substring(0, Math.min(500, req.responseSnapshot().length()))
                : null;
        String resolvedModel = req.modelName() != null ? req.modelName() : modelName;

        MessageFeedback entry = new MessageFeedback(
                null,
                req.messageId(),
                req.conversationId(),
                userUuid,
                intent,
                req.inputClass(),
                feedbackType,
                truncatedPrompt,
                truncatedResponse,
                resolvedModel,
                Instant.now()
        );

        Mono<Void> feedbackInsert = feedbackRepository.save(entry).then();

        return Mono.when(counterUpsert, feedbackInsert);
    }

    public Mono<ReflectionStatsResponse> getStats() {
        Mono<Long> feedbackTotalMono   = feedbackRepository.count();
        Mono<Long> dislikedCountMono   = feedbackRepository.countByFeedback("dislike").defaultIfEmpty(0L);

        return Mono.zip(
                repository.findAll().collectList(),
                feedbackTotalMono,
                dislikedCountMono
        ).map(tuple -> {
            List<ReflectionStat> rows         = tuple.getT1();
            long feedbackDatasetSize          = tuple.getT2();
            long dislikedResponseCount        = tuple.getT3();

            Map<String, Long> counters = rows.stream()
                    .collect(Collectors.toMap(
                            r -> r.statType() + ":" + r.statKey(),
                            ReflectionStat::statValue,
                            Long::sum
                    ));

            Map<String, Long> intentDistribution = rows.stream()
                    .filter(r -> "intent_count".equals(r.statType()))
                    .collect(Collectors.toMap(ReflectionStat::statKey, ReflectionStat::statValue));

            long totalExecutions = intentDistribution.values().stream().mapToLong(Long::longValue).sum();

            long mcpCall    = counters.getOrDefault("mcp_call:total", 0L);
            long mcpFailure = counters.getOrDefault("mcp_failure:total", 0L);
            double mcpFailureRate = mcpCall > 0 ? (double) mcpFailure / mcpCall : 0.0;

            Map<String, Double> emptyRetrievalRate = new LinkedHashMap<>();
            rows.stream()
                    .filter(r -> "retrieval_total".equals(r.statType()))
                    .forEach(r -> {
                        long total = r.statValue();
                        long empty = counters.getOrDefault("retrieval_empty:" + r.statKey(), 0L);
                        emptyRetrievalRate.put(r.statKey(), total > 0 ? (double) empty / total : 0.0);
                    });

            long filterTotal     = counters.getOrDefault("filter_total:total", 0L);
            long filterTriggered = counters.getOrDefault("filter_triggered:total", 0L);
            double filterTriggerRate = filterTotal > 0 ? (double) filterTriggered / filterTotal : 0.0;

            long parseTotal = counters.getOrDefault("parse_total:total", 0L);
            long parseError = counters.getOrDefault("parse_error:total", 0L);
            double parseErrorRate = parseTotal > 0 ? (double) parseError / parseTotal : 0.0;

            Map<String, Long> avgResponseTimeMs = new LinkedHashMap<>();
            rows.stream()
                    .filter(r -> "response_time_count".equals(r.statType()))
                    .forEach(r -> {
                        long count = r.statValue();
                        long sum   = counters.getOrDefault("response_time_sum:" + r.statKey(), 0L);
                        avgResponseTimeMs.put(r.statKey(), count > 0 ? sum / count : 0L);
                    });

            Instant lastUpdated = rows.stream()
                    .map(ReflectionStat::lastUpdated)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

            List<String> warnings = checkWarnings(counters, emptyRetrievalRate);

            Map<String, PromptAdaptation> activeAdaptations = adaptivePromptService.getAdaptations();

            return new ReflectionStatsResponse(
                    mcpFailureRate,
                    emptyRetrievalRate,
                    filterTriggerRate,
                    parseErrorRate,
                    intentDistribution,
                    avgResponseTimeMs,
                    totalExecutions,
                    warnings,
                    lastUpdated,
                    activeAdaptations,
                    feedbackDatasetSize,
                    dislikedResponseCount
            );
        });
    }

    public Flux<MessageFeedback> getRecentDisliked(int limit) {
        return feedbackRepository.findRecentDisliked(limit);
    }

    List<String> checkWarnings(Map<String, Long> counters, Map<String, Double> emptyRetrievalRateByIntent) {
        List<String> warnings = new ArrayList<>();

        long mcpCall    = counters.getOrDefault("mcp_call:total", 0L);
        long mcpFailure = counters.getOrDefault("mcp_failure:total", 0L);
        if (mcpCall > 0 && (double) mcpFailure / mcpCall > 0.50) {
            int pct = (int) Math.round((double) mcpFailure / mcpCall * 100);
            warnings.add("MCP server reliability degraded (failure rate: " + pct + "%)");
        }

        emptyRetrievalRateByIntent.forEach((intent, rate) -> {
            long total = counters.getOrDefault("retrieval_total:" + intent, 0L);
            if (total > 0 && rate > 0.30) {
                int pct = (int) Math.round(rate * 100);
                warnings.add("Knowledge gaps detected for " + intent + " (empty retrieval rate: " + pct + "%)");
            }
        });

        long filterTotal     = counters.getOrDefault("filter_total:total", 0L);
        long filterTriggered = counters.getOrDefault("filter_triggered:total", 0L);
        if (filterTotal > 0 && (double) filterTriggered / filterTotal > 0.20) {
            int pct = (int) Math.round((double) filterTriggered / filterTotal * 100);
            warnings.add("Output filter may be overly aggressive (trigger rate: " + pct + "%)");
        }

        long parseTotal = counters.getOrDefault("parse_total:total", 0L);
        long parseError = counters.getOrDefault("parse_error:total", 0L);
        if (parseTotal > 0 && (double) parseError / parseTotal > 0.40) {
            int pct = (int) Math.round((double) parseError / parseTotal * 100);
            warnings.add("High rate of malformed frame submissions (parse error rate: " + pct + "%)");
        }

        return Collections.unmodifiableList(warnings);
    }
}
