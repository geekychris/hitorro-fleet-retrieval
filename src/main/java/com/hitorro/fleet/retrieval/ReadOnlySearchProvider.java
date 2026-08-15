/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.hitorro.index.search.JVSLuceneSearcher;
import com.hitorro.index.search.SearchResult;
import com.hitorro.retrieval.search.SearchProvider;

import java.util.List;

/**
 * {@link SearchProvider} backed by {@link ReadOnlyIndexService}.
 * Bypasses {@code IndexManager} + {@code LuceneSearchProvider} because
 * the former grabs {@code write.lock} on every index open — fine for
 * standalone mode but incompatible with concurrent pipeline writers in
 * shared mode.
 */
public class ReadOnlySearchProvider implements SearchProvider {

    private final ReadOnlyIndexService indexes;
    private final java.util.function.Function<String, java.nio.file.Path> dirResolver;

    public ReadOnlySearchProvider(ReadOnlyIndexService indexes,
                                  java.util.function.Function<String, java.nio.file.Path> dirResolver) {
        this.indexes = indexes;
        this.dirResolver = dirResolver;
    }

    @Override
    public SearchResult search(String indexName, String queryString, int offset, int limit,
                               List<String> facetDims, String lang) throws Exception {
        JVSLuceneSearcher s = indexes.get(indexName);
        if (s == null) {
            java.nio.file.Path dir = dirResolver.apply(indexName);
            if (dir == null) throw new IllegalArgumentException("Unknown index: " + indexName);
            s = indexes.openOrGet(indexName, dir);
        } else {
            // Pick up any writes that landed since the last query.
            s = indexes.refresh(indexName);
        }
        return s.search(queryString, offset, limit, facetDims, lang);
    }

    @Override public String getName()     { return "read-only-lucene"; }
    @Override public boolean isAvailable(){ return true; }
}
