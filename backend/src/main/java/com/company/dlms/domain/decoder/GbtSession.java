package com.company.dlms.domain.decoder;

public record GbtSession(
        String sessionId,
        int blockNumber,
        boolean lastBlock,
        byte[] blockData
) {}

