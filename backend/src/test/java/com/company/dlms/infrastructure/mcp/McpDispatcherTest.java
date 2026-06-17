package com.company.dlms.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class McpDispatcherTest {

    private McpClient mcpClient;
    private McpDispatcher dispatcher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mcpClient = mock(McpClient.class);
        objectMapper = new ObjectMapper();
        dispatcher = new McpDispatcher(mcpClient);
    }

    @Test
    void mcpClientSuccess_returnsMcpResultSuccess() {
        JsonNode resultNode = objectMapper.createObjectNode()
                .put("hcs_valid", true)
                .put("fcs_valid", true);

        when(mcpClient.call(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.just(resultNode));

        StepVerifier.create(dispatcher.dispatch("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(mcpResult -> {
                    assertTrue(mcpResult.success());
                    assertEquals("dlms.parse_hdlc", mcpResult.tool());
                    assertNull(mcpResult.error());
                    assertNotNull(mcpResult.result());
                })
                .verifyComplete();
    }

    @Test
    void mcpClientThrowsMcpException_returnsMcpResultFailure() {
        when(mcpClient.call(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.error(new McpException("dlms.parse_hdlc", "Tool not found")));

        StepVerifier.create(dispatcher.dispatch("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(mcpResult -> {
                    assertFalse(mcpResult.success());
                    assertEquals("dlms.parse_hdlc", mcpResult.tool());
                    assertEquals("Tool not found", mcpResult.error());
                })
                .verifyComplete();
    }

    @Test
    void mcpClientThrowsMcpUnavailableException_returnsMcpResultUnavailable() {
        when(mcpClient.call(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.error(new McpUnavailableException("dlms.parse_hdlc")));

        StepVerifier.create(dispatcher.dispatch("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(mcpResult -> {
                    assertFalse(mcpResult.success());
                    assertEquals("dlms.parse_hdlc", mcpResult.tool());
                    assertEquals("MCP server unavailable", mcpResult.error());
                })
                .verifyComplete();
    }

    @Test
    void mcpClientThrowsUnexpectedException_returnsMcpResultFailure() {
        when(mcpClient.call(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

        StepVerifier.create(dispatcher.dispatch("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(mcpResult -> {
                    assertFalse(mcpResult.success());
                    assertEquals("dlms.parse_hdlc", mcpResult.tool());
                    assertEquals("Unexpected error", mcpResult.error());
                })
                .verifyComplete();
    }

    @Test
    void dispatcherNeverPropagatesException() {
        when(mcpClient.call(eq("dlms.parse_hdlc"), anyMap()))
                .thenReturn(Mono.error(new IllegalStateException("Critical failure")));

        // Verify no error is propagated - always returns McpResult
        StepVerifier.create(dispatcher.dispatch("dlms.parse_hdlc", Map.of("frame_hex", "7EA0")))
                .assertNext(mcpResult -> {
                    assertFalse(mcpResult.success());
                    assertNotNull(mcpResult.error());
                })
                .verifyComplete();
    }
}
