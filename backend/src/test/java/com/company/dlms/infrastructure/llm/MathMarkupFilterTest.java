package com.company.dlms.infrastructure.llm;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class MathMarkupFilterTest {

    private final MathMarkupFilter filter = new MathMarkupFilter();

    @Test
    void singleTokenBoxedMarkupIsUnwrapped() {
        StepVerifier.create(filter.filter(Flux.just("\\boxed{Local operations are procedures run locally.}")))
                .expectNext("Local operations are procedures run locally.")
                .verifyComplete();
    }

    @Test
    void splitBoxedMarkupAcrossTokensIsUnwrapped() {
        StepVerifier.create(filter.filter(Flux.just("\\bo", "xed{Local operations", " stay local.}")))
                .expectNext("Local operations", " stay local.")
                .verifyComplete();
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        StepVerifier.create(filter.filter(Flux.just("Normal plain text output")))
                .expectNext("Normal plain text output")
                .verifyComplete();
    }
}
