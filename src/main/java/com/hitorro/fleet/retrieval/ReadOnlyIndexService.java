/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.hitorro.index.config.IndexConfig;
import com.hitorro.index.search.JVSLuceneSearcher;
import com.hitorro.jsontypesystem.Type;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only view of on-disk Lucene indexes. Constructs
 * {@link JVSLuceneSearcher} directly via {@link IndexConfig#getDirectory()}
 * (which opens a {@code DirectoryReader}) — no {@code IndexWriter}, no
 * {@code write.lock} contention with the pipeline writers landing new
 * documents into the same directory.
 *
 * <p>Used by {@link FleetIndexService} when {@code mode=shared}; standalone
 * mode still routes through {@link com.hitorro.index.IndexManager} because
 * it needs the writer half for the {@code /api/ingest/*} REST surface.</p>
 */
public class ReadOnlyIndexService {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyIndexService.class);

    private final String defaultLanguage;
    private final Map<String, JVSLuceneSearcher> searchers = new ConcurrentHashMap<>();
    private final Map<String, Path>              paths     = new ConcurrentHashMap<>();

    public ReadOnlyIndexService(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage == null ? "en" : defaultLanguage;
    }

    /** Open (or return cached) read-only searcher for the given on-disk index. */
    public synchronized JVSLuceneSearcher openOrGet(String name, Path indexDir) throws IOException {
        JVSLuceneSearcher existing = searchers.get(name);
        if (existing != null) return existing;
        if (!Files.isDirectory(indexDir)) throw new IOException("No such index dir: " + indexDir);
        IndexConfig cfg = IndexConfig.builder().filesystem(indexDir).storeSource(true).build();
        JVSLuceneSearcher s = new JVSLuceneSearcher(cfg, /*type*/ null, defaultLanguage);
        searchers.put(name, s);
        paths.put(name, indexDir);
        return s;
    }

    /** Force a refresh so writes since the last open are visible. */
    public synchronized JVSLuceneSearcher refresh(String name) throws IOException {
        JVSLuceneSearcher old = searchers.get(name);
        if (old == null) return null;
        JVSLuceneSearcher refreshed = old.refresh();
        if (refreshed != old) {
            searchers.put(name, refreshed);
            try { old.close(); } catch (IOException e) { log.debug("close on refresh", e); }
        }
        return refreshed;
    }

    public JVSLuceneSearcher get(String name) { return searchers.get(name); }
    public Path pathOf(String name)           { return paths.get(name); }
    public Set<String> names()                { return searchers.keySet(); }

    public Map<String, Object> describe(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("path", paths.getOrDefault(name, Path.of("?")).toString());
        m.put("open", searchers.containsKey(name));
        return m;
    }

    @PreDestroy
    public void close() {
        for (JVSLuceneSearcher s : searchers.values()) {
            try { s.close(); } catch (IOException e) { log.debug("close", e); }
        }
        searchers.clear();
    }
}
