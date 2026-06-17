package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.infrastructure.rag.RetrievalService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class ObisResolver {

    private final DatabaseClient databaseClient;
    private final RetrievalService retrievalService;

    public ObisResolver(DatabaseClient databaseClient, RetrievalService retrievalService) {
        this.databaseClient = databaseClient;
        this.retrievalService = retrievalService;
    }

    public Mono<ObisResolution> resolve(String obis, String sessionId) {
        if (obis == null || obis.isBlank()) {
            return Mono.empty();
        }

        return tier1Kg(obis)
                .switchIfEmpty(tier2Structural(obis))
                .switchIfEmpty(tier3Rag(obis));
    }

    private Mono<ObisResolution> tier1Kg(String obis) {
        return databaseClient.sql("""
                        SELECT
                          label AS obis,
                          COALESCE(metadata->>'description','') AS description,
                          (metadata->>'ic')::int AS ic,
                          metadata->>'unit' AS unit,
                          (metadata->>'scaler')::int AS scaler
                        FROM kg_nodes
                        WHERE type='OBIS' AND label=:obis
                        LIMIT 1
                        """)
                .bind("obis", obis)
                .map((row, meta) -> new ObisResolution(
                        row.get("obis", String.class),
                        row.get("description", String.class),
                        row.get("ic", Integer.class),
                        row.get("unit", String.class),
                        row.get("scaler", Integer.class),
                        ResolutionTier.KG
                ))
                .one();
    }

    private Mono<ObisResolution> tier2Structural(String obis) {
        return Mono.fromSupplier(() -> structuralDecode(obis))
                .flatMap(res -> (res == null || res.description() == null || res.description().isBlank()) ? Mono.empty() : Mono.just(res))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ObisResolution> tier3Rag(String obis) {
        return retrievalService.retrieve(obis, IntentType.OBIS_LOOKUP, 3)
                .next()
                .map(r -> new ObisResolution(
                        obis,
                        r.chunk() == null ? "" : r.chunk().content(),
                        null,
                        null,
                        null,
                        ResolutionTier.RAG
                ));
    }

    static ObisResolution structuralDecode(String obis) {
        // Must not throw; structural tier is best-effort.
        try {
            String[] parts = obis.split("\\.");
            if (parts.length != 6) return null;
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            int e = Integer.parseInt(parts[4]);
            int f = Integer.parseInt(parts[5]);

            String medium = switch (a) {
                case 1 -> "Electricity";
                case 6 -> "Heat";
                case 7 -> "Gas";
                case 8 -> "Water";
                default -> "Utility";
            };

            String quantity = switch (c) {
                case 1 -> "Active energy import";
                case 2 -> "Active energy export";
                case 3 -> "Reactive energy import";
                case 4 -> "Reactive energy export";
                case 21 -> "Instantaneous active power import";
                case 22 -> "Instantaneous active power export";
                default -> "Measured quantity C=" + c;
            };

            String aggregation = e == 0 ? "total" : "tariff " + e;
            String interval = d == 8 ? "register" : "time integral " + d;
            String channel = b == 0 ? "all channels" : "channel " + b;
            String instance = f == 255 ? "default instance" : "instance " + f;

            String desc = medium + " - " + quantity + ", " + aggregation
                    + " (" + interval + ", " + channel + ", " + instance + ")";
            return new ObisResolution(obis, desc, null, null, null, ResolutionTier.STRUCTURAL);
        } catch (Exception e) {
            return null;
        }
    }
}

