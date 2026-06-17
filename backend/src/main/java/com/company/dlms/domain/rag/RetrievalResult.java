package com.company.dlms.domain.rag;

import java.io.Serializable;

public record RetrievalResult(
        DocumentChunk chunk,
        double        combinedScore,
        double        vectorScore,
        double        bm25Score
) implements Serializable {}
