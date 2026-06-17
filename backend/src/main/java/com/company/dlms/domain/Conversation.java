package com.company.dlms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Table("conversations")
public record Conversation(
    @Id UUID conversationId,
    UUID userId,
    String title,
    Instant createdAt
) {
    public static Conversation create(UUID userId, String title) {
        return new Conversation(null, userId, title, Instant.now());
    }
}