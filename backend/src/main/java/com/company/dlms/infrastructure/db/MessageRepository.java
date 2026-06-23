package com.company.dlms.infrastructure.db;

import com.company.dlms.domain.Message;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MessageRepository extends R2dbcRepository<Message, UUID> {
    Flux<Message> findByConversationIdOrderByTimestampAsc(UUID conversationId);
    Flux<Message> findByConversationIdAndSessionIdAndRoleOrderByTimestampDesc(UUID conversationId, String sessionId, String role);
    Flux<Message> findByConversationIdAndRoleOrderByTimestampDesc(UUID conversationId, String role);
    Mono<Long> countByConversationId(UUID conversationId);
    Mono<Void> deleteByConversationId(UUID conversationId);
}
