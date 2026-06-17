package com.company.dlms.api.admin;

import com.company.dlms.domain.reflection.MessageFeedback;
import com.company.dlms.domain.reflection.ReflectionStatsResponse;
import com.company.dlms.infrastructure.reflection.ReflectionService;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/reflection")
public class ReflectionController {

    private final ReflectionService reflectionService;

    public ReflectionController(ReflectionService reflectionService) {
        this.reflectionService = reflectionService;
    }

    @GetMapping("/stats")
    public Mono<ReflectionStatsResponse> getStats() {
        return reflectionService.getStats();
    }

    @PostMapping("/feedback")
    public Mono<Void> submitFeedback(@RequestBody FeedbackRequest request) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .flatMap(userId -> reflectionService.recordFeedback(request, userId));
    }

    @GetMapping("/feedback/disliked")
    public Flux<MessageFeedback> getDislikedFeedback(
            @RequestParam(defaultValue = "20") int limit) {
        return reflectionService.getRecentDisliked(limit);
    }
}
