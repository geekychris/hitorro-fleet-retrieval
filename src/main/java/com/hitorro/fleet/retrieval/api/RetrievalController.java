/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hitorro.fleet.retrieval.FleetCoordinatorService;
import com.hitorro.fleet.retrieval.FleetIndexService;
import com.hitorro.fleet.retrieval.FleetKvService;
import com.hitorro.fleet.retrieval.FleetRetrievalProperties;
import com.hitorro.index.search.SearchResult;
import com.hitorro.jsontypesystem.JVS;
import com.hitorro.kvstore.TypedKVStore;
import com.hitorro.retrieval.RetrievalResult;
import com.hitorro.retrieval.context.ContextAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the fleet-retrieval coordination runtime.
 * Mirrors the {@code /api/retrieval/*} shape from
 * {@code hitorro-jvs-example-springboot} so existing clients port over
 * unchanged, minus the dev-time features (viewer, analyzer builder,
 * transport toggle) that belong in a dev tool, not a fleet service.
 */
@RestController
@RequestMapping("/api/retrieval")
@CrossOrigin(origins = "*")
public class RetrievalController {

    private static final Logger log = LoggerFactory.getLogger(RetrievalController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FleetCoordinatorService coordinator;
    private final FleetIndexService indexes;
    private final FleetKvService kv;
    private final FleetRetrievalProperties props;

    public RetrievalController(FleetCoordinatorService coordinator,
                               FleetIndexService indexes,
                               FleetKvService kv,
                               FleetRetrievalProperties props) {
        this.coordinator = coordinator;
        this.indexes = indexes;
        this.kv = kv;
        this.props = props;
    }

    // ─── Health & Status ────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "mode", props.getMode().name(),
                "indexes", indexes.listIndexes().size(),
                "kvStores", kv.listStores().size()));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("mode", props.getMode().name());
        s.put("luceneRoot", props.luceneRoot().toString());
        s.put("kvRoot", props.kvRoot().toString());
        s.put("kvEnabled", props.isKvEnabled());
        s.put("allowIngest", props.isAllowIngest());
        List<Map<String, Object>> details = new ArrayList<>();
        for (String name : indexes.listIndexes()) details.add(indexes.describe(name));
        s.put("indexes", details);
        return ResponseEntity.ok(s);
    }

    @GetMapping("/indexes")
    public ResponseEntity<List<Map<String, Object>>> listIndexes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : indexes.listIndexes()) list.add(indexes.describe(name));
        return ResponseEntity.ok(list);
    }

    // ─── Single-index execute ───────────────────────────────────────

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody JsonNode body) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String indexName = requireString(body, "indexName");
            JsonNode queryNode = body.has("query") ? body.get("query")
                    : JsonNodeFactory.instance.objectNode();
            String lang = body.has("lang") ? body.get("lang").asText() : null;

            RetrievalResult rr = coordinator.execute(indexName, new JVS(queryNode), lang);
            populate(result, rr, queryNode);
            result.put("success", true);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("totalTimeMs", System.currentTimeMillis() - start);
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            log.error("execute failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        result.put("totalTimeMs", System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    // ─── Streaming execute (NDJson unified stream) ──────────────────

    @PostMapping(value = "/execute/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> executeStream(@RequestBody JsonNode body) {
        StreamingResponseBody streamBody = out -> {
            try {
                String indexName = requireString(body, "indexName");
                JsonNode queryNode = body.has("query") ? body.get("query")
                        : JsonNodeFactory.instance.objectNode();
                String lang = body.has("lang") ? body.get("lang").asText() : null;

                RetrievalResult rr = coordinator.execute(indexName, new JVS(queryNode), lang);
                var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);

                SearchResult sr = rr.getSearchResult();
                if (sr != null) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("_type", "meta");
                    meta.put("totalHits", sr.getTotalHits());
                    meta.put("searchTimeMs", sr.getSearchTimeMs());
                    writer.write(JSON.writeValueAsString(meta));
                    writer.write('\n');
                    writer.flush();
                }
                var unified = rr.unifiedStream();
                while (unified.hasNext()) {
                    JVS item = unified.next();
                    writer.write(JSON.writeValueAsString(item.getJsonNode()));
                    writer.write('\n');
                    writer.flush();
                }
            } catch (Exception e) {
                log.error("stream execute failed", e);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(streamBody);
    }

    // ─── Multi-index search (Composite + merger) ────────────────────

    @PostMapping("/search-multiple")
    public ResponseEntity<Map<String, Object>> searchMultiple(@RequestBody JsonNode body) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<String> indexNames = new ArrayList<>();
            if (body.has("indexNames") && body.get("indexNames").isArray()) {
                body.get("indexNames").forEach(n -> indexNames.add(n.asText()));
            }
            if (indexNames.isEmpty()) {
                result.put("success", false);
                result.put("error", "indexNames required (array)");
                return ResponseEntity.badRequest().body(result);
            }
            String q = body.has("query") ? body.get("query").asText() : "*:*";
            int offset = body.has("offset") ? body.get("offset").asInt() : 0;
            int limit = body.has("limit") ? body.get("limit").asInt() : 20;
            String lang = body.has("lang") ? body.get("lang").asText() : null;
            String merger = body.has("merger") ? body.get("merger").asText() : "score";
            List<String> facets = null;
            if (body.has("facets") && body.get("facets").isTextual() && !body.get("facets").asText().isBlank()) {
                facets = List.of(body.get("facets").asText().split(","));
            }

            SearchResult sr = coordinator.searchMultiple(indexNames, q, offset, limit, facets, lang, merger);

            List<JVS> docs = sr.getDocuments();
            result.put("documents", docs.stream().map(JVS::getJsonNode).toList());
            result.put("documentCount", docs.size());
            result.put("totalHits", sr.getTotalHits());
            result.put("searchTimeMs", sr.getSearchTimeMs());
            result.put("indexNames", indexNames);
            result.put("merger", merger);
            if (sr.hasFacets()) result.put("facets", facetsToMap(sr));
            result.put("success", true);
        } catch (Exception e) {
            log.error("search-multiple failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        result.put("totalTimeMs", System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    // ─── KV fetch by key (for a specific index's KV) ────────────────

    @GetMapping("/documents/{indexName}/{key}")
    public ResponseEntity<JsonNode> fetchDocument(@PathVariable String indexName,
                                                  @PathVariable String key) {
        TypedKVStore<JsonNode> store = kv.get(indexName);
        if (store == null) return ResponseEntity.notFound().build();
        var res = store.get(key);
        if (res.isSuccess() && res.getValue().isPresent()) {
            return ResponseEntity.ok(res.getValue().get());
        }
        return ResponseEntity.notFound().build();
    }

    /** Legacy /documents/{key} — fetch from the first KV store that has it. */
    @GetMapping("/documents/{key}")
    public ResponseEntity<JsonNode> fetchDocumentAny(@PathVariable String key) {
        for (String name : kv.listStores()) {
            TypedKVStore<JsonNode> store = kv.get(name);
            if (store == null) continue;
            var res = store.get(key);
            if (res.isSuccess() && res.getValue().isPresent()) return ResponseEntity.ok(res.getValue().get());
        }
        return ResponseEntity.notFound().build();
    }

    /** Report the type-system config resolution so we can spot missing HT_BIN etc. */
    @GetMapping("/env-check")
    public ResponseEntity<Map<String, Object>> envCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("systemHtBin", System.getProperty("HT_BIN"));
        out.put("systemHtBinLower", System.getProperty("ht.bin"));
        out.put("envHtBin", System.getenv("HT_BIN"));
        out.put("userDir", System.getProperty("user.dir"));
        try {
            var lfts = com.hitorro.index.config.LuceneFieldTypes.getInstance();
            java.lang.reflect.Field mf = com.hitorro.index.config.LuceneFieldTypes.class.getDeclaredField("map");
            mf.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> m = (Map<String, ?>) mf.get(lfts);
            out.put("luceneFieldTypeCount", m.size());
            out.put("luceneFieldTypeNames", m.keySet());
        } catch (Exception e) {
            out.put("luceneFieldTypesError", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    // ─── Debug — physical Lucene field inventory ────────────────────

    /**
     * Enumerate every Lucene FieldInfo in the named index — physical
     * name + term count + sample. Lets a caller figure out how the
     * type-aware writer projected each JVS field, without cracking the
     * on-disk segments by hand.
     */
    @GetMapping("/fields/{indexName}")
    public ResponseEntity<Map<String, Object>> fields(@PathVariable("indexName") String indexName) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!indexes.hasIndex(indexName)) return ResponseEntity.notFound().build();
            org.apache.lucene.index.DirectoryReader reader;
            boolean weOpenedIt;
            if (indexes.isReadOnly()) {
                // Shared mode — open a fresh DirectoryReader on demand, no
                // write.lock. Snapshot introspection only.
                reader = org.apache.lucene.index.DirectoryReader.open(
                        org.apache.lucene.store.FSDirectory.open(indexes.pathOf(indexName)));
                weOpenedIt = true;
            } else {
                var handle = indexes.indexManager().getIndex(indexName);
                if (handle == null) return ResponseEntity.notFound().build();
                reader = org.apache.lucene.index.DirectoryReader.open(handle.getWriter().getIndexWriter());
                weOpenedIt = true;
            }
            try {
                var fis = org.apache.lucene.index.FieldInfos.getMergedFieldInfos(reader);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var fi : fis) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", fi.name);
                    row.put("indexed", fi.getIndexOptions() != org.apache.lucene.index.IndexOptions.NONE);
                    row.put("docValuesType", fi.getDocValuesType().name());
                    row.put("pointDimensionCount", fi.getPointDimensionCount());
                    List<String> samples = new ArrayList<>();
                    try {
                        var terms = org.apache.lucene.index.MultiTerms.getTerms(reader, fi.name);
                        if (terms != null) {
                            var te = terms.iterator();
                            org.apache.lucene.util.BytesRef t;
                            while ((t = te.next()) != null && samples.size() < 5) samples.add(t.utf8ToString());
                        }
                    } catch (Exception ignore) {}
                    row.put("sampleTerms", samples);
                    rows.add(row);
                }
                out.put("indexName", indexName);
                out.put("numDocs", reader.numDocs());
                out.put("fields", rows);
            } finally {
                if (weOpenedIt) try { reader.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    // ─── Wire-transport support for RemoteSearchProvider clients ────

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> remoteSearch(
            @RequestParam String index,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "en") String lang,
            @RequestParam(required = false) String facets) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<String> facetDims = (facets != null && !facets.isBlank())
                    ? List.of(facets.split(",")) : null;
            SearchResult sr = coordinator.executeRaw(index, q, offset, limit, facetDims, lang);
            result.put("documents", sr.getDocuments().stream().map(JVS::getJsonNode).toList());
            result.put("totalHits", sr.getTotalHits());
            result.put("searchTimeMs", sr.getSearchTimeMs());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static String requireString(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return body.get(field).asText();
    }

    private static void populate(Map<String, Object> result, RetrievalResult rr, JsonNode queryNode) {
        List<JVS> docs = rr.getDocumentList();
        List<JVS> aggregates = rr.getAggregates();
        result.put("documents", docs.stream().map(JVS::getJsonNode).toList());
        result.put("documentCount", docs.size());

        List<JsonNode> aggNodes = new ArrayList<>();
        for (JVS agg : aggregates) if (agg != null) aggNodes.add(agg.getJsonNode());
        result.put("aggregates", aggNodes);

        if (rr.hasErrors()) result.put("errors", rr.getErrors());

        SearchResult sr = rr.getSearchResult();
        if (sr != null) {
            result.put("totalHits", sr.getTotalHits());
            result.put("searchTimeMs", sr.getSearchTimeMs());
            if (sr.hasFacets()) result.put("facets", facetsToMap(sr));
        }

        var ctx = rr.getContext();

        // Derive stagesUsed from what the stages actually reported to the
        // context where possible, NOT from what the query asked for. Two
        // stages (DocumentRetriever, SummarizationRetriever) set context
        // attributes when they run, so we can flag them accurately. Fixup
        // and Pagination don't yet have dedicated attrs — asked-for is the
        // best signal we have, tagged with "?" so users know it may not
        // have participated (e.g. type tags that matched nothing).
        List<String> stages = new ArrayList<>();
        stages.add("IndexRetriever");
        if (ctx.hasAttribute(ContextAttributes.DOCUMENT_STORE_TYPE)) stages.add("DocumentRetriever");
        if (queryNode != null && queryNode.has("fixup")) stages.add("FixupRetriever?");
        if (queryNode != null && queryNode.has("page"))  stages.add("PaginationRetriever?");
        if (sr != null && sr.hasFacets()) stages.add("FacetRetriever");
        if (ctx.hasAttribute(ContextAttributes.AI_SUMMARY)) stages.add("SummarizationRetriever");
        result.put("stagesUsed", stages);

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (ctx.hasAttribute(ContextAttributes.SEARCH_PROVIDERS))
            attrs.put("searchProvider", ctx.getAttribute(ContextAttributes.SEARCH_PROVIDERS, String.class));
        if (ctx.hasAttribute(ContextAttributes.TOTAL_HITS))
            attrs.put("totalHits", ctx.getAttribute(ContextAttributes.TOTAL_HITS, Long.class));
        if (ctx.hasAttribute(ContextAttributes.DOCUMENT_STORE_TYPE))
            attrs.put("documentStore", ctx.getAttribute(ContextAttributes.DOCUMENT_STORE_TYPE, String.class));
        if (ctx.hasAttribute(ContextAttributes.AI_SUMMARY))
            attrs.put("aiSummary", ctx.getAttribute(ContextAttributes.AI_SUMMARY, String.class));
        if (!attrs.isEmpty()) result.put("contextAttributes", attrs);
    }

    private static Map<String, Object> facetsToMap(SearchResult sr) {
        Map<String, Object> facetMap = new LinkedHashMap<>();
        sr.getFacets().forEach((dim, fr) -> {
            List<Map<String, Object>> values = new ArrayList<>();
            fr.getValues().forEach(fv -> values.add(Map.of("value", fv.getValue(), "count", fv.getCount())));
            facetMap.put(dim, Map.of("values", values, "totalCount", fr.getTotalCount()));
        });
        return facetMap;
    }
}
