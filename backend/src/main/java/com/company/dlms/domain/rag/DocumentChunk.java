package com.company.dlms.domain.rag;

import java.io.Serializable;

public record DocumentChunk(
        String         id,
        String         content,
        SourceCitation citation
) implements Serializable {}
