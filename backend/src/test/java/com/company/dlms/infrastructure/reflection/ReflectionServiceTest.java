package com.company.dlms.infrastructure.reflection;

import com.company.dlms.api.admin.FeedbackRequest;
import com.company.dlms.domain.reflection.ReflectionStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReflectionServiceTest {

    @Mock private ReflectionStatsRepository repository;
    @Mock private MessageFeedbackRepository feedbackRepository;
    @Mock private AdaptivePromptService adaptivePromptService;

    private ReflectionService reflectionService;

    @BeforeEach
    void setUp() {
        reflectionService = new ReflectionService(repository, feedbackRepository, adaptivePromptService, "qwen2.5:3b");
        when(adaptivePromptService.getAdaptations()).thenReturn(Map.of());
    }

    // ── getStats() tests ──────────────────────────────────────────────────

    @Test
    void getStats_emptyTable_returnsZeroRates() {
        when(repository.findAll()).thenReturn(Flux.empty());
        when(feedbackRepository.count()).thenReturn(Mono.just(0L));
        when(feedbackRepository.countByFeedback("dislike")).thenReturn(Mono.just(0L));

        StepVerifier.create(reflectionService.getStats())
                .assertNext(response -> {
                    assertThat(response.mcpFailureRate()).isEqualTo(0.0);
                    assertThat(response.filterTriggerRate()).isEqualTo(0.0);
                    assertThat(response.parseErrorRate()).isEqualTo(0.0);
                    assertThat(response.totalExecutions()).isEqualTo(0L);
                    assertThat(response.warnings()).isEmpty();
                    assertThat(response.intentDistribution()).isEmpty();
                    assertThat(response.feedbackDatasetSize()).isEqualTo(0L);
                    assertThat(response.dislikedResponseCount()).isEqualTo(0L);
                })
                .verifyComplete();
    }

    @Test
    void getStats_withSeededRows_returnsCorrectIntentDistribution() {
        List<ReflectionStat> rows = List.of(
                stat("intent_count", "FRAME_DECODE", 5L),
                stat("intent_count", "DOCUMENTATION", 3L),
                stat("retrieval_total", "FRAME_DECODE", 5L),
                stat("filter_total", "total", 8L),
                stat("filter_triggered", "total", 0L),
                stat("mcp_call", "total", 5L),
                stat("mcp_failure", "total", 0L)
        );
        when(repository.findAll()).thenReturn(Flux.fromIterable(rows));
        when(feedbackRepository.count()).thenReturn(Mono.just(10L));
        when(feedbackRepository.countByFeedback("dislike")).thenReturn(Mono.just(3L));

        StepVerifier.create(reflectionService.getStats())
                .assertNext(response -> {
                    assertThat(response.intentDistribution()).containsEntry("FRAME_DECODE", 5L);
                    assertThat(response.intentDistribution()).containsEntry("DOCUMENTATION", 3L);
                    assertThat(response.totalExecutions()).isEqualTo(8L);
                    assertThat(response.mcpFailureRate()).isEqualTo(0.0);
                    assertThat(response.warnings()).isEmpty();
                    assertThat(response.feedbackDatasetSize()).isEqualTo(10L);
                    assertThat(response.dislikedResponseCount()).isEqualTo(3L);
                })
                .verifyComplete();
    }

    @Test
    void getStats_returnsLastUpdatedFromMostRecentRow() {
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-05-10T12:00:00Z");
        List<ReflectionStat> rows = List.of(
                statWithTimestamp("intent_count", "FRAME_DECODE", 1L, older),
                statWithTimestamp("intent_count", "DOCUMENTATION", 1L, newer)
        );
        when(repository.findAll()).thenReturn(Flux.fromIterable(rows));
        when(feedbackRepository.count()).thenReturn(Mono.just(0L));
        when(feedbackRepository.countByFeedback("dislike")).thenReturn(Mono.just(0L));

        StepVerifier.create(reflectionService.getStats())
                .assertNext(response -> assertThat(response.lastUpdated()).isEqualTo(newer))
                .verifyComplete();
    }

    // ── checkWarnings() tests ─────────────────────────────────────────────

    @Test
    void checkWarnings_mcpFailureAboveThreshold_addsWarning() {
        Map<String, Long> counters = new HashMap<>();
        counters.put("mcp_call:total", 100L);
        counters.put("mcp_failure:total", 52L);

        List<String> warnings = reflectionService.checkWarnings(counters, Map.of());

        assertThat(warnings).anyMatch(w -> w.contains("MCP") && w.contains("52%"));
    }

    @Test
    void checkWarnings_emptyRetrievalAboveThreshold_addsWarningWithIntentName() {
        Map<String, Long> counters = new HashMap<>();
        counters.put("retrieval_total:DOCUMENTATION", 10L);

        Map<String, Double> emptyRetrieval = Map.of("DOCUMENTATION", 0.40);

        List<String> warnings = reflectionService.checkWarnings(counters, emptyRetrieval);

        assertThat(warnings).anyMatch(w -> w.contains("DOCUMENTATION") && w.contains("40%"));
    }

    @Test
    void checkWarnings_allRatesBelowThreshold_returnsEmpty() {
        Map<String, Long> counters = new HashMap<>();
        counters.put("mcp_call:total", 100L);
        counters.put("mcp_failure:total", 10L);
        counters.put("filter_total:total", 100L);
        counters.put("filter_triggered:total", 5L);
        counters.put("parse_total:total", 100L);
        counters.put("parse_error:total", 10L);
        counters.put("retrieval_total:FRAME_DECODE", 100L);

        Map<String, Double> emptyRetrieval = Map.of("FRAME_DECODE", 0.05);

        assertThat(reflectionService.checkWarnings(counters, emptyRetrieval)).isEmpty();
    }

    @Test
    void checkWarnings_zeroDenominators_noDivisionByZero() {
        assertThat(reflectionService.checkWarnings(Map.of(), Map.of())).isEmpty();
    }

    // ── recordFeedback() tests ────────────────────────────────────────────

    @Test
    void recordFeedback_savesCounterAndFeedbackRow() {
        when(repository.upsertCounter(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(feedbackRepository.save(any())).thenReturn(Mono.just(
                new com.company.dlms.domain.reflection.MessageFeedback(
                        UUID.randomUUID(), null, null, UUID.randomUUID(),
                        "FRAME_DECODE", "HEX_FRAME", "dislike",
                        null, null, "qwen2.5:3b", Instant.now())
        ));

        FeedbackRequest req = new FeedbackRequest(
                null, null, "dislike", "FRAME_DECODE", "HEX_FRAME", null, "test response", null);
        String userId = UUID.randomUUID().toString();

        StepVerifier.create(reflectionService.recordFeedback(req, userId))
                .verifyComplete();

        verify(repository).upsertCounter("feedback_dislike", "FRAME_DECODE", 1L);
        verify(feedbackRepository).save(any());
    }

    @Test
    void recordFeedback_withInvalidUserId_returnsEmptyMono() {
        FeedbackRequest req = new FeedbackRequest(null, null, "like", "DOCUMENTATION", null, null, null, null);

        StepVerifier.create(reflectionService.recordFeedback(req, "not-a-uuid"))
                .verifyComplete();
    }

    @Test
    void recordFeedback_truncatesSnapshotsTo500Chars() {
        when(repository.upsertCounter(anyString(), anyString(), anyLong())).thenReturn(Mono.empty());

        String longResponse = "x".repeat(1000);
        FeedbackRequest req = new FeedbackRequest(null, null, "like", "FRAME_DECODE", null, null, longResponse, null);
        String userId = UUID.randomUUID().toString();

        when(feedbackRepository.save(any())).thenAnswer(inv -> {
            com.company.dlms.domain.reflection.MessageFeedback saved = inv.getArgument(0);
            assertThat(saved.responseSnapshot()).hasSize(500);
            return Mono.just(saved);
        });

        StepVerifier.create(reflectionService.recordFeedback(req, userId))
                .verifyComplete();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ReflectionStat stat(String type, String key, long value) {
        return new ReflectionStat(UUID.randomUUID(), type, key, value, Instant.now());
    }

    private ReflectionStat statWithTimestamp(String type, String key, long value, Instant ts) {
        return new ReflectionStat(UUID.randomUUID(), type, key, value, ts);
    }
}
