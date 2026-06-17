package com.company.dlms.infrastructure.rag;

import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.SourceCitation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory BM25 retrieval service using Apache Lucene.
 * ALL Lucene operations are wrapped in Schedulers.boundedElastic() as it is a blocking library.
 */
@Service
public class BM25Service {

    private static final Logger log = LoggerFactory.getLogger(BM25Service.class);
    private static final String CONTENT_FIELD = "content";
    private static final String COLLECTION_FIELD = "collection_name";
    private static final String TITLE_SEARCH_FIELD = "page_title_search";
    private static final String CONFLUENCE_COLLECTION = "embeddings_confluence_knowledge";
    private static final float CONFLUENCE_TITLE_BOOST = 2.5f;

    private final Directory directory = new ByteBuffersDirectory();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final R2dbcEntityTemplate entityTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BM25Service(R2dbcEntityTemplate entityTemplate) {
        this.entityTemplate = entityTemplate;
    }

    /**
     * Builds Lucene index from chunks.
     */
    public void indexDocuments(List<DocumentChunk> chunks) {
        indexDocuments(chunks, null);
    }

    /**
     * Builds Lucene index from chunks for a specific collection.
     */
    public void indexDocuments(List<DocumentChunk> chunks, String collectionName) {
        if (chunks == null || chunks.isEmpty()) return;
        
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (DocumentChunk chunk : chunks) {
                    Document doc = new Document();
                    doc.add(new TextField(CONTENT_FIELD, chunk.content(), Field.Store.YES));
                    doc.add(new StoredField("id", chunk.id()));
                    String resolvedCollectionName = resolveCollectionName(chunk, collectionName);
                    doc.add(new StringField(COLLECTION_FIELD, resolvedCollectionName, Field.Store.YES));
                    
                    if (chunk.citation() != null) {
                        doc.add(new StoredField("doc_type", chunk.citation().docType()));
                        doc.add(new StoredField("source_file", chunk.citation().sourceFile()));
                        doc.add(new StoredField("page_number", chunk.citation().pageNumber()));
                        doc.add(new StoredField("section_title", chunk.citation().sectionTitle()));
                        doc.add(new StoredField("page_title", chunk.citation().pageTitle()));
                        doc.add(new StoredField("space_name", chunk.citation().spaceName()));
                        doc.add(new StoredField("space_weight", chunk.citation().spaceWeight()));
                        if (isConfluenceCollection(resolvedCollectionName)) {
                            String normalizedTitle = SearchTextNormalizer.normalize(chunk.citation().pageTitle());
                            if (!normalizedTitle.isBlank()) {
                                doc.add(new TextField(TITLE_SEARCH_FIELD, normalizedTitle, Field.Store.NO));
                            }
                        }
                    }
                    writer.addDocument(doc);
                }
                writer.commit();
                log.info("Indexed {} chunks into Lucene BM25", chunks.size());
            }
        } catch (IOException e) {
            log.error("Failed to index documents into Lucene", e);
        }
    }

    /**
     * Search Lucene index and return results with normalized [0,1] scores.
     */
    public Mono<List<BM25Result>> search(String query, int topK) {
        return search(query, null, topK);
    }

    /**
     * Search Lucene index for a specific collection and return results with normalized [0,1] scores.
     */
    public Mono<List<BM25Result>> search(String query, String collectionName, int topK) {
        return Mono.fromCallable(() -> {
            try {
                if (!DirectoryReader.indexExists(directory)) {
                    return List.<BM25Result>of();
                }

                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    Query luceneQuery = buildSearchQuery(query, collectionName);

                    TopDocs topDocs = searcher.search(luceneQuery, Math.max(1, topK));
                    List<BM25Result> results = new ArrayList<>();
                    
                    float maxScore = topDocs.totalHits.value > 0 ? topDocs.scoreDocs[0].score : 0.0f;

                    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                        Document doc = searcher.doc(scoreDoc.doc);
                        double normalizedScore = maxScore > 0 ? (double) scoreDoc.score / maxScore : 0.0;
                        
                        Map<String, Object> metadata = Map.of(
                                "doc_type", string(doc, "doc_type"),
                                "source_file", string(doc, "source_file"),
                                "page_number", integer(doc, "page_number"),
                                "section_title", string(doc, "section_title"),
                                "page_title", string(doc, "page_title"),
                                "space_name", string(doc, "space_name"),
                                "space_weight", doubleVal(doc, "space_weight")
                        );
                        SourceCitation citation = SourceCitation.fromMetadata(metadata);

                        DocumentChunk chunk = new DocumentChunk(
                                doc.get("id"),
                                doc.get("content"),
                                citation
                        );
                        results.add(new BM25Result(chunk, normalizedScore));
                    }
                    return results;
                }
            } catch (Exception e) {
                log.error("BM25 search failed for query: {}", query, e);
                return List.<BM25Result>of();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Hydrates in-memory index from PostgreSQL on startup.
     */
    @PostConstruct
    public void loadIndexFromDatabase() {
        if (entityTemplate == null) {
            log.warn("EntityTemplate is null, skipping BM25 hydration (likely in test)");
            return;
        }

        log.info("Hydrating BM25 index from R2DBC tables...");
        hydrateTable("embeddings_dlms_knowledge")
                .then(hydrateTable("embeddings_confluence_knowledge"))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> log.error("Failed to hydrate BM25 index", error),
                        () -> log.info("BM25 hydration complete")
                );
    }

    private reactor.core.publisher.Flux<DocumentChunk> loadFromTable(String tableName) {
        String sql = "SELECT id, content, metadata FROM " + tableName;
        return entityTemplate.getDatabaseClient()
                .sql(sql)
                .map((row, metadata) -> {
                    UUID uuid = row.get("id", UUID.class);
                    String id = uuid != null ? uuid.toString() : UUID.randomUUID().toString();
                    String content = row.get("content", String.class);
                    Object metaObj = row.get("metadata");

                    Map<String, Object> metaMap = parseMetadata(metaObj, id);

                    return new DocumentChunk(id, content, SourceCitation.fromMetadata(metaMap));
                })
                .all();
    }

    private String resolveCollectionName(DocumentChunk chunk, String collectionName) {
        if (collectionName != null && !collectionName.isBlank()) {
            return collectionName;
        }
        if (chunk != null && chunk.citation() != null && "confluence".equals(chunk.citation().docType())) {
            return CONFLUENCE_COLLECTION;
        }
        return "embeddings_dlms_knowledge";
    }

    private Mono<Void> hydrateTable(String tableName) {
        return loadFromTable(tableName)
                .buffer(100)
                .doOnNext(batch -> indexDocuments(batch, tableName))
                .then();
    }

    private String string(Document doc, String field) {
        String val = doc.get(field);
        return val != null ? val : "";
    }

    private int integer(Document doc, String field) {
        var f = doc.getField(field);
        return (f != null && f.numericValue() != null) ? f.numericValue().intValue() : 0;
    }

    private double doubleVal(Document doc, String field) {
        var f = doc.getField(field);
        return (f != null && f.numericValue() != null) ? f.numericValue().doubleValue() : 0.0;
    }

    private Query buildSearchQuery(String query, String collectionName) throws Exception {
        Query contentQuery = parseQuery(CONTENT_FIELD, query);
        Query baseQuery = contentQuery;

        if (isConfluenceCollection(collectionName)) {
            String normalizedQuery = SearchTextNormalizer.normalize(query);
            String titleQueryText = normalizedQuery.isBlank() ? query : normalizedQuery;
            Query titleQuery = parseQuery(TITLE_SEARCH_FIELD, titleQueryText);
            baseQuery = new BooleanQuery.Builder()
                    .add(new BoostQuery(contentQuery, 1.0f), BooleanClause.Occur.SHOULD)
                    .add(new BoostQuery(titleQuery, CONFLUENCE_TITLE_BOOST), BooleanClause.Occur.SHOULD)
                    .setMinimumNumberShouldMatch(1)
                    .build();
        }

        if (collectionName == null || collectionName.isBlank()) {
            return baseQuery;
        }

        return new BooleanQuery.Builder()
                .add(baseQuery, BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(COLLECTION_FIELD, collectionName)), BooleanClause.Occur.FILTER)
                .build();
    }

    private Query parseQuery(String field, String queryText) throws Exception {
        QueryParser parser = new QueryParser(field, analyzer);
        return parser.parse(QueryParser.escape(queryText));
    }

    private boolean isConfluenceCollection(String collectionName) {
        return CONFLUENCE_COLLECTION.equals(collectionName) || "confluence".equals(collectionName);
    }

    Map<String, Object> parseMetadata(Object metaObj, String chunkId) {
        if (metaObj == null) {
            return Map.of();
        }

        try {
            if (metaObj instanceof Map map) {
                return (Map<String, Object>) map;
            }
            if (metaObj instanceof Json json) {
                return objectMapper.readValue(json.asString(), new TypeReference<Map<String, Object>>() {});
            }
            if (metaObj instanceof String text && !text.isBlank()) {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
            }

            String serialized = metaObj.toString();
            if (!serialized.isBlank()) {
                return objectMapper.readValue(serialized, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to parse metadata for chunk {} from type {}", chunkId, metaObj.getClass().getName(), e);
        }

        return Map.of();
    }
}
