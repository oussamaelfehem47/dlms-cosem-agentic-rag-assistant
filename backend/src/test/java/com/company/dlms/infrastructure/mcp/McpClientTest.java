package com.company.dlms.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private WireMockServer wireMockServer;
    private McpClient mcpClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();
        mcpClient = new McpClient(webClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void validSuccessResponse_returnsJsonNode() {
        String toolResultJson = """
                {"status": "ok", "hcs_valid": true, "fcs_valid": true}
                """;
        String jsonResponse = """
                {
                    "jsonrpc": "2.0",
                    "id": "test-id",
                    "result": {
                        "content": [{"type": "text", "text": %s}]
                    }
                }
                """.formatted(toolResultJson);

        stubFor(post(urlEqualTo("/messages"))
                .withRequestBody(containing("\"method\":\"tools/call\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonResponse)));

        StepVerifier.create(mcpClient.call("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(result -> {
                    assertEquals("ok", result.get("status").asText());
                    assertTrue(result.get("hcs_valid").asBoolean());
                })
                .verifyComplete();
    }

    @Test
    void jsonResponseError_returnsMcpException() {
        String jsonError = """
                {
                    "jsonrpc": "2.0",
                    "id": "test-id",
                    "error": {"code": -32601, "message": "Tool not found: dlms.fake_tool"}
                }
                """;

        stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonError)));

        StepVerifier.create(mcpClient.call("dlms.fake_tool", Map.of()))
                .expectError(McpException.class)
                .verify();
    }

    @Test
    void connectionRefused_returnsMcpUnavailableException() {
        // Use a port with no server listening
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:19999")
                .build();
        McpClient client = new McpClient(webClient, new ObjectMapper());

        StepVerifier.create(client.call("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .expectError(McpUnavailableException.class)
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void timeoutAfter10Seconds_returnsError() {
        stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(15000)
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"content\":[]}}")));

        StepVerifier.create(mcpClient.call("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void requestBodyContainsCorrectJsonRpcFormat() {
        String toolResultJson = "{\"ok\": true}";
        String jsonResponse = """
                {
                    "jsonrpc": "2.0",
                    "id": "test-id",
                    "result": {
                        "content": [{"type": "text", "text": %s}]
                    }
                }
                """.formatted(toolResultJson);

        stubFor(post(urlEqualTo("/messages"))
                .withRequestBody(containing("\"jsonrpc\":\"2.0\""))
                .withRequestBody(containing("\"method\":\"tools/call\""))
                .withRequestBody(containing("\"name\":\"dlms.parse_hdlc\""))
                .withRequestBody(containing("\"arguments\":{\"frame_hex\":\"7EA0\"}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonResponse)));

        StepVerifier.create(mcpClient.call("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(result -> assertTrue(result.get("ok").asBoolean()))
                .verifyComplete();
    }
}
