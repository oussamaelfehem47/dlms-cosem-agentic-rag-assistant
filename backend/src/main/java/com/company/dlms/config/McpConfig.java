package com.company.dlms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class McpConfig {

    @Value("${mcp.server.url:http://mcp-server:8001}")
    private String mcpServerUrl;

    @Bean
    public WebClient mcpWebClient(WebClient.Builder builder) {
        return builder.baseUrl(mcpServerUrl).build();
    }
}
