/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.index.search.SearchResult;
import com.hitorro.jsontypesystem.JVS;
import com.hitorro.jsontypesystem.JsonTypeSystem;
import com.hitorro.jsontypesystem.Type;
import com.hitorro.kvstore.TypedKVStore;
import com.hitorro.retrieval.RetrievalConfig;
import com.hitorro.retrieval.RetrievalResult;
import com.hitorro.retrieval.RetrievalService;
import com.hitorro.retrieval.docstore.DocumentStore;
import com.hitorro.retrieval.docstore.LocalKVDocumentStore;
import com.hitorro.retrieval.merger.FieldSortMerger;
import com.hitorro.retrieval.merger.RRFMerger;
import com.hitorro.retrieval.merger.ResultMerger;
import com.hitorro.retrieval.merger.ScoreMerger;
import com.hitorro.retrieval.search.CompositeSearchProvider;
import com.hitorro.retrieval.search.LuceneSearchProvider;
import com.hitorro.retrieval.search.SearchProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Front-door to the full retrieval coordination runtime. Builds a
 * {@link RetrievalService} per call (cheap — the heavy state lives in
 * {@link FleetIndexService#indexManager()} and {@link FleetKvService})
 * with the right search provider + document store + optional
 * summarization stage for the request.
 *
 * <p>Single-index calls go through {@link RetrievalService} which wraps
 * {@code RetrievalPipelineBuilder}. Multi-index calls fan out through a
 * {@link CompositeSearchProvider} with a caller-selected merger.</p>
 */
@Service
public class FleetCoordinatorService {

    private final FleetIndexService indexes;
    private final FleetKvService kv;
    private final FleetRetrievalProperties props;

    public FleetCoordinatorService(FleetIndexService indexes, FleetKvService kv,
                                   FleetRetrievalProperties props) {
        this.indexes = indexes;
        this.kv = kv;
        this.props = props;
    }

    /**
     * Single-index execute — full RetrievalService pipeline
     * (Index → Document → Fixup → Pagination → Facet → Summarization).
     */
    public RetrievalResult execute(String indexName, JVS query, String lang) {
        if (!indexes.hasIndex(indexName)) {
            throw new IllegalArgumentException("Index not found: " + indexName);
        }
        SearchProvider search = new LuceneSearchProvider(indexes.indexManager());
        TypedKVStore<JsonNode> store = kv.openOrCreate(indexName);
        DocumentStore docStore = store != null ? new LocalKVDocumentStore(store) : null;

        RetrievalService svc = docStore != null
                ? new RetrievalService(search, docStore)
                : new RetrievalService(search);

        Type type = resolveType(indexName);
        RetrievalConfig cfg = new RetrievalConfig(indexName,
                type, effectiveLang(lang));
        return svc.retrieve(cfg, query);
    }

    /**
     * Multi-index execute — fan out over the given indexes via
     * {@link CompositeSearchProvider}, merge with the named merger.
     * KV fallback per doc uses the same-name convention.
     */
    public SearchResult searchMultiple(List<String> indexNames, String query, int offset, int limit,
                                       List<String> facets, String lang, String mergerName) throws Exception {
        List<SearchProvider> providers = new ArrayList<>();
        for (String name : indexNames) {
            if (!indexes.hasIndex(name)) continue;
            final String captured = name;
            providers.add(new LuceneSearchProvider(indexes.indexManager()) {
                @Override
                public SearchResult search(String ig, String q, int o, int l,
                                           List<String> f, String ln) throws Exception {
                    return super.search(captured, q, o, l, f, ln);
                }
                @Override public String getName() { return "lucene:" + captured; }
            });
        }
        if (providers.isEmpty()) throw new IllegalArgumentException("No valid indexes: " + indexNames);
        CompositeSearchProvider composite = new CompositeSearchProvider(providers, selectMerger(mergerName));
        return composite.search("multi", query, offset, limit, facets, effectiveLang(lang));
    }

    private Type resolveType(String indexName) {
        String typeName = indexes.typeName(indexName);
        if (typeName == null) return null;
        try { return JsonTypeSystem.getMe().getType(typeName); }
        catch (Exception ignore) { return null; }
    }

    private String effectiveLang(String lang) {
        return (lang == null || lang.isBlank()) ? props.getDefaultLanguage() : lang;
    }

    private ResultMerger selectMerger(String name) {
        if (name == null) return new ScoreMerger();
        return switch (name.toLowerCase()) {
            case "field", "fieldsort" -> new FieldSortMerger();
            case "rrf" -> new RRFMerger();
            default -> new ScoreMerger();
        };
    }
}
