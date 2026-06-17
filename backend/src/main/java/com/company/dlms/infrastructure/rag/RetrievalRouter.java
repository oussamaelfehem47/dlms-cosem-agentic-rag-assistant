package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.IntentType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetrievalRouter {

    public List<String> route(IntentType intent, String query) {
        if (intent == null) {
            return List.of("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");
        }
        String normalizedQuery = SearchTextNormalizer.normalize(query);
        return switch (intent) {
            case FRAME_DECODE, OBIS_LOOKUP, APDU_ANALYSIS, PROFILE_DECODE, SECURITY_EXPLAIN ->
                List.of("embeddings_dlms_knowledge");
            case DOCUMENTATION ->
                routeDocumentation(normalizedQuery);
            case SICONIA_TROUBLESHOOT, UNKNOWN ->
                List.of("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");
        };
    }

    private List<String> routeDocumentation(String normalizedQuery) {
        boolean standardsHeavy = RetrievalQuerySignals.containsStandardsSignal(normalizedQuery);
        boolean opsHeavy = RetrievalQuerySignals.containsOpsSignal(normalizedQuery);
        if (standardsHeavy && !opsHeavy) {
            return List.of("embeddings_dlms_knowledge");
        }
        return List.of("embeddings_dlms_knowledge", "embeddings_confluence_knowledge");
    }
}
