package com.company.dlms.infrastructure.llm;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ThinkTagFilterTest {

    private final ThinkTagFilter filter = new ThinkTagFilter();

    @Test
    void cleanTokensPassThroughUnchanged() {
        StepVerifier.create(filter.filter(Flux.just("hello ", "world")))
                .expectNext("hello ", "world")
                .verifyComplete();
    }

    @Test
    void completeThinkTagInOneTokenIsSuppressed() {
        StepVerifier.create(filter.filter(Flux.just("before ", "<think>secret</think>", " after")))
                .expectNext("before ", " after")
                .verifyComplete();
    }

    @Test
    void openingThinkTagSplitAcrossThreeTokensIsSuppressed() {
        StepVerifier.create(filter.filter(Flux.just("before ", "<th", "in", "k>secret</think>", " after")))
                .expectNext("before ", " after")
                .verifyComplete();
    }

    @Test
    void closingThinkTagSplitAcrossTwoTokensClosesCorrectly() {
        StepVerifier.create(filter.filter(Flux.just("before ", "<think>secret</th", "ink>", " after")))
                .expectNext("before ", " after")
                .verifyComplete();
    }

    @Test
    void nestedContentInsideThinkTagsIsSuppressed() {
        StepVerifier.create(filter.filter(Flux.just("<think>a <think>b</think> c</think>", "visible")))
                .expectNext("visible")
                .verifyComplete();
    }

    @Test
    void tokensAfterClosingThinkTagPassThrough() {
        StepVerifier.create(filter.filter(Flux.just("<think>hidden</think>", "token-1", "token-2")))
                .expectNext("token-1", "token-2")
                .verifyComplete();
    }
}
