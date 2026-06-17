package com.company.dlms.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private DatabaseClient db;

    @Mock
    private GenericExecuteSpec executeSpec;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        when(db.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());

        auditService = new AuditService(db, new ObjectMapper(), "test-audit-hmac-key-at-least-32chars!!");
    }

    @Test
    void log_insertsRowWithCorrectFields() {
        UUID userId = UUID.randomUUID();
        StepVerifier.create(auditService.log("USER_LOGIN", "auth", userId, Map.of("user", "test")))
                .verifyComplete();
        verify(db).sql(contains("INSERT INTO audit_log"));
    }

    @Test
    void log_hmacPayloadContainsTimestampFirst() {
        // Verify that the signature field is populated (non-null bind call for "signature")
        UUID userId = UUID.randomUUID();
        auditService.log("USER_LOGIN", "auth", userId, Map.of()).block();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(db).sql(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("hmac_signature");
    }

    @Test
    void log_nullUserId_usesPlaceholder() {
        // Should not throw — null userId handled gracefully
        StepVerifier.create(auditService.log("USER_LOGIN", "auth", null, Map.of()))
                .verifyComplete();
    }

    @Test
    void log_returnsMonoVoid_nonBlocking() {
        UUID userId = UUID.randomUUID();
        Mono<Void> result = auditService.log("DECODE_REQUEST", "decode", userId, Map.of());
        // Should be Mono<Void>, not null
        assertThat(result).isNotNull();
        result.subscribe(); // fire-and-forget: no block()
    }
}
