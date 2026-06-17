package com.company.dlms.service;

import com.company.dlms.infrastructure.db.MessageRepository;
import com.company.dlms.infrastructure.db.ConversationRepository;
import com.company.dlms.domain.Conversation;
import com.company.dlms.domain.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository, MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    public Flux<Conversation> listByUserId(UUID userId) {
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Conversation> create(UUID userId, String title) {
        return conversationRepository.save(Conversation.create(userId, title))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Conversation> getById(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Conversation> updateTitle(UUID conversationId, String title) {
        return conversationRepository.findById(conversationId)
                .flatMap(conv -> conversationRepository.save(
                        new Conversation(conv.conversationId(), conv.userId(), title, conv.createdAt())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> delete(UUID conversationId) {
        return conversationRepository.deleteById(conversationId)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<Message> getMessages(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByTimestampAsc(conversationId)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Message> saveMessage(UUID conversationId, String role, String inputClass, String intent,
                                       String rawInput, String decodeResultJson, String strategyMetadataJson, String explanation,
                                       String sessionId, boolean usedMcpFallback) {
        return messageRepository.save(Message.create(
                conversationId,
                role,
                inputClass,
                intent,
                rawInput,
                decodeResultJson,
                strategyMetadataJson,
                explanation,
                sessionId,
                usedMcpFallback,
                null,
                null
        ))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
