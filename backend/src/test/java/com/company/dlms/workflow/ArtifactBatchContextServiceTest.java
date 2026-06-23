package com.company.dlms.workflow;

import com.company.dlms.domain.Message;
import com.company.dlms.infrastructure.db.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArtifactBatchContextServiceTest {

    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ArtifactBatchContextService service = new ArtifactBatchContextService(
            messageRepository,
            new ObjectMapper()
    );

    @Test
    void loadsLatestAssistantArtifactResultsFromSession() {
        UUID conversationId = UUID.randomUUID();
        Message assistantMessage = new Message(
                UUID.randomUUID(),
                conversationId,
                "assistant",
                "QUERY",
                "UNKNOWN",
                "batch",
                null,
                Json.of("""
                        [
                          {
                            "artifactId": "artifact-1",
                            "index": 0,
                            "source": "PASTED_BLOCK",
                            "rawInput": "03 01",
                            "inputClass": "QUERY",
                            "intent": "APDU_ANALYSIS",
                            "explanation": "What it means: The payload decodes as AXDR boolean true."
                          }
                        ]
                        """),
                null,
                "DETERMINISTIC_FAST_PATH",
                false,
                null,
                null,
                null,
                "session-1",
                false,
                null,
                null,
                Instant.now()
        );

        when(messageRepository.findByConversationIdAndSessionIdAndRoleOrderByTimestampDesc(conversationId, "session-1", "assistant"))
                .thenReturn(Flux.just(assistantMessage));

        WorkflowState loaded = service.loadRecentArtifactResultsSync(
                WorkflowState.empty("session-1", conversationId.toString(), "explain artifact 1")
        );

        assertThat(loaded.recentArtifactResults()).hasSize(1);
        assertThat(loaded.recentArtifactResults().getFirst().artifactId()).isEqualTo("artifact-1");
        assertThat(loaded.recentArtifactResults().getFirst().explanation()).contains("AXDR boolean true");
        verify(messageRepository).findByConversationIdAndSessionIdAndRoleOrderByTimestampDesc(conversationId, "session-1", "assistant");
    }

    @Test
    void invalidConversationIdSkipsLookupAndReturnsEmptyArtifactContext() {
        WorkflowState loaded = service.loadRecentArtifactResultsSync(
                WorkflowState.empty("session-1", "not-a-uuid", "explain artifact 2")
        );

        assertThat(loaded.recentArtifactResults()).isEmpty();
        verifyNoInteractions(messageRepository);
    }
}
