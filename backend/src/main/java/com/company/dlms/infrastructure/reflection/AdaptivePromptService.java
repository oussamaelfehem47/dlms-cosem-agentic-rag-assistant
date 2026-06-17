package com.company.dlms.infrastructure.reflection;

import com.company.dlms.domain.reflection.PromptAdaptation;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AdaptivePromptService {

    private static final Logger log = LoggerFactory.getLogger(AdaptivePromptService.class);
    private static final double DISLIKE_THRESHOLD = 0.40;
    private static final long MIN_SAMPLES = 10;

    private static final List<String> TRACKED_INTENTS = List.of(
            "FRAME_DECODE", "DOCUMENTATION", "SECURITY_EXPLAIN",
            "SICONIA_TROUBLESHOOT", "OBIS_LOOKUP", "APDU_ANALYSIS", "PROFILE_DECODE"
    );

    private static final Map<String, String> ADAPTATION_INSTRUCTIONS = Map.of(
            "FRAME_DECODE",          "Provide more detail about each decoded field. Show the byte-level breakdown explicitly.",
            "DOCUMENTATION",         "Give a more complete answer with specific references to the standard. Include section numbers where possible.",
            "SECURITY_EXPLAIN",      "Include a practical example alongside the theoretical explanation.",
            "SICONIA_TROUBLESHOOT",  "Provide step-by-step diagnostic procedure, not just root cause identification.",
            "OBIS_LOOKUP",           "Include the Interface Class number, unit, and scaler in the explanation."
    );

    private final MessageFeedbackRepository feedbackRepository;
    private final AtomicReference<Map<String, PromptAdaptation>> currentAdaptations =
            new AtomicReference<>(Map.of());

    public AdaptivePromptService(MessageFeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @PostConstruct
    public void init() {
        computeAdaptations().subscribe(a -> currentAdaptations.set(a));
    }

    public Map<String, PromptAdaptation> getAdaptations() {
        return currentAdaptations.get();
    }

    public reactor.core.publisher.Mono<Map<String, PromptAdaptation>> computeAdaptations() {
        return Flux.fromIterable(TRACKED_INTENTS)
                .flatMap(intent -> {
                    var likesMono  = feedbackRepository.countByIntentAndFeedback(intent, "like").defaultIfEmpty(0L);
                    var dislikesMono = feedbackRepository.countByIntentAndFeedback(intent, "dislike").defaultIfEmpty(0L);
                    return reactor.core.publisher.Mono.zip(likesMono, dislikesMono)
                            .map(tuple -> buildAdaptation(intent, tuple.getT1(), tuple.getT2()));
                })
                .collectMap(PromptAdaptation::intent, adaptation -> adaptation,
                        LinkedHashMap::new);
    }

    @Scheduled(fixedDelay = 300_000)
    public void refreshAdaptations() {
        computeAdaptations().subscribe(a -> currentAdaptations.set(a));
    }

    private PromptAdaptation buildAdaptation(String intent, long likes, long dislikes) {
        long total = likes + dislikes;
        if (total < MIN_SAMPLES) {
            return new PromptAdaptation(intent, "", 0.0, false);
        }
        double dislikeRate = (double) dislikes / total;
        boolean active = dislikeRate > DISLIKE_THRESHOLD && ADAPTATION_INSTRUCTIONS.containsKey(intent);
        String instruction = active ? ADAPTATION_INSTRUCTIONS.get(intent) : "";
        if (active) {
            log.info("Prompt adaptation activated for {}: dislike rate {}%", intent, Math.round(dislikeRate * 100));
        }
        return new PromptAdaptation(intent, instruction, dislikeRate, active);
    }
}
