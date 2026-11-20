#!/bin/bash
# Golden REST test for hitorro-fleet-retrieval, against responses recorded from main on Jackson 2
# (main/main-*.jsonl.gz: 5 runs of main @ 20412aa, HiTorro 3.x).
#
#   src/test/golden/run.sh <app.jar>        (HITORRO_CONFIG=<hitorro-all>/config if not a sibling checkout)
#
# Starts the app (standalone, offline, its own HOME), makes 57 requests (every controller: ingest
# index/documents/refresh, execute incl. search/facets/sort/lang and bad input, execute/stream NDJSON,
# search-multiple, remote search, documents, KV scan NDJSON, fields, status/health/env-check,
# summarize without Ollama, actuator, error bodies) and compares with the main recordings: byte for
# byte after masking run-dependent values (timestamps keep their format), order-insensitively where
# the order varies between main runs. Exit code 0 = identical.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
python3 "$here/app_golden.py" --cp "$1" --main-class com.hitorro.fleet.retrieval.FleetRetrievalApplication \
  --requests "$here/requests.py" --out "$work/new.jsonl" --work "$work/w" --env 'HT_BIN=<WORK>/bin'
python3 "$here/golden_compare.py" "$(ls "$here"/main/main-*.jsonl.gz | paste -sd, -)" "$work/new.jsonl"
