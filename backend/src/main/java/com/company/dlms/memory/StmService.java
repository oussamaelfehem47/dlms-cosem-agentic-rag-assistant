package com.company.dlms.memory;

import com.company.dlms.workflow.WorkflowState;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class StmService {

    private final DatabaseClient databaseClient;
    private final DlmsMemoryProperties properties;

    public StmService(DatabaseClient databaseClient, DlmsMemoryProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    public Mono<WorkflowState> loadStm(WorkflowState state) {
        String table = properties.stmTable();
        String sql = """
                SELECT
                  hdlc_client_sap,
                  hdlc_server_sap,
                  frame_counter,
                  frame_counter_hex,
                  security_suite,
                  invoke_id,
                  association_state,
                  max_pdu_size,
                  last_obis,
                  last_ic
                FROM %s
                WHERE session_id = :sessionId
                """.formatted(table);

        return databaseClient.sql(sql)
                .bind("sessionId", state.sessionId())
                .map((row, metadata) -> new StmRow(
                        row.get("hdlc_client_sap", String.class),
                        row.get("hdlc_server_sap", String.class),
                        row.get("frame_counter", Long.class),
                        row.get("frame_counter_hex", String.class),
                        row.get("security_suite", Integer.class),
                        row.get("invoke_id", String.class),
                        row.get("association_state", String.class),
                        row.get("max_pdu_size", Integer.class),
                        row.get("last_obis", String.class),
                        row.get("last_ic", Integer.class)
                ))
                .one()
                .map(db -> merge(state, db))
                .switchIfEmpty(Mono.just(state));
    }

    public WorkflowState loadStmSync(WorkflowState state) {
        return loadStm(state).block();
    }

    public Mono<Void> saveStm(WorkflowState state) {
        String table = properties.stmTable();
        String sql = """
                INSERT INTO %s (
                  session_id,
                  hdlc_client_sap,
                  hdlc_server_sap,
                  frame_counter,
                  frame_counter_hex,
                  security_suite,
                  invoke_id,
                  association_state,
                  max_pdu_size,
                  last_obis,
                  last_ic,
                  updated_at
                )
                VALUES (
                  :sessionId,
                  :hdlcClientSap,
                  :hdlcServerSap,
                  :frameCounter,
                  :frameCounterHex,
                  :securitySuite,
                  :invokeId,
                  :associationState,
                  :maxPduSize,
                  :lastObis,
                  :lastIc,
                  NOW()
                )
                ON CONFLICT (session_id) DO UPDATE SET
                  hdlc_client_sap = COALESCE(EXCLUDED.hdlc_client_sap, %s.hdlc_client_sap),
                  hdlc_server_sap = COALESCE(EXCLUDED.hdlc_server_sap, %s.hdlc_server_sap),
                  frame_counter = COALESCE(EXCLUDED.frame_counter, %s.frame_counter),
                  frame_counter_hex = COALESCE(EXCLUDED.frame_counter_hex, %s.frame_counter_hex),
                  security_suite = COALESCE(EXCLUDED.security_suite, %s.security_suite),
                  invoke_id = COALESCE(EXCLUDED.invoke_id, %s.invoke_id),
                  association_state = COALESCE(EXCLUDED.association_state, %s.association_state),
                  max_pdu_size = COALESCE(EXCLUDED.max_pdu_size, %s.max_pdu_size),
                  last_obis = COALESCE(EXCLUDED.last_obis, %s.last_obis),
                  last_ic = COALESCE(EXCLUDED.last_ic, %s.last_ic),
                  updated_at = NOW()
                """.formatted(table, table, table, table, table, table, table, table, table, table, table, table);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
                .bind("sessionId", state.sessionId());

        spec = bindNullable(spec, "hdlcClientSap", state.hdlcClientSap(), String.class);
        spec = bindNullable(spec, "hdlcServerSap", state.hdlcServerSap(), String.class);
        spec = bindNullable(spec, "frameCounter", parseLongOrNull(state.frameCounter()), Long.class);
        spec = bindNullable(spec, "frameCounterHex", state.frameCounterHex(), String.class);
        spec = bindNullable(spec, "securitySuite", parseIntOrNull(state.securitySuite()), Integer.class);
        spec = bindNullable(spec, "invokeId", state.invokeId(), String.class);
        spec = bindNullable(spec, "associationState", state.associationState(), String.class);
        spec = bindNullable(spec, "maxPduSize", parseIntOrNull(state.maxPduSize()), Integer.class);
        spec = bindNullable(spec, "lastObis", state.lastObis(), String.class);
        spec = bindNullable(spec, "lastIc", parseIntOrNull(state.lastIc()), Integer.class);

        return spec.fetch().rowsUpdated().then();
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

    private static WorkflowState merge(WorkflowState state, StmRow db) {
        WorkflowState.Builder b = state.toBuilder();

        // DB non-null wins over state null; state non-null wins over DB null (no overwrite)
        b.hdlcClientSap(coalesce(db.hdlcClientSap(), state.hdlcClientSap()));
        b.hdlcServerSap(coalesce(db.hdlcServerSap(), state.hdlcServerSap()));
        b.frameCounter(coalesce(Optional.ofNullable(db.frameCounter()).map(String::valueOf).orElse(null), state.frameCounter()));
        b.frameCounterHex(coalesce(db.frameCounterHex(), state.frameCounterHex()));
        b.securitySuite(coalesce(Optional.ofNullable(db.securitySuite()).map(String::valueOf).orElse(null), state.securitySuite()));
        b.invokeId(coalesce(db.invokeId(), state.invokeId()));
        b.associationState(coalesce(db.associationState(), state.associationState()));
        b.maxPduSize(coalesce(Optional.ofNullable(db.maxPduSize()).map(String::valueOf).orElse(null), state.maxPduSize()));
        b.lastObis(coalesce(db.lastObis(), state.lastObis()));
        b.lastIc(coalesce(Optional.ofNullable(db.lastIc()).map(String::valueOf).orElse(null), state.lastIc()));

        return b.build();
    }

    private static String coalesce(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record StmRow(
            String hdlcClientSap,
            String hdlcServerSap,
            Long frameCounter,
            String frameCounterHex,
            Integer securitySuite,
            String invokeId,
            String associationState,
            Integer maxPduSize,
            String lastObis,
            Integer lastIc
    ) {}
}

