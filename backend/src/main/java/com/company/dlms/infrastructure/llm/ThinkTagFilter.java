package com.company.dlms.infrastructure.llm;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ThinkTagFilter {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private enum State {
        PASSTHROUGH,
        BUFFERING,
        TRANSITION
    }

    public Flux<String> filter(Flux<String> tokens) {
        return Flux.defer(() -> {
            Context context = new Context();
            return tokens.concatMap(token -> Flux.fromIterable(processToken(token, context)))
                    .concatWith(Flux.defer(() -> {
                        if (context.state != State.BUFFERING && !context.openCandidate.isEmpty()) {
                            String remainder = context.openCandidate.toString();
                            context.openCandidate.setLength(0);
                            return Flux.just(remainder);
                        }
                        return Flux.empty();
                    }));
        });
    }

    private java.util.List<String> processToken(String token, Context context) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (token == null || token.isEmpty()) {
            return out;
        }

        StringBuilder emit = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);

            if (context.state == State.BUFFERING) {
                handleBufferingChar(ch, context);
                continue;
            }

            boolean reprocess = true;
            while (reprocess) {
                reprocess = false;
                if (ch == OPEN_TAG.charAt(context.openMatchIndex)) {
                    context.openCandidate.append(ch);
                    context.openMatchIndex++;
                    context.state = State.TRANSITION;
                    if (context.openMatchIndex == OPEN_TAG.length()) {
                        context.state = State.BUFFERING;
                        context.depth = 1;
                        context.openMatchIndex = 0;
                        context.openCandidate.setLength(0);
                    }
                } else {
                    if (context.openMatchIndex > 0) {
                        emit.append(context.openCandidate);
                        context.openCandidate.setLength(0);
                        context.openMatchIndex = 0;
                        context.state = State.PASSTHROUGH;
                        reprocess = true;
                    } else {
                        emit.append(ch);
                    }
                }
            }
        }

        if (!emit.isEmpty()) {
            out.add(emit.toString());
        }
        return out;
    }

    private void handleBufferingChar(char ch, Context context) {
        if (context.tagCandidate.isEmpty()) {
            if (ch == '<') {
                context.tagCandidate.append(ch);
            }
            return;
        }

        context.tagCandidate.append(ch);
        String candidate = context.tagCandidate.toString();
        boolean openPrefix = OPEN_TAG.startsWith(candidate);
        boolean closePrefix = CLOSE_TAG.startsWith(candidate);
        if (openPrefix || closePrefix) {
            if (OPEN_TAG.equals(candidate)) {
                context.depth++;
                context.tagCandidate.setLength(0);
            } else if (CLOSE_TAG.equals(candidate)) {
                context.depth--;
                context.tagCandidate.setLength(0);
                if (context.depth <= 0) {
                    context.depth = 0;
                    context.state = State.PASSTHROUGH;
                }
            }
            return;
        }

        context.tagCandidate.setLength(0);
        if (ch == '<') {
            context.tagCandidate.append('<');
        }
    }

    private static final class Context {
        private State state = State.PASSTHROUGH;
        private int depth = 0;
        private int openMatchIndex = 0;
        private final StringBuilder openCandidate = new StringBuilder();
        private final StringBuilder tagCandidate = new StringBuilder();
    }
}
