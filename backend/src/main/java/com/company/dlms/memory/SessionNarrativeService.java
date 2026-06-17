package com.company.dlms.memory;

import com.company.dlms.domain.SessionEvent;
import com.company.dlms.workflow.WorkflowState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Component
public class SessionNarrativeService {

    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

    private final DatabaseClient databaseClient;
    private final DlmsMemoryProperties properties;
    private final ObjectMapper objectMapper;

    public SessionNarrativeService(DatabaseClient databaseClient, DlmsMemoryProperties properties, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Mono<WorkflowState> loadNarrative(WorkflowState state) {
        String table = properties.narrativeTable();
        String sql = """
                SELECT
                  session_id,
                  timestamp,
                  frame_number,
                  apdu_type,
                  decode_stage,
                  association_state,
                  obis,
                  ic,
                  errors,
                  warnings,
                  anomalies
                FROM %s
                WHERE session_id = :sessionId
                ORDER BY timestamp DESC
                LIMIT %d
                """.formatted(table, properties.narrativeLimit());

        return databaseClient.sql(sql)
                .bind("sessionId", state.sessionId())
                .map((row, meta) -> new SessionEvent(
                        row.get("session_id", String.class),
                        asInstant(row.get("timestamp")),
                        row.get("frame_number", Integer.class),
                        row.get("apdu_type", String.class),
                        row.get("decode_stage", String.class),
                        row.get("association_state", String.class),
                        row.get("obis", String.class),
                        asString(row.get("ic")),
                        readStringList(row.get("errors", String.class)),
                        readStringList(row.get("warnings", String.class)),
                        readStringList(row.get("anomalies", String.class))
                ))
                .all()
                .collectList()
                .map(list -> {
                    Collections.reverse(list); // chronological order for prompt injection
                    return state.toBuilder().narrativeContext(list).build();
                })
                .switchIfEmpty(Mono.just(state.toBuilder().narrativeContext(List.of()).build()));
    }

    public WorkflowState loadNarrativeSync(WorkflowState state) {
        return loadNarrative(state).block();
    }

    public Mono<Void> appendEvent(SessionEvent event) {
        String table = properties.narrativeTable();
        String sql = """
                INSERT INTO %s (
                  session_id,
                  frame_number,
                  apdu_type,
                  decode_stage,
                  association_state,
                  obis,
                  ic,
                  errors,
                  warnings,
                  anomalies,
                  timestamp
                )
                VALUES (
                  :sessionId,
                  :frameNumber,
                  :apduType,
                  :decodeStage,
                  :associationState,
                  :obis,
                  :ic,
                  CAST(:errors AS JSONB),
                  CAST(:warnings AS JSONB),
                  CAST(:anomalies AS JSONB),
                  :timestamp
                )
                """.formatted(table);

        var spec = databaseClient.sql(sql)
                .bind("sessionId", event.sessionId())
                .bind("frameNumber", event.frameNumber())
                .bind("apduType", event.apduType())
                .bind("decodeStage", event.decodeStage());

        spec = bindNullable(spec, "associationState", event.associationState(), String.class);
        spec = bindNullable(spec, "obis", event.obis(), String.class);
        spec = bindNullable(spec, "ic", parseIntOrNull(event.ic()), Integer.class);
        spec = bindNullable(spec, "errors", writeStringList(event.errors()), String.class);
        spec = bindNullable(spec, "warnings", writeStringList(event.warnings()), String.class);
        spec = bindNullable(spec, "anomalies", writeStringList(event.anomalies()), String.class);
        spec = spec.bind("timestamp", event.timestamp() == null ? Instant.now() : event.timestamp());

        return spec.fetch()
                .rowsUpdated()
                .then();
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec,
            String name,
            T value,
            Class<T> type
    ) {
        if (value == null) {
            return spec.bindNull(name, type);
        }
        return spec.bind(name, value);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, LIST_OF_STRING);
        } catch (JsonProcessingException e) {
            return List.of("NARRATIVE_JSON_PARSE_ERROR");
        }
    }

    private String writeStringList(List<String> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static Instant asInstant(Object value) {
        if (value == null) return Instant.now();
        if (value instanceof Instant i) return i;
        if (value instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.now();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
