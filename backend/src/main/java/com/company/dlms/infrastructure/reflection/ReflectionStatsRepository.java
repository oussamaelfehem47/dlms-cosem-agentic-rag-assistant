package com.company.dlms.infrastructure.reflection;

import com.company.dlms.domain.reflection.ReflectionStat;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ReflectionStatsRepository extends R2dbcRepository<ReflectionStat, UUID> {

    @Modifying
    @Query("""
            INSERT INTO reflection_stats (id, stat_type, stat_key, stat_value, last_updated)
            VALUES (gen_random_uuid(), :statType, :statKey, :increment, NOW())
            ON CONFLICT (stat_type, stat_key)
            DO UPDATE SET stat_value = reflection_stats.stat_value + :increment,
                          last_updated = NOW()
            """)
    Mono<Void> upsertCounter(@Param("statType") String statType,
                             @Param("statKey") String statKey,
                             @Param("increment") long increment);

    @Query("SELECT MAX(last_updated) FROM reflection_stats")
    Mono<Instant> findMaxLastUpdated();

    Flux<ReflectionStat> findAll();
}
