package com.company.dlms.infrastructure;

import com.company.dlms.api.ConversationDetailResponse;
import com.company.dlms.api.ConversationResponse;
import com.company.dlms.api.MessageResponse;
import com.company.dlms.domain.Conversation;
import com.company.dlms.domain.Message;
import com.company.dlms.infrastructure.db.ConversationRepository;
import com.company.dlms.infrastructure.db.MessageRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Primary
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                                MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    public Mono<ConversationResponse> createConversation(UUID userId, String title) {
        String t = (title != null && !title.isBlank()) ? title : "New Conversation";
        return conversationRepository.save(Conversation.create(userId, t))
                .flatMap(this::toResponse);
    }

    public Flux<ConversationResponse> getConversationsByUser(UUID userId) {
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .flatMap(this::toResponse);
    }

    public Mono<ConversationDetailResponse> getConversationWithMessages(UUID conversationId, UUID userId) {
        return resolveOwnedConversation(conversationId, userId)
                .flatMap(conv ->
                    messageRepository.findByConversationIdOrderByTimestampAsc(conversationId)
                            .map(this::toMessageResponse)
                            .collectList()
                            .map(msgs -> new ConversationDetailResponse(
                                    conv.conversationId(),
                                    conv.title(),
                                    conv.createdAt(),
                                    conv.createdAt(),
                                    msgs.size(),
                                    msgs
                            ))
                );
    }

    public Mono<Message> saveMessage(UUID conversationId, UUID userId, String role, String inputClass,
                                      String intent, String rawInput, String decodeResultJson, String strategyMetadataJson,
                                      String artifactResultsJson,
                                      String orchestrationMode, Boolean plannerUsed, String toolTraceJson,
                                      String plannerFallbackReason, String explanation, String sessionId,
                                      boolean usedMcpFallback, String explanationMode, String toolProvenance) {
        return resolveOwnedConversation(conversationId, userId)
                .flatMap(conv -> messageRepository.save(
                        Message.create(conversationId, role, inputClass, intent,
                                rawInput, decodeResultJson, artifactResultsJson, strategyMetadataJson,
                                orchestrationMode, plannerUsed, toolTraceJson, plannerFallbackReason,
                                explanation, sessionId, usedMcpFallback, explanationMode, toolProvenance)
                ));
    }

    public Mono<Message> saveMessage(UUID conversationId, UUID userId, String role, String inputClass,
                                      String intent, String rawInput, String decodeResultJson, String strategyMetadataJson,
                                      String orchestrationMode, Boolean plannerUsed, String toolTraceJson,
                                      String plannerFallbackReason, String explanation, String sessionId,
                                      boolean usedMcpFallback, String explanationMode, String toolProvenance) {
        return saveMessage(
                conversationId,
                userId,
                role,
                inputClass,
                intent,
                rawInput,
                decodeResultJson,
                strategyMetadataJson,
                null,
                orchestrationMode,
                plannerUsed,
                toolTraceJson,
                plannerFallbackReason,
                explanation,
                sessionId,
                usedMcpFallback,
                explanationMode,
                toolProvenance
        );
    }

    public Mono<Void> deleteConversation(UUID conversationId, UUID userId) {
        return resolveOwnedConversation(conversationId, userId)
                .flatMap(conv -> messageRepository.deleteByConversationId(conversationId)
                        .then(conversationRepository.deleteById(conversationId)));
    }

    public Mono<ConversationResponse> updateTitle(UUID conversationId, UUID userId, String title) {
        return resolveOwnedConversation(conversationId, userId)
                .flatMap(conv -> conversationRepository.save(
                        new Conversation(conv.conversationId(), conv.userId(), title, conv.createdAt())
                ))
                .flatMap(this::toResponse);
    }

    private Mono<ConversationResponse> toResponse(Conversation conv) {
        return messageRepository.countByConversationId(conv.conversationId())
                .map(count -> new ConversationResponse(
                        conv.conversationId(),
                        conv.title(),
                        conv.createdAt(),
                        conv.createdAt(),
                        count
                ));
    }

    private MessageResponse toMessageResponse(Message msg) {
        return new MessageResponse(
                msg.messageId(),
                msg.role(),
                msg.inputClass(),
                msg.intent(),
                msg.rawInput(),
                msg.decodeResultJson() == null ? null : msg.decodeResultJson().asString(),
                msg.artifactResultsJson() == null ? null : msg.artifactResultsJson().asString(),
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
        );
    }

    private Mono<Conversation> resolveOwnedConversation(UUID conversationId, UUID userId) {
        return conversationRepository.findByConversationIdAndUserId(conversationId, userId)
                .switchIfEmpty(conversationRepository.existsById(conversationId)
                        .flatMap(exists -> exists
                                ? Mono.error(new ResponseStatusException(FORBIDDEN, "Access denied"))
                                : Mono.error(new ResponseStatusException(NOT_FOUND, "Conversation not found"))));
    }
}
