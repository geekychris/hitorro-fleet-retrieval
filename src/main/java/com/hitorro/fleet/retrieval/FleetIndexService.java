/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.hitorro.index.IndexManager;
import com.hitorro.index.config.IndexConfig;
import com.hitorro.jsontypesystem.JsonTypeSystem;
import com.hitorro.jsontypesystem.Type;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Owns the {@link IndexManager} and the disk-layout convention that maps a
 * name → {@code ${luceneRoot}/<name>/} on-disk Lucene directory.
 *
 * <p>At boot, walks {@code luceneRoot} and registers every subdirectory that
 * looks like a Lucene index (contains a {@code segments_*} file). Pipeline
 * writers land indexes into the same layout — no separate registration step.</p>
 */
@Service
public class FleetIndexService {

    private static final Logger log = LoggerFactory.getLogger(FleetIndexService.class);

    private final FleetRetrievalProperties props;
    private final IndexManager indexManager;
    /** Optional type-name per index, for type-aware retrieval. */
    private final Map<String, String> typeNames = new ConcurrentHashMap<>();

    public FleetIndexService(FleetRetrievalProperties props) {
        this.props = props;
        this.indexManager = new IndexManager(props.getDefaultLanguage());
    }

    @PostConstruct
    public void discover() {
        Path root = props.luceneRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Cannot create luceneRoot={}: {}", root, e.getMessage());
        }
        int discovered = 0;
        try (Stream<Path> subdirs = Files.list(root)) {
            for (Path p : (Iterable<Path>) subdirs.filter(Files::isDirectory)::iterator) {
                if (looksLikeLuceneIndex(p)) {
                    String name = p.getFileName().toString();
                    try {
                        openExisting(name, p);
                        discovered++;
                    } catch (Exception e) {
                        log.warn("Skipping malformed index {} ({}): {}", name, p, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Cannot walk luceneRoot={}: {}", root, e.getMessage());
        }
        log.info("Fleet-retrieval mode={} luceneRoot={} discovered={} index(es)",
                props.getMode(), root, discovered);
    }

    private static boolean looksLikeLuceneIndex(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith("segments_"));
        } catch (IOException e) {
            return false;
        }
    }

    private void openExisting(String name, Path dir) throws IOException {
        // Register with default config; Directory points at the existing dir.
        // storeSource(true) is a no-op if there is no _source in the existing docs.
        IndexConfig cfg = IndexConfig.builder().filesystem(dir).storeSource(true).build();
        indexManager.createIndex(name, cfg, null);
    }

    /**
     * Create a new index under {@code ${luceneRoot}/name/}. Standalone-mode only —
     * shared mode returns an error rather than mutating pipeline-owned data.
     */
    public synchronized void createIndex(String name, String typeName) throws IOException {
        if (props.getMode() == FleetRetrievalProperties.Mode.shared) {
            throw new IllegalStateException(
                "Refusing to create index in shared mode — indexes are owned by pipeline writers. "
                + "Set hitorro.fleet.retrieval.mode=standalone to create indexes here.");
        }
        if (indexManager.hasIndex(name)) return;
        Path dir = props.luceneRoot().resolve(name);
        Files.createDirectories(dir);
        IndexConfig cfg = IndexConfig.builder().filesystem(dir).storeSource(true).build();
        Type type = null;
        if (typeName != null && !typeName.isBlank()) {
            try { type = JsonTypeSystem.getMe().getType(typeName); } catch (Exception ignore) {}
        }
        indexManager.createIndex(name, cfg, type);
        if (typeName != null) typeNames.put(name, typeName);
    }

    /** Re-open an index whose directory was populated externally (e.g. pipeline sink). */
    public synchronized void refreshIndex(String name) throws IOException {
        Path dir = props.luceneRoot().resolve(name);
        if (!Files.isDirectory(dir)) throw new IOException("No such index directory: " + dir);
        if (indexManager.hasIndex(name)) indexManager.closeIndex(name);
        openExisting(name, dir);
    }

    public boolean hasIndex(String name) { return indexManager.hasIndex(name); }
    public Set<String> listIndexes() { return indexManager.getIndexNames(); }
    public IndexManager indexManager() { return indexManager; }
    public String typeName(String indexName) { return typeNames.get(indexName); }
    public void setTypeName(String indexName, String typeName) {
        if (typeName != null) typeNames.put(indexName, typeName);
    }

    public Map<String, Object> describe(String indexName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", indexName);
        m.put("path", props.luceneRoot().resolve(indexName).toString());
        m.put("typeName", typeNames.get(indexName));
        m.put("open", indexManager.hasIndex(indexName));
        return m;
    }

    @PreDestroy
    public void close() {
        try { indexManager.close(); } catch (IOException e) { log.warn("IndexManager close", e); }
    }
}
