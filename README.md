<p align="center">
  <img src="assets/logo.png" alt="Hitorro Fleet Retrieval logo" width="180">
</p>

# hitorro-fleet-retrieval

Spring Boot fleet member that hosts the full `hitorro-retrieval`
coordination runtime (Index → Document → Fixup → Pagination → Facet →
Summarization) with SearchSummary / Facet / Summarization aggregates
and multi-index merging. Deployable onto Orion mesh or Kubernetes.

## Modes

Selected via `hitorro.fleet.retrieval.mode`:

| mode | behavior |
| --- | --- |
| `standalone` | Owns its own Lucene + KV; ingest via REST at `/api/ingest`. |
| `shared`     | Reads pipeline-produced indexes at `${hitorro.pipelines.home}/lucene/*` and KV at `${hitorro.pipelines.home}/kv/*`. Mesh writes, retrieval reads. |

## Endpoints

### Search

- `POST /api/retrieval/execute` — full coordinator with JVS query
- `POST /api/retrieval/execute/stream` — same, unified NDJSON stream
- `POST /api/retrieval/search-multiple` — cross-index federation w/ mergers
- `GET  /api/retrieval/search?index=…&q=…` — end-user shorthand
- `GET  /api/retrieval/documents/{index}/{key}` — one KV doc
- `GET  /api/retrieval/fields/{index}` — Lucene FieldInfos

### Summarization

- `GET  /api/retrieval/summary-styles` — enumerate registered styles
- `POST /api/retrieval/summarize` — summarize pre-assembled text

The summarize stage on `/execute` also honours `summarize.style` /
`summarize.prompt`. Full reference:
[hitorro-retrieval/docs/summarization.md](../hitorro-retrieval/docs/summarization.md).

Toggle Ollama on/off:

```yaml
hitorro:
  fleet:
    retrieval:
      ollama:
        enabled: true                      # false disables all AI paths
        url:     http://localhost:11434
        model:   llama3
        timeout-seconds: 120
```

When `enabled=false` or Ollama is unreachable at boot:

- `AIOperations.isAvailable()` returns false
- The summarize stage silently no-ops on `/execute` requests
- `POST /api/retrieval/summarize` returns `503 Service Unavailable`

### Ingest (standalone only)

- `POST /api/ingest/indexes` — create index (name + typeJsonResource)
- `POST /api/ingest/indexes/{name}/documents` — add doc(s)

## Build + run

```bash
cd hitorro-fleet-retrieval
mvn clean install -DskipTests
HT_BIN=~/hitorro java -jar target/hitorro-fleet-retrieval-3.0.1.jar \
  --server.port=8095 \
  --hitorro.fleet.retrieval.mode=shared \
  --hitorro.fleet.retrieval.pipelines-home=$HOME/.hitorro/pipelines
```

## Health

- `GET /api/retrieval/health` — `{status, mode, indexes, kvStores}`
- `GET /actuator/health` — Spring Boot standard
- `GET /actuator/prometheus` — Micrometer metrics
