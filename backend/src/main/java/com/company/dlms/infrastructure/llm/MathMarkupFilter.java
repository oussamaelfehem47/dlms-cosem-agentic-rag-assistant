package com.company.dlms.infrastructure.llm;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class MathMarkupFilter {

    private static final String BOXED_OPEN = "\\boxed{";

    private enum State {
        PASSTHROUGH,
        UNWRAPPING
    }

    public Flux<String> filter(Flux<String> tokens) {
        return Flux.defer(() -> {
            Context context = new Context();
            return tokens.concatMap(token -> Flux.fromIterable(processToken(token, context)))
                    .concatWith(Flux.defer(() -> {
                        if (!context.candidate.isEmpty()) {
                            String remainder = context.candidate.toString();
                            context.candidate.setLength(0);
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

            if (context.state == State.UNWRAPPING) {
                if (ch == '{') {
                    context.braceDepth++;
                    emit.append(ch);
                } else if (ch == '}') {
                    if (context.braceDepth == 0) {
                        context.state = State.PASSTHROUGH;
                    } else {
                        context.braceDepth--;
                        emit.append(ch);
                    }
                } else {
                    emit.append(ch);
                }
                continue;
            }

            boolean reprocess = true;
            while (reprocess) {
                reprocess = false;
                if (ch == BOXED_OPEN.charAt(context.matchIndex)) {
                    context.candidate.append(ch);
                    context.matchIndex++;
                    if (context.matchIndex == BOXED_OPEN.length()) {
                        context.state = State.UNWRAPPING;
                        context.matchIndex = 0;
                        context.candidate.setLength(0);
                        context.braceDepth = 0;
                    }
                } else {
                    if (context.matchIndex > 0) {
                        emit.append(context.candidate);
                        context.candidate.setLength(0);
                        context.matchIndex = 0;
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

    private static final class Context {
        private State state = State.PASSTHROUGH;
        private int matchIndex = 0;
        private int braceDepth = 0;
        private final StringBuilder candidate = new StringBuilder();
    }
}
