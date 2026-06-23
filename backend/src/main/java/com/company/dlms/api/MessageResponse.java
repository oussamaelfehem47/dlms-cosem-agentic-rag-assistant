package com.company.dlms.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
    UUID id,
    String role,
    @JsonProperty("input_class") String inputClass,
    String intent,
    @JsonProperty("raw_input") String rawInput,
    @JsonProperty("decode_result") @JsonRawValue String decodeResult,
    @JsonProperty("artifact_results") @JsonRawValue String artifactResults,
    @JsonProperty("strategy_metadata") @JsonRawValue String strategyMetadata,
    @JsonProperty("orchestration_mode") String orchestrationMode,
    @JsonProperty("planner_used") Boolean plannerUsed,
    @JsonProperty("tool_trace") @JsonRawValue String toolTrace,
    @JsonProperty("planner_fallback_reason") String plannerFallbackReason,
    String explanation,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("used_mcp_fallback") boolean usedMcpFallback,
    @JsonProperty("explanation_mode") String explanationMode,
    @JsonProperty("tool_provenance") String toolProvenance,
    @JsonProperty("created_at") Instant createdAt
) {
    public MessageResponse(
            UUID id,
            String role,
            String inputClass,
            String intent,
            String rawInput,
            String decodeResult,
            String strategyMetadata,
            String orchestrationMode,
            Boolean plannerUsed,
            String toolTrace,
            String plannerFallbackReason,
            String explanation,
            String sessionId,
            boolean usedMcpFallback,
            String explanationMode,
            String toolProvenance,
            Instant createdAt
    ) {
        this(
                id,
                role,
                inputClass,
                intent,
                rawInput,
                decodeResult,
                null,
                strategyMetadata,
                orchestrationMode,
                plannerUsed,
                toolTrace,
                plannerFallbackReason,
                explanation,
                sessionId,
                usedMcpFallback,
                explanationMode,
                toolProvenance,
                createdAt
        );
    }
}
