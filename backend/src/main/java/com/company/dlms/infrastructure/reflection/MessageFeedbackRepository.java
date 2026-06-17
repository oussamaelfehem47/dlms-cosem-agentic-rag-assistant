package com.company.dlms.infrastructure.reflection;

import com.company.dlms.domain.reflection.MessageFeedback;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MessageFeedbackRepository extends R2dbcRepository<MessageFeedback, UUID> {

    Mono<Void> deleteByUserId(UUID userId);

    Mono<Void> deleteByConversationId(UUID conversationId);

    Flux<MessageFeedback> findByIntentAndFeedback(String intent, String feedback);

    Mono<Long> countByIntentAndFeedback(String intent, String feedback);

    Mono<Long> countByFeedback(String feedback);

    @Query("SELECT * FROM message_feedback WHERE feedback = 'dislike' ORDER BY created_at DESC LIMIT :limit")
    Flux<MessageFeedback> findRecentDisliked(@Param("limit") int limit);
}
