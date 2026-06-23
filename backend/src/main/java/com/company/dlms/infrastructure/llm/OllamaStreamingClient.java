package com.company.dlms.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class OllamaStreamingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaStreamingClient.class);

    @NonNull private final WebClient webClient;
    @NonNull private final ObjectMapper objectMapper;
    @NonNull private final String model;
    @NonNull private final PromptAssembler promptAssembler;

    @Autowired
    public OllamaStreamingClient(
            @NonNull ObjectMapper objectMapper,
            @NonNull @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @NonNull @Value("${spring.ai.ollama.chat.model:qwen2.5:3b}") String model,
            @NonNull PromptAssembler promptAssembler
    ) {
        this(WebClient.builder().baseUrl(baseUrl).build(), objectMapper, model, promptAssembler);
    }

    OllamaStreamingClient(@NonNull WebClient webClient, @NonNull ObjectMapper objectMapper, @NonNull String model, @NonNull PromptAssembler promptAssembler) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = Objects.requireNonNull(model, "model");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler");
    }

    public Flux<String> stream(@NonNull String prompt) {
        return stream(promptAssembler.systemPrompt(), prompt);
    }

    public Flux<String> stream(@NonNull String systemPrompt, @NonNull String prompt) {
        // Send system instructions as a separate "system" role message,
        // and the assembled context + user question as a "user" role message.
        Map<String, Object> payload = Map.of(
                "model", model,
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", prompt)
                )
        );

        log.info("Sending prompt to Ollama:\nSystem:\n{}\nUser:\n{}", systemPrompt, prompt);

        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMapIterable(chunk -> chunk.lines().toList())
                .<String>handle((line, sink) -> {
                    try {
                        JsonNode json = objectMapper.readTree(line);
                        JsonNode contentNode = json.path("message").path("content");
                        if (!contentNode.isMissingNode() && !contentNode.asText().isEmpty()) {
                            sink.next(contentNode.asText());
                        }
                        if (json.path("done").asBoolean(false)) {
                            sink.complete();
                        }
                    } catch (Exception ignored) {
                        // Ignore malformed NDJSON lines and continue streaming.
                    }
                })
                .timeout(Duration.ofSeconds(60))
                .onErrorResume(ex -> {
                    log.warn("Ollama streaming error: {}", ex.getMessage());
                    return Flux.just("[Ollama unavailable: " + ex.getMessage() + "]");
                });
    }
}
