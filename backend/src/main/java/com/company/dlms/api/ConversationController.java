package com.company.dlms.api;

import com.company.dlms.infrastructure.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public Mono<ResponseEntity<ConversationResponse>> createConversation(
            @RequestBody(required = false) Map<String, String> body) {
        String title = body != null ? body.get("title") : null;
        return currentUserId()
                .flatMap(userId -> conversationService.createConversation(userId, title))
                .map(r -> ResponseEntity.status(201).body(r));
    }

    @GetMapping
    public Mono<ResponseEntity<List<ConversationResponse>>> listConversations() {
        return currentUserId()
                .flatMap(userId -> conversationService.getConversationsByUser(userId).collectList())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{conversationId}")
    public Mono<ResponseEntity<ConversationDetailResponse>> getConversation(
            @PathVariable UUID conversationId) {
        return currentUserId()
                .flatMap(userId -> conversationService.getConversationWithMessages(conversationId, userId))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{conversationId}/messages")
    public Mono<ResponseEntity<MessageResponse>> saveMessage(
            @PathVariable UUID conversationId,
            @RequestBody SaveMessageRequest req) {
        return currentUserId()
                .flatMap(userId -> conversationService.saveMessage(
                        conversationId,
                        userId,
                        req.role(),
                        req.inputClass(),
                        req.intent(),
                        req.rawInput(),
                        req.decodeResultJson(),
                        req.strategyMetadataJson(),
                        req.orchestrationMode(),
                        req.plannerUsed(),
                        req.toolTraceJson(),
                        req.plannerFallbackReason(),
                        req.explanation(),
                        req.sessionId(),
                        req.usedMcpFallback() != null && req.usedMcpFallback(),
                        req.explanationMode(),
                        req.toolProvenance()
                ))
                .map(msg -> ResponseEntity.status(201).body(new MessageResponse(
                        msg.messageId(), msg.role(), msg.inputClass(), msg.intent(), msg.rawInput(),
                        msg.decodeResultJson() == null ? null : msg.decodeResultJson().asString(),
                        msg.strategyMetadataJson() == null ? null : msg.strategyMetadataJson().asString(),
                        msg.orchestrationMode(),
                        msg.plannerUsed(),
                        msg.toolTraceJson() == null ? null : msg.toolTraceJson().asString(),
                        msg.plannerFallbackReason(),
                        msg.explanation(),
                        msg.sessionId(),
                        msg.usedMcpFallback(),
                        msg.explanationMode(),
                        msg.toolProvenance(),
                        msg.timestamp()
                )));
    }

    @PatchMapping("/{conversationId}/title")
    public Mono<ResponseEntity<ConversationResponse>> updateTitle(
            @PathVariable UUID conversationId,
            @RequestBody Map<String, String> body) {
        return currentUserId().flatMap(userId ->
                conversationService.updateTitle(conversationId, userId, body.getOrDefault("title", "")))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{conversationId}")
    public Mono<ResponseEntity<Void>> deleteConversation(@PathVariable UUID conversationId) {
        return currentUserId()
                .flatMap(userId -> conversationService.deleteConversation(conversationId, userId))
                .then(Mono.just(ResponseEntity.<Void>noContent().build()));
    }

    private Mono<UUID> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> UUID.fromString((String) ctx.getAuthentication().getPrincipal()));
    }

    public record SaveMessageRequest(
            String role,
            String inputClass,
            String intent,
            String rawInput,
            String decodeResultJson,
            String strategyMetadataJson,
            String orchestrationMode,
            Boolean plannerUsed,
            String toolTraceJson,
            String plannerFallbackReason,
            String explanation,
            String sessionId,
            Boolean usedMcpFallback,
            String explanationMode,
            String toolProvenance
    ) {}
}
