package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;

public record BM25Result(
        DocumentChunk chunk,
        double score
) {}
