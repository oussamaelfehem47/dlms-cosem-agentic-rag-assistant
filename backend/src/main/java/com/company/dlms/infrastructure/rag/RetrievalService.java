package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;

/**
 * Public facade for RAG retrieval.
 * Orchestrates routing across collections and merging of hybrid search results.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final String DLMS_COLLECTION = "embeddings_dlms_knowledge";
    private static final String CONFLUENCE_COLLECTION = "embeddings_confluence_knowledge";
    private static final double CONFLUENCE_OPS_BOOST = 1.15;
    private static final double CONFLUENCE_TITLE_MATCH_BOOST = 1.20;
    private final RetrievalRouter router;
    private final HybridRetrievalService hybridRetrievalService;

    public RetrievalService(RetrievalRouter router, HybridRetrievalService hybridRetrievalService) {
        this.router = router;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * Retrieve the top-K most relevant document chunks for a query based on intent.
     *
     * @param query   natural language query string
     * @param intent  detected intent (determines which knowledge collections to search)
     * @param topK    maximum number of results to return
     * @return        Flux of ranked RetrievalResults
     */
    public Flux<RetrievalResult> retrieve(String query, IntentType intent, int topK) {
        if (query == null || query.isBlank()) {
            return Flux.empty();
        }

        List<String> collections = router.route(intent, query);
        String normalizedQuery = SearchTextNormalizer.normalize(query);

        return Flux.fromIterable(collections)
                .flatMap(collection -> hybridRetrievalService.search(query, collection, topK)
                        .map(result -> new RankedHit(
                                collection,
                                applyBalancedBoost(intent, normalizedQuery, collection, result)
                        )))
                .collectList()
                .flatMapMany(hits -> {
                    List<RankedHit> sortedHits = hits.stream()
                            .sorted(Comparator.comparingDouble((RankedHit hit) -> hit.result().combinedScore()).reversed())
                            .toList();
                    logFinalRanking(query, intent, sortedHits);
                    return Flux.fromIterable(sortedHits.stream()
                        .map(RankedHit::result)
                        .distinct()
                        .limit(topK)
                        .toList());
                });
    }

    private RetrievalResult applyBalancedBoost(
            IntentType intent,
            String normalizedQuery,
            String collection,
            RetrievalResult result
    ) {
        if (result == null || !isConfluenceHit(collection, result)) {
            return result;
        }

        double multiplier = 1.0;
        if (intent == IntentType.SICONIA_TROUBLESHOOT) {
            multiplier *= CONFLUENCE_OPS_BOOST;
        } else if (intent == IntentType.DOCUMENTATION && containsOpsSignal(normalizedQuery)) {
            multiplier *= CONFLUENCE_OPS_BOOST;
        }

        String pageTitle = citationOf(result).pageTitle();
        if (isStrongTitleMatch(normalizedQuery, pageTitle)) {
            multiplier *= CONFLUENCE_TITLE_MATCH_BOOST;
        }

        if (multiplier == 1.0) {
            return result;
        }

        return new RetrievalResult(
                result.chunk(),
                result.combinedScore() * multiplier,
                result.vectorScore(),
                result.bm25Score()
        );
    }

    private boolean isConfluenceHit(String collection, RetrievalResult result) {
        if (CONFLUENCE_COLLECTION.equals(collection) || "confluence".equals(collection)) {
            return true;
        }
        return "confluence".equalsIgnoreCase(citationOf(result).docType());
    }

    private boolean containsOpsSignal(String normalizedQuery) {
        return RetrievalQuerySignals.containsOpsSignal(normalizedQuery);
    }

    private boolean isStrongTitleMatch(String normalizedQuery, String pageTitle) {
        String normalizedTitle = SearchTextNormalizer.normalize(pageTitle);
        if (normalizedQuery == null || normalizedQuery.isBlank() || normalizedTitle.isBlank()) {
            return false;
        }
        if (normalizedQuery.equals(normalizedTitle)
                || normalizedQuery.contains(normalizedTitle)
                || normalizedTitle.contains(normalizedQuery)) {
            return true;
        }

        List<String> queryTokens = List.of(normalizedQuery.split(" "));
        List<String> titleTokens = List.of(normalizedTitle.split(" "));
        long matchingTokens = queryTokens.stream()
                .filter(token -> token.length() > 2)
                .filter(token -> titleTokens.stream().anyMatch(titleToken ->
                        titleToken.equals(token)
                                || titleToken.startsWith(token)
                                || token.startsWith(titleToken)))
                .count();
        long required = Math.min(
                queryTokens.stream().filter(token -> token.length() > 2).count(),
                titleTokens.stream().filter(token -> token.length() > 2).count()
        );
        return required >= 2 && matchingTokens >= required;
    }

    private void logFinalRanking(String query, IntentType intent, List<RankedHit> sortedHits) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Merged retrieval ranking query='{}' intent={} results={}", query, intent, sortedHits.size());
        for (int index = 0; index < sortedHits.size(); index++) {
            RankedHit hit = sortedHits.get(index);
            RetrievalResult result = hit.result();
            SourceCitation citation = citationOf(result);
            DocumentChunk chunk = result != null ? result.chunk() : null;
            log.debug(
                    "Merged rank={} collection={} chunkId={} combinedScore={} vectorScore={} bm25Score={} docType={} pageTitle={} spaceName={} formatted={}",
                    index + 1,
                    hit.collection(),
                    chunk != null ? chunk.id() : "null",
                    result != null ? result.combinedScore() : 0.0,
                    result != null ? result.vectorScore() : 0.0,
                    result != null ? result.bm25Score() : 0.0,
                    citation.docType(),
                    citation.pageTitle(),
                    citation.spaceName(),
                    citation.formatted()
            );
        }
    }

    private SourceCitation citationOf(RetrievalResult result) {
        if (result == null || result.chunk() == null || result.chunk().citation() == null) {
            return SourceCitation.fromMetadata(null);
        }
        return result.chunk().citation();
    }

    private record RankedHit(String collection, RetrievalResult result) {
    }
}
