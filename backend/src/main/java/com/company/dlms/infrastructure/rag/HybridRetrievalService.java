package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level hybrid search implementation for a single collection.
 * Combines semantic (Vector) and keyword (BM25) results.
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);
    private final PgVectorStore dlmsVectorStore;
    private final PgVectorStore confluenceVectorStore;
    private final BM25Service bm25Service;
    private final double vectorWeight;
    private final double bm25Weight;

    public HybridRetrievalService(
            @Qualifier("dlmsKnowledgeVectorStore") PgVectorStore dlmsVectorStore,
            @Qualifier("confluenceKnowledgeVectorStore") PgVectorStore confluenceVectorStore,
            BM25Service bm25Service,
            @Value("${rag.retrieval.vector-weight:0.7}") double vectorWeight,
            @Value("${rag.retrieval.bm25-weight:0.3}") double bm25Weight) {
        this.dlmsVectorStore = dlmsVectorStore;
        this.confluenceVectorStore = confluenceVectorStore;
        this.bm25Service = bm25Service;
        this.vectorWeight = vectorWeight;
        this.bm25Weight = bm25Weight;
    }

    /**
     * Performs hybrid search (Vector + BM25) for a specific collection.
     * 
     * @param query          The search query
     * @param collectionName The table/collection name
     * @param topK           Limit of results
     * @return Flux of RetrievalResult
     */
    public Flux<RetrievalResult> search(String query, String collectionName, int topK) {
        VectorStore vectorStore = getVectorStore(collectionName);

        // Vector search wrapped in boundedElastic (blocking JDBC call)
        Mono<List<RetrievalResult>> vectorResultsMono = Mono.fromCallable(() ->
                vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build())
        )
        .subscribeOn(Schedulers.boundedElastic())
        .map(docs -> docs.stream()
                .map(doc -> {
                    double vScore = calculateVectorScore(doc);
                    return new RetrievalResult(toDocumentChunk(doc, collectionName), 0.0, vScore, 0.0);
                })
                .toList())
        .onErrorReturn(List.of());

        // BM25 search (already wrapped in boundedElastic inside BM25Service)
        Mono<List<BM25Result>> bm25ResultsMono = bm25Service.search(query, collectionName, topK)
                .onErrorReturn(List.of());

        return Mono.zip(vectorResultsMono, bm25ResultsMono)
                .flatMapIterable(tuple -> mergeAndRank(collectionName, tuple.getT1(), tuple.getT2(), topK));
    }

    private List<RetrievalResult> mergeAndRank(
            String collectionName,
            List<RetrievalResult> vectorResults,
            List<BM25Result> bm25Results,
            int topK
    ) {
        Map<String, RetrievalResult> seen = new HashMap<>();
        Map<String, String> contentKeys = new HashMap<>();

        // Process vector results
        for (RetrievalResult vRes : vectorResults) {
            registerResult(seen, contentKeys, vRes);
        }

        // Process BM25 results and combine scores
        for (BM25Result bRes : bm25Results) {
            String key = findExistingKey(seen, contentKeys, bRes.chunk());
            if (key != null) {
                RetrievalResult existing = seen.get(key);
                seen.put(key, new RetrievalResult(
                        preferRicherChunk(existing.chunk(), bRes.chunk()),
                        0.0, // calculated below
                        existing.vectorScore(),
                        bRes.score()
                ));
            } else {
                registerResult(seen, contentKeys, new RetrievalResult(bRes.chunk(), 0.0, 0.0, bRes.score()));
            }
        }

        // Calculate combined scores and sort descending
        List<RetrievalResult> ranked = seen.values().stream()
                .map(res -> {
                    double combined = (res.vectorScore() * vectorWeight) + (res.bm25Score() * bm25Weight);
                    double spaceWeight = (res.chunk() != null && res.chunk().citation() != null)
                            ? res.chunk().citation().spaceWeight()
                            : 1.0;
                    if (spaceWeight <= 0.0) {
                        spaceWeight = 1.0;
                    }
                    return new RetrievalResult(
                            res.chunk(),
                            combined * spaceWeight,
                            res.vectorScore(),
                            res.bm25Score()
                    );
                })
                .sorted((a, b) -> Double.compare(b.combinedScore(), a.combinedScore()))
                .limit(topK)
                .toList();
        logHybridRanking(collectionName, ranked);
        return ranked;
    }

    private VectorStore getVectorStore(String collectionName) {
        if ("embeddings_confluence_knowledge".equals(collectionName)) {
            return confluenceVectorStore;
        }
        return dlmsVectorStore;
    }

    private double calculateVectorScore(Document doc) {
        Object dist = doc.getMetadata().get("distance");
        if (dist instanceof Number num) {
            // Distance 0.0 = Similarity 1.0
            return Math.max(0.0, 1.0 - num.doubleValue());
        }
        return 0.0;
    }

    private DocumentChunk toDocumentChunk(Document doc, String collectionName) {
        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
        applyMetadataDefaults(metadata, collectionName);
        return new DocumentChunk(
                doc.getId(),
                doc.getText(),
                SourceCitation.fromMetadata(metadata)
        );
    }

    private DocumentChunk preferRicherChunk(DocumentChunk primary, DocumentChunk fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        SourceCitation primaryCitation = primary.citation();
        SourceCitation fallbackCitation = fallback.citation();
        if (citationRichness(fallbackCitation) > citationRichness(primaryCitation)) {
            return fallback;
        }
        return primary;
    }

    private int citationRichness(SourceCitation citation) {
        if (citation == null) {
            return 0;
        }
        int score = 0;
        if (hasText(citation.docType())) score++;
        if (hasText(citation.sourceFile())) score++;
        if (hasText(citation.sectionTitle())) score++;
        if (hasText(citation.pageTitle())) score++;
        if (hasText(citation.spaceName())) score++;
        if (hasText(citation.formatted())) score++;
        return score;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void registerResult(
            Map<String, RetrievalResult> seen,
            Map<String, String> contentKeys,
            RetrievalResult result
    ) {
        String key = canonicalKey(result.chunk());
        seen.put(key, result);
        String contentKey = contentKey(result.chunk());
        if (contentKey != null) {
            contentKeys.putIfAbsent(contentKey, key);
        }
    }

    private String findExistingKey(
            Map<String, RetrievalResult> seen,
            Map<String, String> contentKeys,
            DocumentChunk chunk
    ) {
        if (chunk == null) {
            return null;
        }

        String id = chunk.id();
        if (hasText(id) && seen.containsKey(id)) {
            return id;
        }

        String contentKey = contentKey(chunk);
        return contentKey != null ? contentKeys.get(contentKey) : null;
    }

    private String canonicalKey(DocumentChunk chunk) {
        if (chunk != null && hasText(chunk.id())) {
            return chunk.id();
        }
        String contentKey = contentKey(chunk);
        return contentKey != null ? "content:" + contentKey : "anonymous";
    }

    private String contentKey(DocumentChunk chunk) {
        if (chunk == null || !hasText(chunk.content())) {
            return null;
        }
        return chunk.content().replaceAll("\\s+", " ").trim();
    }

    private void applyMetadataDefaults(Map<String, Object> metadata, String collectionName) {
        copyIfMissing(metadata, "doc_type", confluenceCollection(collectionName) ? "confluence" : "dlms");
        copyAliasIfMissing(metadata, "source_file", metadata.get("sourceFile"));
        copyAliasIfMissing(metadata, "page_title", metadata.get("pageTitle"));
        copyAliasIfMissing(metadata, "space_name", metadata.get("spaceName"));
        copyAliasIfMissing(metadata, "section_title", metadata.get("sectionTitle"));
        if (!metadata.containsKey("space_weight") && metadata.containsKey("spaceWeight")) {
            metadata.put("space_weight", metadata.get("spaceWeight"));
        }
    }

    private void copyIfMissing(Map<String, Object> metadata, String key, String fallback) {
        if (!hasText(metadata.get(key)) && hasText(fallback)) {
            metadata.put(key, fallback);
        }
    }

    private void copyAliasIfMissing(Map<String, Object> metadata, String key, Object aliasValue) {
        if (!hasText(metadata.get(key)) && hasText(aliasValue)) {
            metadata.put(key, aliasValue.toString());
        }
    }

    private boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private boolean confluenceCollection(String collectionName) {
        return "embeddings_confluence_knowledge".equals(collectionName) || "confluence".equals(collectionName);
    }

    private void logHybridRanking(String collectionName, List<RetrievalResult> rankedResults) {
        if (!log.isDebugEnabled()) {
            return;
        }

        for (int index = 0; index < rankedResults.size(); index++) {
            RetrievalResult result = rankedResults.get(index);
            DocumentChunk chunk = result != null ? result.chunk() : null;
            SourceCitation citation = chunk != null ? chunk.citation() : null;
            log.debug(
                    "Hybrid rank={} collection={} chunkId={} vectorScore={} bm25Score={} combinedScore={} docType={} pageTitle={} spaceName={} formatted={}",
                    index + 1,
                    collectionName,
                    chunk != null ? chunk.id() : "null",
                    result != null ? result.vectorScore() : 0.0,
                    result != null ? result.bm25Score() : 0.0,
                    result != null ? result.combinedScore() : 0.0,
                    citation != null ? citation.docType() : "",
                    citation != null ? citation.pageTitle() : "",
                    citation != null ? citation.spaceName() : "",
                    citation != null ? citation.formatted() : ""
            );
        }
    }
}
