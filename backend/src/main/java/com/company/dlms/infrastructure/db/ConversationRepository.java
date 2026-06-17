package com.company.dlms.infrastructure.db;

import com.company.dlms.domain.Conversation;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ConversationRepository extends R2dbcRepository<Conversation, UUID> {
    Flux<Conversation> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Flux<Conversation> findByUserId(UUID userId);
    Mono<Conversation> findByConversationIdAndUserId(UUID conversationId, UUID userId);
}
