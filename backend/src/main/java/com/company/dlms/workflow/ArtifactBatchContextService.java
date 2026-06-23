package com.company.dlms.workflow;

import com.company.dlms.domain.Message;
import com.company.dlms.infrastructure.db.MessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class ArtifactBatchContextService {

    private static final TypeReference<List<ArtifactResultPayload>> ARTIFACT_RESULTS_TYPE = new TypeReference<>() {};
    private static final String ASSISTANT_ROLE = "assistant";

    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public ArtifactBatchContextService(
            MessageRepository messageRepository,
            ObjectMapper objectMapper
    ) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Mono<WorkflowState> loadRecentArtifactResults(WorkflowState state) {
        Objects.requireNonNull(state, "state");
        UUID conversationId = parseConversationId(state.conversationId());
        if (conversationId == null) {
            return Mono.just(state.withRecentArtifactResults(List.of()));
        }

        Flux<Message> sessionCandidates = hasText(state.sessionId())
                ? safeFlux(messageRepository.findByConversationIdAndSessionIdAndRoleOrderByTimestampDesc(
                        conversationId,
                        state.sessionId(),
                        ASSISTANT_ROLE
                ))
                : Flux.empty();

        Flux<Message> conversationCandidates = safeFlux(messageRepository.findByConversationIdAndRoleOrderByTimestampDesc(
                conversationId,
                ASSISTANT_ROLE
        ));

        return sessionCandidates
                .filter(this::hasArtifactResults)
                .next()
                .switchIfEmpty(conversationCandidates.filter(this::hasArtifactResults).next())
                .map(message -> state.withRecentArtifactResults(parseArtifactResults(message)))
                .switchIfEmpty(Mono.just(state.withRecentArtifactResults(List.of())));
    }

    public WorkflowState loadRecentArtifactResultsSync(WorkflowState state) {
        return loadRecentArtifactResults(state).block();
    }

    private boolean hasArtifactResults(Message message) {
        return message != null
                && message.artifactResultsJson() != null
                && message.artifactResultsJson().asString() != null
                && !message.artifactResultsJson().asString().isBlank();
    }

    private List<ArtifactResultPayload> parseArtifactResults(Message message) {
        if (!hasArtifactResults(message)) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(message.artifactResultsJson().asString(), ARTIFACT_RESULTS_TYPE));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private UUID parseConversationId(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Flux<Message> safeFlux(Flux<Message> value) {
        return value == null ? Flux.empty() : value;
    }
}
