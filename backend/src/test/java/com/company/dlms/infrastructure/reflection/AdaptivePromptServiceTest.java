package com.company.dlms.infrastructure.reflection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptivePromptServiceTest {

    @Mock
    private MessageFeedbackRepository feedbackRepository;

    @InjectMocks
    private AdaptivePromptService service;

    @Test
    void computeAdaptations_highDislikeRate_activatesAdaptationForFrameDecode() {
        when(feedbackRepository.countByIntentAndFeedback(anyString(), anyString())).thenReturn(Mono.just(0L));
        when(feedbackRepository.countByIntentAndFeedback("FRAME_DECODE", "like")).thenReturn(Mono.just(4L));
        when(feedbackRepository.countByIntentAndFeedback("FRAME_DECODE", "dislike")).thenReturn(Mono.just(10L));

        StepVerifier.create(service.computeAdaptations())
                .assertNext(map -> {
                    var adaptation = map.get("FRAME_DECODE");
                    assertThat(adaptation).isNotNull();
                    assertThat(adaptation.active()).isTrue();
                    assertThat(adaptation.triggerRate()).isGreaterThan(0.40);
                    assertThat(adaptation.additionalInstruction()).contains("byte-level breakdown");
                })
                .verifyComplete();
    }

    @Test
    void computeAdaptations_lowDislikeRate_noAdaptation() {
        when(feedbackRepository.countByIntentAndFeedback(anyString(), anyString())).thenReturn(Mono.just(0L));
        when(feedbackRepository.countByIntentAndFeedback("FRAME_DECODE", "like")).thenReturn(Mono.just(9L));
        when(feedbackRepository.countByIntentAndFeedback("FRAME_DECODE", "dislike")).thenReturn(Mono.just(1L));

        StepVerifier.create(service.computeAdaptations())
                .assertNext(map -> {
                    var adaptation = map.get("FRAME_DECODE");
                    assertThat(adaptation).isNotNull();
                    assertThat(adaptation.active()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void computeAdaptations_insufficientSamples_noAdaptation() {
        // Only 5 total samples — below MIN_SAMPLES of 10
        when(feedbackRepository.countByIntentAndFeedback(anyString(), anyString())).thenReturn(Mono.just(0L));
        when(feedbackRepository.countByIntentAndFeedback("FRAME_DECODE", "like")).thenReturn(Mono.just(1L));
        when(feedbackRepository.countByIntentAndFeedback("FRAME_DECODE", "dislike")).thenReturn(Mono.just(4L));

        StepVerifier.create(service.computeAdaptations())
                .assertNext(map -> {
                    var adaptation = map.get("FRAME_DECODE");
                    assertThat(adaptation.active()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void getAdaptations_returnsEmptyMapInitially() {
        assertThat(service.getAdaptations()).isEmpty();
    }
}
