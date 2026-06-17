package com.company.dlms.infrastructure.mcp;

/**
 * Thrown when the MCP server is unreachable (connection refused, timeout, etc.).
 */
public class McpUnavailableException extends RuntimeException {

    private final String tool;

    public McpUnavailableException(String tool) {
        super("MCP server unavailable for tool: " + tool);
        this.tool = tool;
    }

    public McpUnavailableException(String tool, Throwable cause) {
        super("MCP server unavailable for tool: " + tool, cause);
        this.tool = tool;
    }

    public String getTool() {
        return tool;
    }
}
