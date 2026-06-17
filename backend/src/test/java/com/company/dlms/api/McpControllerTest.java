package com.company.dlms.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpControllerTest {

    @Test
    void mcpHealth_successIncludesReachabilityAndToolInventory() {
        ExchangeFunction exchange = request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"status\":\"ok\"}")
                        .build()
        );
        McpController controller = new McpController(WebClient.builder().exchangeFunction(exchange).build());

        StepVerifier.create(controller.mcpHealth())
                .assertNext(response -> {
                    assertThat(response.get("reachable")).isEqualTo(true);
                    assertThat(response.get("toolCount")).isEqualTo(9);
                    assertThat(response.get("tools")).isEqualTo(List.of(
                            "dlms.parse_hdlc",
                            "dlms.decode_axdr",
                            "dlms.resolve_obis",
                            "dlms.assemble_gbt",
                            "siconia.decode_alarm",
                            "siconia.parse_xml",
                            "siconia.classify_log",
                            "confluence.search",
                            "confluence.get_page"
                    ));
                })
                .verifyComplete();
    }

    @Test
    void mcpHealth_failureIncludesErrorAndConfiguredToolInventory() {
        ExchangeFunction exchange = request -> Mono.error(new RuntimeException("MCP proxy unavailable"));
        McpController controller = new McpController(WebClient.builder().exchangeFunction(exchange).build());

        StepVerifier.create(controller.mcpHealth())
                .assertNext(response -> {
                    assertThat(response.get("reachable")).isEqualTo(false);
                    assertThat(response.get("toolCount")).isEqualTo(9);
                    assertThat(((List<?>) response.get("tools"))).hasSize(9);
                    assertThat(response).containsEntry("error", "MCP proxy unavailable");
                })
                .verifyComplete();
    }
}
