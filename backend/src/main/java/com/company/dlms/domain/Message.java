package com.company.dlms.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Table("messages")
public record Message(
    @Id UUID messageId,
    UUID conversationId,
    String role,
    String inputClass,
    String intent,
    String rawInput,
    Json decodeResultJson,
    Json artifactResultsJson,
    Json strategyMetadataJson,
    String orchestrationMode,
    Boolean plannerUsed,
    Json toolTraceJson,
    String plannerFallbackReason,
    String explanation,
    String sessionId,
    boolean usedMcpFallback,
    String explanationMode,
    String toolProvenance,
    Instant timestamp
) {
    public Message(
            UUID messageId,
            UUID conversationId,
            String role,
            String inputClass,
            String intent,
            String rawInput,
            Json decodeResultJson,
            Json strategyMetadataJson,
            String orchestrationMode,
            Boolean plannerUsed,
            Json toolTraceJson,
            String plannerFallbackReason,
            String explanation,
            String sessionId,
            boolean usedMcpFallback,
            String explanationMode,
            String toolProvenance,
            Instant timestamp
    ) {
        this(
                messageId,
                conversationId,
                role,
                inputClass,
                intent,
                rawInput,
                decodeResultJson,
                null,
                strategyMetadataJson,
                orchestrationMode,
                plannerUsed,
                toolTraceJson,
                plannerFallbackReason,
                explanation,
                sessionId,
                usedMcpFallback,
                explanationMode,
                toolProvenance,
                timestamp
        );
    }

    public static Message create(UUID conversationId, String role, String inputClass, String intent,
                                  String rawInput, String decodeResultJson, String strategyMetadataJson,
                                  String explanation, String sessionId, boolean usedMcpFallback,
                                  String explanationMode, String toolProvenance) {
        return create(
                conversationId,
                role,
                inputClass,
                intent,
                rawInput,
                decodeResultJson,
                null,
                null,
                strategyMetadataJson,
                null,
                null,
                null,
                explanation,
                sessionId,
                usedMcpFallback,
                explanationMode,
                toolProvenance
        );
    }

    public static Message create(UUID conversationId, String role, String inputClass, String intent,
                                  String rawInput, String decodeResultJson, String strategyMetadataJson,
                                  String orchestrationMode, Boolean plannerUsed, String toolTraceJson,
                                  String plannerFallbackReason, String explanation, String sessionId,
                                  boolean usedMcpFallback, String explanationMode, String toolProvenance) {
        return create(
                conversationId,
                role,
                inputClass,
                intent,
                rawInput,
                decodeResultJson,
                null,
                strategyMetadataJson,
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

    public static Message create(UUID conversationId, String role, String inputClass, String intent,
                                  String rawInput, String decodeResultJson, String artifactResultsJson,
                                  String strategyMetadataJson,
                                  String orchestrationMode, Boolean plannerUsed, String toolTraceJson,
                                  String plannerFallbackReason, String explanation, String sessionId,
                                  boolean usedMcpFallback, String explanationMode, String toolProvenance) {
        return new Message(null, conversationId, role, inputClass, intent,
                rawInput, decodeResultJson == null ? null : Json.of(decodeResultJson),
                artifactResultsJson == null ? null : Json.of(artifactResultsJson),
                strategyMetadataJson == null ? null : Json.of(strategyMetadataJson),
                orchestrationMode,
                plannerUsed,
                toolTraceJson == null ? null : Json.of(toolTraceJson),
                plannerFallbackReason,
                explanation, sessionId, usedMcpFallback, explanationMode, toolProvenance, Instant.now());
    }
}
