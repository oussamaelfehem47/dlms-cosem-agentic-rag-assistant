package com.company.dlms.domain.answer;

import java.util.List;
import java.util.Objects;

public record GroundedFactBundle(
        AnswerTopicFamily family,
        String topicLabel,
        String preferredQuote,
        List<String> authoritativeFacts
) {
    public GroundedFactBundle {
        family = Objects.requireNonNullElse(family, AnswerTopicFamily.NONE);
        topicLabel = topicLabel == null ? "" : topicLabel;
        preferredQuote = preferredQuote == null ? "" : preferredQuote.trim();
        authoritativeFacts = authoritativeFacts == null ? List.of() : List.copyOf(authoritativeFacts);
    }

    public static GroundedFactBundle empty() {
        return new GroundedFactBundle(AnswerTopicFamily.NONE, "", "", List.of());
    }

    public boolean isEmpty() {
        return family == AnswerTopicFamily.NONE && authoritativeFacts.isEmpty() && preferredQuote.isBlank();
    }
}
