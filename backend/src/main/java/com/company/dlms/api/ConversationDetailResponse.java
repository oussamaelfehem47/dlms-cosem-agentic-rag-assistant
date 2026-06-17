package com.company.dlms.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDetailResponse(
    UUID id,
    String title,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("message_count") long messageCount,
    List<MessageResponse> messages
) {}
