package com.company.dlms.infrastructure.mcp;

/**
 * Thrown when the MCP server returns a JSON-RPC error response.
 */
public class McpException extends RuntimeException {

    private final String tool;

    public McpException(String tool, String message) {
        super(message);
        this.tool = tool;
    }

    public String getTool() {
        return tool;
    }
}
