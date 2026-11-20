# Request list for hitorro-fleet-retrieval (standalone mode, offline).
import os, shutil

# hitorro-all's config/ (types): $HITORRO_CONFIG, or a hitorro-all checkout next to this repo
CONFIG = os.environ.get('HITORRO_CONFIG') or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..', 'hitorro-all', 'config')

def setup(work):
    # JsonTypeSystem reads $HT_BIN/config/types: a copy of hitorro-all's config
    shutil.copytree(CONFIG, os.path.join(work, 'bin', 'config'))

def ARGS(work):
    return ['--hitorro.fleet.retrieval.mode=standalone', '--hitorro.fleet.retrieval.ollama.enabled=false',
            f'--hitorro.fleet.retrieval.standalone-lucene-home={work}/lucene',
            f'--hitorro.fleet.retrieval.standalone-kv-home={work}/kv',
            f'--hitorro.fleet.retrieval.pipelines-home={work}/pipelines']

def doc(did, title, author, dept, size, kw, lang='en'):
    return {"id": {"domain": "test", "did": did}, "filename": title, "file_type": "pdf", "file_size": size,
            "author": author, "department": dept, "keywords": kw, "classification": "internal",
            "content": {"mls": [{"lang": lang, "text": title + " body text about " + kw}]}}

DOCS = [doc("d1", "Quarterly report", "ada", "eng", 210, "finance"),
        doc("d2", "Roadmap été", "bob", "eng", 12345678901, "planning"),
        doc("d3", "Hiring plan", "ada", "hr", 3, "people"),
        doc("d4", "Rapport annuel", "cy", "sales", 512, "finance", "fr")]

def requests(work):
    q = lambda name, query, **kw: ('POST', '/api/retrieval/execute', dict({"indexName": name, "query": query}, **kw))
    return [
      ('GET', '/actuator/health', None), ('GET', '/actuator/info', None), ('GET', '/actuator/health/liveness', None),
      ('GET', '/api/retrieval/health', None), ('GET', '/api/retrieval/status', None), ('GET', '/api/retrieval/indexes', None),
      ('GET', '/api/retrieval/env-check', None), ('GET', '/api/retrieval/summary-styles', None),
      ('POST', '/api/ingest/indexes', {"name": "docs", "typeName": "demo_document"}),
      ('POST', '/api/ingest/indexes', {"name": ""}),
      ('POST', '/api/ingest/indexes', {"name": "docs2", "typeName": "no_such_type"}),
      ('POST', '/api/ingest/indexes/docs/documents', DOCS),
      ('POST', '/api/ingest/indexes/docs/documents', {"documents": [doc("d5", "Extra", "dee", "ops", 0, "misc")]}),
      ('POST', '/api/ingest/indexes/docs/documents', '{"documents": "not-an-array"}'),
      ('POST', '/api/ingest/indexes/nope/documents', DOCS[:1]),
      ('POST', '/api/ingest/indexes/docs/refresh', None), ('POST', '/api/ingest/indexes/nope/refresh', None),
      ('SLEEP', '1', None),
      ('GET', '/api/retrieval/indexes', None), ('GET', '/api/retrieval/status', None),
      ('GET', '/api/retrieval/fields/docs', None), ('GET', '/api/retrieval/fields/nope', None),
      q("docs", {"search": {"query": "*:*"}}), q("docs", {"search": {"query": "finance"}}),
      q("docs", {"search": {"query": "keywords:finance"}}), q("docs", {"search": {"query": "department:eng", "offset": 1, "limit": 1}}),
      q("docs", {"search": {"query": "*:*", "facets": ["department", "author"]}}),
      q("docs", {"search": {"query": "*:*", "sort": [{"field": "file_size", "direction": "desc"}]}}),
      q("docs", {"search": {"query": "*:*", "sort": "author:asc,file_size:desc"}}),
      q("docs", {"search": {"query": "annuel", "lang": "fr"}}), q("docs", {"search": {"query": "((("}}),
      q("nope", {"search": {"query": "*:*"}}), q("docs", {"q": "*:*"}),
      ('POST', '/api/retrieval/execute', {"query": {"q": "*:*"}}),
      ('POST', '/api/retrieval/execute', '{bad json'),
      ('NDJSON', '/api/retrieval/execute/stream', {"indexName": "docs", "query": {"search": {"query": "*:*", "facets": ["department"]}}}),
      ('NDJSON', '/api/retrieval/execute/stream', {"indexName": "nope", "query": {"search": {"query": "*:*"}}}),
      ('POST', '/api/retrieval/search-multiple', {"indexNames": ["docs"], "query": "finance", "limit": 5}),
      ('POST', '/api/retrieval/search-multiple', {"indexNames": ["docs"], "query": "*:*", "facets": "department", "merger": "score", "offset": 0, "limit": 10}),
      ('POST', '/api/retrieval/search-multiple', {"indexNames": ["docs"], "query": "*:*", "sort": "file_size:desc"}),
      ('POST', '/api/retrieval/search-multiple', {"query": "x"}),
      ('GET', '/api/retrieval/search?index=docs&q=finance', None), ('GET', '/api/retrieval/search?index=docs&q=department:eng', None), ('GET', '/api/retrieval/search?index=docs&q=finance&offset=1&limit=1', None),
      ('GET', '/api/retrieval/search?index=nope&q=x', None), ('GET', '/api/retrieval/search?index=docs', None),
      ('GET', '/api/retrieval/documents/docs/test%2Fd1', None), ('GET', '/api/retrieval/documents/docs/d1', None),
      ('GET', '/api/retrieval/documents/docs/missing', None), ('GET', '/api/retrieval/documents/test%2Fd2', None),
      ('GET', '/api/retrieval/documents/nope', None),
      ('GETNDJSON', '/api/retrieval/kv/docs/scan', None), ('GETNDJSON', '/api/retrieval/kv/docs/scan?prefix=test%2Fd', None),
      ('GETNDJSON', '/api/retrieval/kv/nope/scan', None),
      ('POST', '/api/retrieval/summarize', {"text": "hello world", "style": "brief"}),
      ('POST', '/api/retrieval/summarize', {"text": ""}), ('POST', '/api/retrieval/summarize', '{"style": 5, "maxWords": "x"}'),
      ('GET', '/no/such/path', None),
    ]
