package com.company.dlms.infrastructure.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Central dispatcher for MCP tool calls.
 * NEVER propagates exceptions — all failure modes return McpResult.
 */
@Component
public class McpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

    private final McpClient mcpClient;

    public McpDispatcher(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * Dispatch a tool call to the MCP server.
     * Never throws — all errors are captured in the returned McpResult.
     *
     * @param tool   MCP tool name
     * @param params tool arguments
     * @return Mono containing McpResult (success, failure, or unavailable)
     */
    public Mono<McpResult> dispatch(String tool, Map<String, Object> params) {
        return mcpClient.call(tool, params)
                .map(result -> McpResult.success(tool, result))
                .onErrorResume(McpException.class, e -> {
                    log.warn("MCP tool {} failed: {}", tool, e.getMessage());
                    return Mono.just(McpResult.failure(tool, e.getMessage()));
                })
                .onErrorResume(McpUnavailableException.class, e -> {
                    log.warn("MCP server unavailable for tool {}: {}", tool, e.getMessage());
                    return Mono.just(McpResult.unavailable(tool));
                })
                .onErrorResume(Throwable.class, e -> {
                    log.warn("Unexpected MCP error for tool {}: {}", tool, e.getMessage());
                    return Mono.just(McpResult.failure(tool, e.getMessage()));
                });
    }
}
