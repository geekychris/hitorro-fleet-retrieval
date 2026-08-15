/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.kvstore.DatabaseConfig;
import com.hitorro.kvstore.RocksDBStore;
import com.hitorro.kvstore.TypedKVStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Per-index RocksDB store manager. Maps {@code name → ${kvRoot}/<name>/}.
 *
 * <ul>
 *   <li>{@code mode=standalone} — opens with the writer-owning
 *       {@link RocksDBStore} so REST ingest can put values.</li>
 *   <li>{@code mode=shared}     — opens as a RocksDB <b>secondary</b>
 *       via {@link ReadOnlyKvStore} so pipeline writers keep exclusive
 *       write access and this service just follows.</li>
 * </ul>
 *
 * <p>Stores open lazily on first {@link #openOrCreate(String)}. If
 * {@code kv-enabled=false} the whole service is a no-op.</p>
 */
@Service
public class FleetKvService {

    private static final Logger log = LoggerFactory.getLogger(FleetKvService.class);

    private final FleetRetrievalProperties props;
    private final Map<String, TypedKVStore<JsonNode>> stores    = new ConcurrentHashMap<>();
    private final Map<String, RocksDBStore>           rawStores = new ConcurrentHashMap<>();
    private final Map<String, ReadOnlyKvStore>        secondary = new ConcurrentHashMap<>();

    public FleetKvService(FleetRetrievalProperties props) {
        this.props = props;
        Path root = props.kvRoot();
        try { Files.createDirectories(root); } catch (IOException e) {
            log.warn("Cannot create kvRoot={}: {}", root, e.getMessage());
        }
        // Auto-discover existing stores.
        if (Files.isDirectory(root)) {
            try (Stream<Path> subdirs = Files.list(root)) {
                subdirs.filter(Files::isDirectory).forEach(p -> {
                    String name = p.getFileName().toString();
                    try { openOrCreate(name); } catch (Exception e) {
                        log.warn("Skipping malformed KV {} ({}): {}", name, p, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("Cannot walk kvRoot={}: {}", root, e.getMessage());
            }
        }
    }

    /** Returns the typed store for the given name, opening it on first access. */
    public synchronized TypedKVStore<JsonNode> openOrCreate(String name) {
        if (!props.isKvEnabled()) return null;
        TypedKVStore<JsonNode> existing = stores.get(name);
        if (existing != null) return existing;
        try {
            Path dir = props.kvRoot().resolve(name);
            if (props.getMode() == FleetRetrievalProperties.Mode.shared) {
                // Only try to attach if the primary already exists on disk.
                if (!Files.isDirectory(dir)) return null;
                Path secDir = props.kvSecondaryRoot().resolve(name);
                ReadOnlyKvStore sec = new ReadOnlyKvStore(dir, secDir);
                TypedKVStore<JsonNode> typed = new TypedKVStore<>(sec, JsonNode.class);
                secondary.put(name, sec);
                stores.put(name, typed);
                return typed;
            }
            Files.createDirectories(dir);
            DatabaseConfig cfg = DatabaseConfig.builder(dir.toString()).createIfMissing(true).build();
            RocksDBStore raw = new RocksDBStore(cfg);
            TypedKVStore<JsonNode> typed = new TypedKVStore<>(raw, JsonNode.class);
            rawStores.put(name, raw);
            stores.put(name, typed);
            return typed;
        } catch (Exception e) {
            log.warn("Cannot open KV store {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Returns the typed store or null if disabled / not present. */
    public TypedKVStore<JsonNode> get(String name) {
        if (!props.isKvEnabled()) return null;
        TypedKVStore<JsonNode> existing = stores.get(name);
        if (existing != null) return existing;
        // Late-arriving KV — pipeline may have created it after our boot scan.
        return openOrCreate(name);
    }

    public Set<String> listStores() { return stores.keySet(); }

    @PreDestroy
    public void close() {
        for (var s : rawStores.values()) {
            try { s.close(); } catch (Exception e) { log.debug("KV close", e); }
        }
        for (var s : secondary.values()) {
            try { s.close(); } catch (Exception e) { log.debug("KV secondary close", e); }
        }
        rawStores.clear();
        secondary.clear();
        stores.clear();
    }
}
