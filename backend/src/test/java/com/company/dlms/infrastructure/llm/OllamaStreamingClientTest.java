package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.reflection.PromptAdaptation;
import com.company.dlms.infrastructure.reflection.AdaptivePromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

class OllamaStreamingClientTest {

    private WireMockServer wireMockServer;
    private OllamaStreamingClient client;

    private static final AdaptivePromptService ADAPTIVE_PROMPT_SERVICE = new AdaptivePromptService(null) {
        @Override
        public Map<String, PromptAdaptation> getAdaptations() {
            return Map.of();
        }
    };
    private static final PromptAssembler PROMPT_ASSEMBLER = new PromptAssembler(ADAPTIVE_PROMPT_SERVICE, new GroundedFactBundleBuilder());

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMockServer.port())
                .build();
        client = new OllamaStreamingClient(webClient, new ObjectMapper(), "lfm2.5-thinking:latest", PROMPT_ASSEMBLER);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void ndjsonWithThreeTokensEmitsThreeStrings() {
        String ndjsonBody = """
                {"message":{"content":"a"},"done":false}
                {"message":{"content":"b"},"done":false}
                {"message":{"content":"c"},"done":true}
                """;

        stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjsonBody)));

        StepVerifier.create(client.stream("prompt"))
                .expectNext("a", "b", "c")
                .verifyComplete();
    }

    @Test
    void doneTrueTerminatesStream() {
        String ndjsonBody = """
                {"message":{"content":"a"},"done":false}
                {"message":{"content":"b"},"done":true}
                {"message":{"content":"ignored"},"done":false}
                """;

        stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjsonBody)));

        StepVerifier.create(client.stream("prompt"))
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    void connectionRefusedCompletesWithErrorEvent() {
        // Use a port with no server listening — same pattern as McpClientTest
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:19999")
                .build();
        OllamaStreamingClient offlineClient = new OllamaStreamingClient(
                webClient, new ObjectMapper(), "lfm2.5-thinking:latest", PROMPT_ASSEMBLER);

        StepVerifier.create(offlineClient.stream("prompt"))
                .expectNextMatches(token -> token.startsWith("[Ollama unavailable:"))
                .verifyComplete();
    }

    @Test
    void timeoutAfterSixtySecondsCompletesGracefully() {
        // Use ExchangeFunction + VirtualTimeScheduler to avoid a 61-second real delay.
        // Mono.never() means the HTTP exchange never produces a response, so
        // after 60 virtual seconds the client's Reactor timeout fires.
        // The onErrorResume in OllamaStreamingClient catches the TimeoutException
        // and emits a descriptive token, then the flux completes.
        VirtualTimeScheduler.getOrSet();
        ExchangeFunction exchange = req -> Mono.never();
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        OllamaStreamingClient timeoutClient = new OllamaStreamingClient(
                webClient, new ObjectMapper(), "lfm2.5-thinking:latest", PROMPT_ASSEMBLER);

        StepVerifier.withVirtualTime(() -> timeoutClient.stream("prompt"))
                .thenAwait(Duration.ofSeconds(61))
                .expectNextMatches(token -> token.startsWith("[Ollama unavailable:"))
                .verifyComplete();
    }
}
