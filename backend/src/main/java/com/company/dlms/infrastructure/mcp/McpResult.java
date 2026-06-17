package com.company.dlms.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable result of an MCP tool dispatch attempt.
 * Never throws — all error modes are captured as data.
 */
public record McpResult(
        String tool,
        JsonNode result,
        boolean success,
        String error
) {

    public static McpResult success(String tool, JsonNode result) {
        return new McpResult(tool, result, true, null);
    }

    public static McpResult failure(String tool, String error) {
        return new McpResult(tool, null, false, error);
    }

    public static McpResult unavailable(String tool) {
        return new McpResult(tool, null, false, "MCP server unavailable");
    }
}
