/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.hitorro.retrieval.search.ReadOnlySearchProvider;

import com.hitorro.index.readonly.ReadOnlyIndexService;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.index.search.SearchResult;
import com.hitorro.jsontypesystem.JVS;
import com.hitorro.jsontypesystem.JsonTypeSystem;
import com.hitorro.jsontypesystem.Type;
import com.hitorro.jsontypesystem.datamapper.AIOperations;
import com.hitorro.kvstore.TypedKVStore;
import com.hitorro.retrieval.RetrievalConfig;
import com.hitorro.retrieval.RetrievalResult;
import com.hitorro.retrieval.RetrievalService;
import com.hitorro.retrieval.docstore.DocumentStore;
import com.hitorro.retrieval.docstore.LocalKVDocumentStore;
import com.hitorro.retrieval.aggregate.MergePolicy;
import com.hitorro.retrieval.merger.FieldSortMerger;
import com.hitorro.retrieval.merger.RRFMerger;
import com.hitorro.retrieval.merger.ResultMerger;
import com.hitorro.retrieval.merger.ScoreMerger;
import com.hitorro.retrieval.merger.SortCriteria;
import com.hitorro.retrieval.search.CompositeSearchProvider;
import com.hitorro.retrieval.search.LuceneSearchProvider;
import com.hitorro.retrieval.search.SearchProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Front-door to the full retrieval coordination runtime. Builds a
 * {@link RetrievalService} per call (cheap — the heavy state lives in
 * {@link FleetIndexService} and {@link FleetKvService}) with the right
 * search provider + document store + optional summarization stage for
 * the request.
 *
 * <p>In shared mode the search provider is a {@link ReadOnlySearchProvider}
 * backed by {@link ReadOnlyIndexService} so the on-disk indexes have no
 * {@code write.lock} contention with the pipeline writers. In standalone
 * mode the coordinator wraps {@link LuceneSearchProvider} over the local
 * {@code IndexManager} (which owns both the writer and searcher because
 * REST ingest lands here too).</p>
 */
@Service
public class FleetCoordinatorService {

    private final FleetIndexService indexes;
    private final FleetKvService kv;
    private final FleetRetrievalProperties props;
    /** Optional. Present when {@code hitorro.fleet.retrieval.ollama.enabled=true}
     *  and {@link AiOperationsConfig} contributed an {@link AIOperations} bean.
     *  When null the summarize stage stays disabled — legacy behavior. */
    private final AIOperations aiOperations;

    public FleetCoordinatorService(FleetIndexService indexes, FleetKvService kv,
                                   FleetRetrievalProperties props,
                                   org.springframework.beans.factory.ObjectProvider<AIOperations> aiProvider) {
        this.indexes = indexes;
        this.kv = kv;
        this.props = props;
        this.aiOperations = aiProvider.getIfAvailable();
    }

    /** Exposed for direct-text callers (e.g. {@code /api/retrieval/summarize}).
     *  Returns null when no AIOperations bean is wired. */
    public AIOperations aiOperations() { return aiOperations; }

    /**
     * Single-index execute — full RetrievalService pipeline
     * (Index → Document → Fixup → Pagination → Facet → Summarization).
     */
    public RetrievalResult execute(String indexName, JVS query, String lang) {
        if (!indexes.hasIndex(indexName)) {
            throw new IllegalArgumentException("Index not found: " + indexName);
        }
        SearchProvider search = providerFor(indexName);
        TypedKVStore<JsonNode> store = kv.openOrCreate(indexName);
        DocumentStore docStore = store != null ? new LocalKVDocumentStore(store) : null;

        RetrievalService svc = docStore != null
                ? new RetrievalService(search, docStore)
                : new RetrievalService(search);
        // Enable the summarize stage per-request when an AIOperations bean is
        // wired. Callers opt in via {@code summarize.style} / {@code summarize.prompt}
        // in the JVS query; requests without a summarize block are unaffected.
        if (aiOperations != null && aiOperations.isAvailable()) {
            svc.enableSummarization(aiOperations);
        }

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
        return searchMultiple(indexNames, query, offset, limit, facets, lang, mergerName,
                              /*policies*/ Collections.emptyMap(), /*sortChain*/ null);
    }

    /**
     * Full multi-index execute — as above, plus per-aggregate
     * {@link MergePolicy} overrides + a composite {@link SortCriteria}
     * chain that drives the merger's k-way select tree.
     *
     * <p>Backward-compat: null / empty extras behave exactly like the
     * three-arg overload (defaults per aggregate, byScore() sort). The
     * {@code mergerName} arg is respected only when {@code sortChain}
     * is null — an explicit sort chain always wins over merger-name
     * selection.</p>
     */
    public SearchResult searchMultiple(List<String> indexNames, String query, int offset, int limit,
                                       List<String> facets, String lang, String mergerName,
                                       Map<String, MergePolicy> policies,
                                       SortCriteria sortChain) throws Exception {
        List<SearchProvider> providers = new ArrayList<>();
        for (String name : indexNames) {
            if (!indexes.hasIndex(name)) continue;
            providers.add(providerFor(name));
        }
        if (providers.isEmpty()) throw new IllegalArgumentException("No valid indexes: " + indexNames);

        // Resolve sort: explicit chain > mergerName-derived > default byScore.
        SortCriteria effectiveSort = sortChain != null ? sortChain : sortFromMergerName(mergerName);

        CompositeSearchProvider composite = new CompositeSearchProvider(providers,
                effectiveSort,
                policies != null ? policies : Collections.emptyMap(),
                /*executor*/ null,   // shared JVM-wide pool
                /*perBranchTimeoutMs*/ 0);   // use system-property default
        return composite.search("multi", query, offset, limit, facets, effectiveLang(lang));
    }

    /** Turn the string merger-name into a SortCriteria for the SelectTreeMerger.
     *  Legacy {@code score}/{@code rrf}/{@code field:name[:desc]} still work. */
    private SortCriteria sortFromMergerName(String name) {
        if (name == null || name.isBlank()) return SortCriteria.byScore();
        String lc = name.toLowerCase();
        if (lc.startsWith("field:")) {
            String rest = name.substring("field:".length());
            String[] parts = rest.split(":");
            SortCriteria.Direction dir = parts.length > 1 && "desc".equalsIgnoreCase(parts[1])
                    ? SortCriteria.Direction.DESC : SortCriteria.Direction.ASC;
            return SortCriteria.byField(parts[0], dir);
        }
        // "rrf" doesn't have a direct SortCriteria equivalent — SelectTreeMerger
        // sorts by whatever we give it. RRF is a per-rank fusion strategy that
        // belongs in the merger's scoring, not its comparator; for now fall
        // back to score-descending. When we plumb a per-doc rrf score into
        // the docs, this can switch.
        return SortCriteria.byScore();
    }

    /**
     * Build a SearchProvider that always targets the given index name.
     * Shared mode → read-only DirectoryReader-backed provider (no
     * {@code write.lock}). Standalone mode → LuceneSearchProvider over
     * the writer-owning IndexManager.
     */
    /**
     * Raw single-index search bypassing the retrieval pipeline — used by
     * the wire-transport {@code /api/retrieval/search} endpoint that
     * {@code RemoteSearchProvider} clients call.
     */
    public SearchResult executeRaw(String indexName, String query, int offset, int limit,
                                   List<String> facetDims, String lang) throws Exception {
        return executeRaw(indexName, query, offset, limit, facetDims, lang, null);
    }

    /** Raw single-index search with an optional caller-requested sort
     *  chain. Skips the retrieval pipeline (no fixup / summarize etc)
     *  but still honours sort by handing the chain to the underlying
     *  provider's sort-aware overload. */
    public SearchResult executeRaw(String indexName, String query, int offset, int limit,
                                   List<String> facetDims, String lang,
                                   com.hitorro.retrieval.merger.SortCriteria sortCriteria) throws Exception {
        return providerFor(indexName).search(indexName, query, offset, limit, facetDims,
                                             effectiveLang(lang), sortCriteria);
    }

    private SearchProvider providerFor(String indexName) {
        final String captured = indexName;
        if (indexes.isReadOnly()) {
            ReadOnlySearchProvider ro = new ReadOnlySearchProvider(indexes.readOnly(),
                    indexes::pathOf);
            return new SearchProvider() {
                @Override
                public SearchResult search(String ig, String q, int o, int l,
                                           List<String> f, String ln) throws Exception {
                    return ro.search(captured, q, o, l, f, ln);
                }
                @Override
                public SearchResult search(String ig, String q, int o, int l,
                                           List<String> f, String ln,
                                           com.hitorro.retrieval.merger.SortCriteria sc) throws Exception {
                    return ro.search(captured, q, o, l, f, ln, sc);
                }
                @Override public String getName()      { return "read-only-lucene:" + captured; }
                @Override public boolean isAvailable() { return true; }
            };
        }
        return new LuceneSearchProvider(indexes.indexManager()) {
            @Override
            public SearchResult search(String ig, String q, int o, int l,
                                       List<String> f, String ln) throws Exception {
                return super.search(captured, q, o, l, f, ln);
            }
            @Override
            public SearchResult search(String ig, String q, int o, int l,
                                       List<String> f, String ln,
                                       com.hitorro.retrieval.merger.SortCriteria sc) throws Exception {
                return super.search(captured, q, o, l, f, ln, sc);
            }
            @Override public String getName() { return "lucene:" + captured; }
        };
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
