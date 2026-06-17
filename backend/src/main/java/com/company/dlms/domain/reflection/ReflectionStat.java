package com.company.dlms.domain.reflection;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("reflection_stats")
public record ReflectionStat(
    @Id UUID id,
    @Column("stat_type") String statType,
    @Column("stat_key") String statKey,
    @Column("stat_value") long statValue,
    @Column("last_updated") Instant lastUpdated
) {}
