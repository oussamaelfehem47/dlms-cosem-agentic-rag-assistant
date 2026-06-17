package com.company.dlms.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;
    private final String hmacSecret;

    public AuditService(DatabaseClient db, ObjectMapper objectMapper, 
                        @Value("${dlms.security.audit.secret:audit-secret-key-1234567890}") String hmacSecret) {
        this.db = db;
        this.objectMapper = objectMapper;
        this.hmacSecret = hmacSecret;
    }

    public Mono<Void> log(String action, String entity, UUID userId, Map<String, Object> details) {
        return Mono.defer(() -> {
            try {
                String timestamp = java.time.Instant.now().toString();
                String detailsJson = objectMapper.writeValueAsString(details);
                String userIdStr = userId != null ? userId.toString() : "null";
                // Spec FR-008: HMAC payload = timestamp|action|entity|userId
                String dataToSign = timestamp + "|" + action + "|" + (entity != null ? entity : "GENERAL") + "|" + userIdStr;
                String signature = calculateHmac(dataToSign);

                return db.sql("INSERT INTO audit_log (action, entity, user_id, details_json, hmac_signature) VALUES (:action, :entity, :userId, CAST(:details AS JSONB), :signature)")
                        .bind("action", action)
                        .bind("entity", entity != null ? entity : "GENERAL")
                        .bind("userId", userId != null ? userId : UUID.nameUUIDFromBytes("null".getBytes()))
                        .bind("details", detailsJson)
                        .bind("signature", signature)
                        .then();
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }

    private String calculateHmac(String data) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        return Base64.getEncoder().encodeToString(sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
