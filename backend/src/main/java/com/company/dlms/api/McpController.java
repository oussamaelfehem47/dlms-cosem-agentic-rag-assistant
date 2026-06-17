package com.company.dlms.api;

import com.company.dlms.infrastructure.mcp.McpTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final WebClient mcpWebClient;

    public McpController(@Qualifier("mcpWebClient") WebClient mcpWebClient) {
        this.mcpWebClient = mcpWebClient;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> mcpHealth() {
        return mcpWebClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(Map.class)
                .map(result -> buildHealthResponse(true, null))
                .onErrorResume(e -> Mono.just(buildHealthResponse(false, e.getMessage())));
    }

    private Map<String, Object> buildHealthResponse(boolean reachable, String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reachable", reachable);
        response.put("toolCount", McpTools.ALL.size());
        response.put("tools", McpTools.ALL);
        if (error != null && !error.isBlank()) {
            response.put("error", error);
        }
        return response;
    }
}
