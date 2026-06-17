package com.company.dlms.domain;

import java.time.Instant;
import java.util.List;

public record SessionEvent(
        String sessionId,
        Instant timestamp,
        Integer frameNumber,
        String apduType,
        String decodeStage,
        String associationState,
        String obis,
        String ic,
        List<String> errors,
        List<String> warnings,
        List<String> anomalies
) implements java.io.Serializable {}

