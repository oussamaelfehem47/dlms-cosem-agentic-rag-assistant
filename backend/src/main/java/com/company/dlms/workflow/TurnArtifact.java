package com.company.dlms.workflow;

import com.company.dlms.domain.InputClass;

import java.io.Serializable;
import java.util.UUID;

public record TurnArtifact(
        String artifactId,
        ArtifactSource source,
        String filename,
        String text,
        InputClass hintedInputClass,
        String suggestedEndpoint
) implements Serializable {

    public TurnArtifact {
        artifactId = artifactId == null || artifactId.isBlank() ? UUID.randomUUID().toString() : artifactId;
        source = source == null ? ArtifactSource.PASTED_BLOCK : source;
        filename = filename == null || filename.isBlank() ? null : filename.trim();
        text = text == null ? "" : text;
        suggestedEndpoint = suggestedEndpoint == null || suggestedEndpoint.isBlank() ? null : suggestedEndpoint.trim();
    }

    public TurnArtifact withText(String nextText) {
        return new TurnArtifact(artifactId, source, filename, nextText, hintedInputClass, suggestedEndpoint);
    }
}
