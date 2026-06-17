package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.IntentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRouterTest {

    private final RetrievalRouter router = new RetrievalRouter();

    @Test
    void route_returnsCorrectCollectionsForAllIntents() {
        assertThat(router.route(IntentType.FRAME_DECODE, "decode this frame"))
                .containsExactly("embeddings_dlms_knowledge");
        
        assertThat(router.route(IntentType.OBIS_LOOKUP, "What is OBIS 1.0.1.8.0.255?"))
                .containsExactly("embeddings_dlms_knowledge");
        
        assertThat(router.route(IntentType.APDU_ANALYSIS, "parse this APDU"))
                .containsExactly("embeddings_dlms_knowledge");
        
        assertThat(router.route(IntentType.PROFILE_DECODE, "decode this profile"))
                .containsExactly("embeddings_dlms_knowledge");

        assertThat(router.route(IntentType.SECURITY_EXPLAIN, "Explain HLS authentication"))
                .containsExactly("embeddings_dlms_knowledge");
        
        assertThat(router.route(IntentType.SICONIA_TROUBLESHOOT, "What is Local operations in SICONIA?"))
                .containsExactlyInAnyOrder("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");
        
        assertThat(router.route(IntentType.DOCUMENTATION, "What is the DLMS Green Book?"))
                .containsExactly("embeddings_dlms_knowledge");

        assertThat(router.route(IntentType.DOCUMENTATION, "Explain Local operations"))
                .containsExactlyInAnyOrder("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");

        assertThat(router.route(IntentType.DOCUMENTATION, "How does SICONIA use DLMS communication?"))
                .containsExactlyInAnyOrder("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");
        
        assertThat(router.route(IntentType.UNKNOWN, "hello"))
                .containsExactlyInAnyOrder("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");
    }
}
