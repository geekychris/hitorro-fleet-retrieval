/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.hitorro.fleet.retrieval.FleetIndexService;
import com.hitorro.fleet.retrieval.FleetKvService;
import com.hitorro.fleet.retrieval.FleetRetrievalProperties;
import com.hitorro.jsontypesystem.JVS;
import com.hitorro.kvstore.TypedKVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone-mode ingest — create indexes and push documents in via REST.
 * Refuses to run in shared mode (pipeline-fed indexes are owned by the
 * pipeline writers; letting a reader mutate them would silently diverge
 * the mesh's SinkLocationRegistry). Every write also lands in the
 * same-name KV store so the DocumentStore fallback stage sees it.
 */
@RestController
@RequestMapping("/api/ingest")
@CrossOrigin(origins = "*")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final FleetIndexService indexes;
    private final FleetKvService kv;
    private final FleetRetrievalProperties props;

    public IngestController(FleetIndexService indexes, FleetKvService kv,
                            FleetRetrievalProperties props) {
        this.indexes = indexes;
        this.kv = kv;
        this.props = props;
    }

    @PostMapping("/indexes")
    public ResponseEntity<Map<String, Object>> createIndex(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!ensureIngestAllowed(result)) return ResponseEntity.status(409).body(result);
        String name = body.getOrDefault("name", "").trim();
        String typeName = body.get("typeName");
        if (name.isEmpty()) {
            result.put("success", false);
            result.put("error", "name required");
            return ResponseEntity.badRequest().body(result);
        }
        try {
            indexes.createIndex(name, typeName);
            result.put("name", name);
            result.put("typeName", typeName);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/indexes/{name}/documents")
    public ResponseEntity<Map<String, Object>> indexDocuments(@PathVariable String name,
                                                              @RequestBody JsonNode body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!ensureIngestAllowed(result)) return ResponseEntity.status(409).body(result);
        try {
            if (!indexes.hasIndex(name)) {
                indexes.createIndex(name, null);
            }
            List<JVS> docs = extractDocs(body);
            indexes.indexManager().indexDocuments(name, docs);
            indexes.indexManager().commit(name);

            TypedKVStore<JsonNode> store = kv.openOrCreate(name);
            int stored = 0;
            if (store != null) {
                for (JVS d : docs) {
                    String key = buildKey(d);
                    if (key != null) { store.put(key, d.getJsonNode()); stored++; }
                }
            }
            result.put("indexed", docs.size());
            result.put("kvStored", stored);
            result.put("indexName", name);
            result.put("success", true);
        } catch (Exception e) {
            log.error("ingest failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/indexes/{name}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            indexes.refreshIndex(name);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private boolean ensureIngestAllowed(Map<String, Object> result) {
        if (!props.isAllowIngest()) {
            result.put("success", false);
            result.put("error", "Ingest disabled (hitorro.fleet.retrieval.allow-ingest=false)");
            return false;
        }
        if (props.getMode() == FleetRetrievalProperties.Mode.shared) {
            result.put("success", false);
            result.put("error", "Ingest refused in shared mode (indexes owned by pipeline writers). "
                    + "Set hitorro.fleet.retrieval.mode=standalone to accept writes here.");
            return false;
        }
        return true;
    }

    private static List<JVS> extractDocs(JsonNode body) {
        List<JVS> docs = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(n -> docs.add(new JVS(n)));
        } else if (body.has("documents") && body.get("documents").isArray()) {
            body.get("documents").forEach(n -> docs.add(new JVS(n)));
        } else {
            docs.add(new JVS(body));
        }
        return docs;
    }

    private static String buildKey(JVS doc) {
        JsonNode idNode = doc.getJsonNode() != null ? doc.getJsonNode().get("id") : null;
        if (idNode == null || idNode.isNull()) return null;
        if (idNode.isObject()) {
            JsonNode domain = idNode.get("domain");
            JsonNode did = idNode.get("did");
            if (domain != null && did != null) return domain.asText() + "/" + did.asText();
            if (did != null) return did.asText();
            return null;
        }
        String text = idNode.asText();
        return (text == null || text.isEmpty()) ? null : text;
    }
}
