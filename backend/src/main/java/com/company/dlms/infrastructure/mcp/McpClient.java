package com.company.dlms.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * MCP client that communicates with the Python MCP tool server via JSON-RPC 2.0.
 * All calls are wrapped in Mono.fromCallable().subscribeOn(Schedulers.boundedElastic())
 * per constitution §I.
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient mcpWebClient;
    private final ObjectMapper objectMapper;

    public McpClient(WebClient mcpWebClient, ObjectMapper objectMapper) {
        this.mcpWebClient = mcpWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Call an MCP tool with the given arguments.
     *
     * @param tool      MCP tool name (e.g. "dlms.parse_hdlc")
     * @param arguments tool arguments as a map
     * @return Mono containing the JSON result from the MCP server
     */
    public Mono<JsonNode> call(String tool, Map<String, Object> arguments) {
        return Mono.fromCallable(() -> {
                    try {
                        // Build JSON-RPC 2.0 request
                        Map<String, Object> requestBody = Map.of(
                                "jsonrpc", "2.0",
                                "method", "tools/call",
                                "params", Map.of("name", tool, "arguments", arguments),
                                "id", UUID.randomUUID().toString()
                        );

                        // POST to /messages
                        String responseJson = mcpWebClient.post()
                                .uri("/messages")
                                .bodyValue(requestBody)
                                .retrieve()
                                .bodyToMono(String.class)
                                .block();

                        JsonNode response = objectMapper.readTree(responseJson);

                        // Check for JSON-RPC error
                        if (response.has("error")) {
                            JsonNode error = response.get("error");
                            String message = error.has("message") ? error.get("message").asText() : "Unknown MCP error";
                            throw new McpException(tool, message);
                        }

                        // Extract result
                        if (response.has("result")) {
                            JsonNode result = response.get("result");
                            // MCP server wraps tool output in result.content[0].text
                            JsonNode content = result.path("content");
                            if (content.isArray() && content.size() > 0) {
                                JsonNode firstContent = content.get(0);
                                JsonNode textNode = firstContent.path("text");
                                if (textNode.isTextual()) {
                                    // text is a JSON string that needs parsing
                                    String text = textNode.asText();
                                    if (text != null && !text.isEmpty()) {
                                        return objectMapper.readTree(text);
                                    }
                                } else if (textNode.isObject() || textNode.isArray()) {
                                    // text is already a JSON object/array — return as-is
                                    return textNode;
                                }
                            }
                            // Fallback: return result as-is
                            return result;
                        }

                        throw new McpException(tool, "No result or error in MCP response");
                    } catch (McpException e) {
                        throw e;
                    } catch (Exception e) {
                        // Check if it's a connection refused or timeout wrapped exception
                        Throwable cause = e.getCause();
                        if (cause instanceof java.net.ConnectException) {
                            throw new McpUnavailableException(tool, cause);
                        }
                        if (cause instanceof java.net.SocketTimeoutException) {
                            throw new McpUnavailableException(tool, cause);
                        }
                        String msg = e.getMessage();
                        if (msg != null && (msg.contains("Connection refused") || msg.contains("connection refused") || msg.contains("Connection timed out"))) {
                            throw new McpUnavailableException(tool, e);
                        }
                        throw new McpException(tool, "MCP call failed: " + e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT);
    }
}
