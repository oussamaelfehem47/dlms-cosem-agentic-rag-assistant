package com.company.dlms.api.admin;

import java.util.UUID;

public record FeedbackRequest(
    UUID messageId,
    UUID conversationId,
    String feedback,
    String intent,
    String inputClass,
    String promptSnapshot,
    String responseSnapshot,
    String modelName
) {}
