package com.company.dlms.agent;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrUint8;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.infrastructure.rag.RetrievalService;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityAgentNodeTest {

    @Mock
    private RetrievalService retrievalService;

    @InjectMocks
    private SecurityAgentNode securityAgentNode;

    @Test
    void process_pureExplanationMode_setsRetrievalResults() {
        when(retrievalService.retrieve(anyString(), eq(IntentType.SECURITY_EXPLAIN), anyInt()))
                .thenReturn(Flux.just(retrievalResult("security-1")));

        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain HLS authentication")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .build();

        WorkflowState out = securityAgentNode.process(state);

        assertThat(out.retrievalResults()).hasSize(1);
        assertThat(out.retrievalResults().get(0).chunk().id()).isEqualTo("security-1");
        assertThat(out.securityContextSummary()).isNull();
        assertThat(out.errors()).isEmpty();
    }

    @Test
    void process_withSecurityFields_populatesSanitizedSecurityContext() {
        when(retrievalService.retrieve(anyString(), eq(IntentType.SECURITY_EXPLAIN), anyInt()))
                .thenReturn(Flux.just(retrievalResult("security-2")));

        WorkflowState state = WorkflowState.empty("s2", "c2", "Explain the security applied here")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .securitySuite("1")
                .frameCounter("12345678")
                .frameCounterHex("00BC614E")
                .decodeResult(decodeResult(ApduType.GLO_GET_REQUEST, null))
                .build();

        WorkflowState out = securityAgentNode.process(state);

        assertThat(out.securityContextSummary()).contains("Suite 1");
        assertThat(out.securityContextSummary()).contains("Frame Counter: present");
        assertThat(out.securityContextSummary()).contains("Ciphered APDU: yes (GLO_GET_REQUEST)");
        assertThat(out.securityContextSummary()).doesNotContain("12345678");
        assertThat(out.securityContextSummary()).doesNotContain("00BC614E");
    }

    @Test
    void process_withAareDiagnostic_includesDiagnosticOnly() {
        when(retrievalService.retrieve(anyString(), eq(IntentType.SECURITY_EXPLAIN), anyInt()))
                .thenReturn(Flux.just(retrievalResult("security-3")));

        WorkflowState state = WorkflowState.empty("s3", "c3", "AARE association rejected, diagnostic 6")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .decodeResult(decodeResult(ApduType.AARE, new AxdrStructure(List.of(new AxdrUint8((short) 6)))))
                .build();

        WorkflowState out = securityAgentNode.process(state);

        assertThat(out.securityContextSummary()).contains("AARE Diagnostic: 6");
        assertThat(out.securityContextSummary()).contains("Frame Counter: absent");
    }

    @Test
    void process_withoutSecurityFields_leavesSummaryEmpty() {
        when(retrievalService.retrieve(anyString(), eq(IntentType.SECURITY_EXPLAIN), anyInt()))
                .thenReturn(Flux.just(retrievalResult("security-4")));

        WorkflowState state = WorkflowState.empty("s4", "c4", "Explain security generally")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .decodeResult(decodeResult(ApduType.GET_REQUEST, null))
                .build();

        WorkflowState out = securityAgentNode.process(state);

        assertThat(out.securityContextSummary()).isNull();
    }

    @Test
    void process_whenRetrievalFails_setsErrorAndDoesNotThrow() {
        when(retrievalService.retrieve(anyString(), eq(IntentType.SECURITY_EXPLAIN), anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        WorkflowState state = WorkflowState.empty("s5", "c5", "Explain HLS authentication")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .build();

        WorkflowState out = securityAgentNode.process(state);

        assertThat(out.errors()).anySatisfy(error -> assertThat(error).contains("Security retrieval failed: boom"));
    }

    private DecodeResult decodeResult(ApduType apduType, com.company.dlms.domain.decoder.AxdrValue axdrValue) {
        return new DecodeResult(
                new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                apduType,
                axdrValue,
                List.of(),
                false,
                "7EA023210313A5E57E",
                List.of()
        );
    }

    private RetrievalResult retrievalResult(String id) {
        return new RetrievalResult(
                new DocumentChunk(id, "Security content",
                        new SourceCitation("dlms", "Blue Book", 1, "Security", "", "", 1.0, "[Blue Book]")),
                0.95,
                0.9,
                0.8
        );
    }
}
