package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.infrastructure.rag.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObisResolverTest {

    @Test
    void tier1KgHit_returnsKgTier() {
        DatabaseClient db = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        RetrievalService rag = mock(RetrievalService.class);

        when(rag.retrieve(anyString(), any(), anyInt())).thenReturn(Flux.empty());
        when(db.sql(anyString()).bind(anyString(), any()).map(any(BiFunction.class)).one())
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "desc", 3, "Wh", -1, ResolutionTier.KG)));

        ObisResolver resolver = new ObisResolver(db, rag);

        ObisResolution out = resolver.resolve("1.0.1.8.0.255", "s1").block();
        assertNotNull(out);
        assertEquals(ResolutionTier.KG, out.tierUsed());
    }

    @Test
    void tier2Structural_returnsNonEmptyDescription() {
        ObisResolution out = ObisResolver.structuralDecode("1.0.1.8.0.255");
        assertEquals(ResolutionTier.STRUCTURAL, out.tierUsed());
        assertNotNull(out.description());
        assertFalse(out.description().isBlank());
    }

    @Test
    void tier3RagHit_returnsRagTier() {
        DatabaseClient db = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        RetrievalService rag = mock(RetrievalService.class);

        when(db.sql(anyString()).bind(anyString(), any()).map(any(BiFunction.class)).one()).thenReturn(Mono.empty());

        DocumentChunk chunk = new DocumentChunk(
                "c1",
                "RAG description",
                new SourceCitation("doc", "file", 1, "section", "", "", 1.0, "formatted")
        );
        RetrievalResult rr = new RetrievalResult(chunk, 1.0, 1.0, 1.0);
        when(rag.retrieve(eq("99"), eq(IntentType.OBIS_LOOKUP), eq(3))).thenReturn(Flux.just(rr));

        ObisResolver resolver = new ObisResolver(db, rag);
        ObisResolution out = resolver.resolve("99", "s1").block();

        assertNotNull(out);
        assertEquals(ResolutionTier.RAG, out.tierUsed());
        assertEquals("RAG description", out.description());
    }
}

