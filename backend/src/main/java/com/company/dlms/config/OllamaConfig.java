package com.company.dlms.config;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the OllamaApi and OllamaEmbeddingModel beans needed by PgVectorStore.
 * OllamaAutoConfiguration is NOT excluded, so base-url comes from application.yml
 * (spring.ai.ollama.base-url). This class only provides the explicit embedding model bean.
 *
 * The auto-configured OllamaEmbeddingModel bean is sufficient in most cases, but we declare
 * it explicitly here so that @Qualifier("embeddingModel") works predictably in RAGVectorStoreConfig.
 */
@Configuration
public class OllamaConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}")
    private String embeddingModel;

    @Bean
    public OllamaApi ollamaApi() {
        return new OllamaApi(baseUrl);
    }

    @Bean
    public OllamaEmbeddingModel embeddingModel(OllamaApi ollamaApi) {
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaOptions.builder().model(embeddingModel).build())
                .build();
    }
}
