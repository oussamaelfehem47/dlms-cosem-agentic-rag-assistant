package com.company.dlms.domain.reflection;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("message_feedback")
public record MessageFeedback(
    @Id UUID id,
    @Column("message_id") UUID messageId,
    @Column("conversation_id") UUID conversationId,
    @Column("user_id") UUID userId,
    String intent,
    @Column("input_class") String inputClass,
    String feedback,
    @Column("prompt_snapshot") String promptSnapshot,
    @Column("response_snapshot") String responseSnapshot,
    @Column("model_name") String modelName,
    @Column("created_at") Instant createdAt
) {}
