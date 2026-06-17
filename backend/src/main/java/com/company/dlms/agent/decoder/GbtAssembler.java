package com.company.dlms.agent.decoder;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class GbtAssembler {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final DatabaseClient databaseClient;

    public GbtAssembler(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Optional<byte[]>> onBlock(String sessionId, int blockNumber, boolean lastBlock, byte[] blockData) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.error(new IllegalArgumentException("sessionId is required"));
        }
        if (blockNumber <= 0) {
            return Mono.error(new IllegalArgumentException("blockNumber must be >= 1"));
        }
        if (blockData == null) {
            blockData = new byte[0];
        }

        String blockHex = HEX.formatHex(blockData);

        Mono<Long> insert = databaseClient.sql("""
                        INSERT INTO episodic_blocks(session_id, frame_number, apdu_type, decode_stage, errors)
                        VALUES (:session_id, :frame_number, 'GBT', 'GBT_PARTIAL', CAST(:errors AS JSONB))
                        """)
                .bind("session_id", sessionId)
                .bind("frame_number", blockNumber)
                .bind("errors", "{\"block_hex\":\"" + blockHex + "\"}")
                .fetch()
                .rowsUpdated();

        if (!lastBlock) {
            return insert.thenReturn(Optional.empty());
        }

        Mono<byte[]> assembled = databaseClient.sql("""
                        SELECT errors->>'block_hex' AS block_hex
                        FROM episodic_blocks
                        WHERE session_id=:session_id AND decode_stage='GBT_PARTIAL'
                        ORDER BY frame_number ASC
                        """)
                .bind("session_id", sessionId)
                .map((row, meta) -> row.get("block_hex", String.class))
                .all()
                .collectList()
                .flatMap(list -> {
                    int totalBytes = list.stream()
                            .filter(s -> s != null)
                            .mapToInt(s -> s.length() / 2)
                            .sum();
                    byte[] out = new byte[totalBytes];
                    int o = 0;
                    for (String hex : list) {
                        if (hex == null) continue;
                        byte[] part = HEX.parseHex(hex);
                        System.arraycopy(part, 0, out, o, part.length);
                        o += part.length;
                    }
                    return Mono.just(out);
                });

        Mono<Long> delete = databaseClient.sql("""
                        DELETE FROM episodic_blocks
                        WHERE session_id=:session_id AND decode_stage='GBT_PARTIAL'
                        """)
                .bind("session_id", sessionId)
                .fetch()
                .rowsUpdated();

        return insert.then(assembled).flatMap(bytes -> delete.thenReturn(Optional.of(bytes)));
    }

    @Scheduled(fixedDelay = 60_000)
    public Mono<Long> cleanupStale() {
        return databaseClient.sql("""
                        DELETE FROM episodic_blocks
                        WHERE decode_stage='GBT_PARTIAL'
                          AND timestamp < NOW() - INTERVAL '5 minutes'
                        """)
                .fetch()
                .rowsUpdated();
    }
}

