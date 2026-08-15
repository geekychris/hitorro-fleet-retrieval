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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Discovers on-disk Lucene indexes under {@code luceneRoot} and hands out
 * either read-only or read-write handles depending on mode.
 *
 * <ul>
 *   <li>{@code mode=shared}   — read-only via {@link ReadOnlyIndexService}.
 *       {@code DirectoryReader.open(FSDirectory)} takes no {@code write.lock},
 *       so mesh pipeline writers can freely append to the same directory.
 *       Any attempt to ingest through {@code /api/ingest/*} is refused.</li>
 *   <li>{@code mode=standalone} — read-write via {@link IndexManager}.
 *       Owns the {@code write.lock} so REST ingest can land documents.</li>
 * </ul>
 */
@Service
public class FleetIndexService {

    private static final Logger log = LoggerFactory.getLogger(FleetIndexService.class);

    private final FleetRetrievalProperties props;
    /** Populated in standalone mode only. */
    private final IndexManager indexManager;
    /** Populated in shared mode only. */
    private final ReadOnlyIndexService readOnly;
    /** Discovered on-disk index names — both modes. */
    private final Set<String> discovered = new LinkedHashSet<>();
    /** Optional type-name per index, for type-aware retrieval. */
    private final Map<String, String> typeNames = new ConcurrentHashMap<>();

    public FleetIndexService(FleetRetrievalProperties props) {
        this.props = props;
        boolean shared = props.getMode() == FleetRetrievalProperties.Mode.shared;
        this.indexManager = shared ? null : new IndexManager(props.getDefaultLanguage());
        this.readOnly     = shared ? new ReadOnlyIndexService(props.getDefaultLanguage()) : null;
    }

    @PostConstruct
    public void discover() {
        Path root = props.luceneRoot();
        try { Files.createDirectories(root); }
        catch (IOException e) { log.warn("Cannot create luceneRoot={}: {}", root, e.getMessage()); }

        int count = 0;
        try (Stream<Path> subdirs = Files.list(root)) {
            for (Path p : (Iterable<Path>) subdirs.filter(Files::isDirectory)::iterator) {
                if (!looksLikeLuceneIndex(p)) continue;
                String name = p.getFileName().toString();
                try {
                    openExisting(name, p);
                    discovered.add(name);
                    count++;
                } catch (Exception e) {
                    log.warn("Skipping malformed index {} ({}): {}", name, p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Cannot walk luceneRoot={}: {}", root, e.getMessage());
        }
        log.info("Fleet-retrieval mode={} luceneRoot={} discovered={} index(es)",
                props.getMode(), root, count);
    }

    private static boolean looksLikeLuceneIndex(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith("segments_"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Register an existing index. Shared mode opens read-only; standalone
     * opens through IndexManager (writer + searcher).
     */
    private void openExisting(String name, Path dir) throws IOException {
        if (readOnly != null) {
            // Lazy — we don't need to open the DirectoryReader until the
            // first query. Just remember the name.
            return;
        }
        IndexConfig cfg = IndexConfig.builder().filesystem(dir).storeSource(true).build();
        indexManager.createIndex(name, cfg, null);
    }

    /**
     * Create a new index under {@code ${luceneRoot}/name/}. Standalone-mode only —
     * shared mode returns an error rather than mutating pipeline-owned data.
     */
    public synchronized void createIndex(String name, String typeName) throws IOException {
        if (readOnly != null) {
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
        discovered.add(name);
        if (typeName != null) typeNames.put(name, typeName);
    }

    /**
     * Re-open (or rediscover) an index whose directory changed externally
     * — e.g. after a pipeline sink wrote fresh segments.
     */
    public synchronized void refreshIndex(String name) throws IOException {
        Path dir = props.luceneRoot().resolve(name);
        if (!Files.isDirectory(dir)) throw new IOException("No such index directory: " + dir);
        if (readOnly != null) {
            readOnly.refresh(name);
            discovered.add(name);
            return;
        }
        if (indexManager.hasIndex(name)) indexManager.closeIndex(name);
        IndexConfig cfg = IndexConfig.builder().filesystem(dir).storeSource(true).build();
        indexManager.createIndex(name, cfg, null);
        discovered.add(name);
    }

    /** Re-scan the luceneRoot for any new index directories. */
    public synchronized int rescan() {
        Path root = props.luceneRoot();
        int added = 0;
        try (Stream<Path> subdirs = Files.list(root)) {
            for (Path p : (Iterable<Path>) subdirs.filter(Files::isDirectory)::iterator) {
                String name = p.getFileName().toString();
                if (discovered.contains(name)) continue;
                if (!looksLikeLuceneIndex(p)) continue;
                try { openExisting(name, p); discovered.add(name); added++; }
                catch (Exception e) { log.warn("rescan skip {}: {}", name, e.getMessage()); }
            }
        } catch (IOException ignore) {}
        return added;
    }

    // ─── Accessors ──────────────────────────────────────────────────

    public boolean hasIndex(String name) {
        rescan();
        return discovered.contains(name);
    }
    public Set<String> listIndexes() {
        rescan();
        return new LinkedHashSet<>(discovered);
    }
    public IndexManager indexManager() { return indexManager; }
    public ReadOnlyIndexService readOnly() { return readOnly; }
    public boolean isReadOnly() { return readOnly != null; }
    public Path pathOf(String name) { return props.luceneRoot().resolve(name); }

    public String typeName(String indexName) { return typeNames.get(indexName); }
    public void setTypeName(String indexName, String typeName) {
        if (typeName != null) typeNames.put(indexName, typeName);
    }

    public Map<String, Object> describe(String indexName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", indexName);
        m.put("path", pathOf(indexName).toString());
        m.put("typeName", typeNames.get(indexName));
        m.put("open", discovered.contains(indexName));
        m.put("readOnly", readOnly != null);
        return m;
    }

    @PreDestroy
    public void close() {
        if (indexManager != null) {
            try { indexManager.close(); } catch (IOException e) { log.warn("IndexManager close", e); }
        }
        if (readOnly != null) readOnly.close();
    }
}
