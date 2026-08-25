# Graph Database Cloud Benchmark (CognoDB Assessment)

This repository implements the home-assessment benchmark suite for comparing graph databases on a common dataset, common workload matrix, and reproducible reporting format.

The implementation currently runs a **4-database active suite** by default:
- CognoDB Cloud
- Neo4j AuraDB Free
- FalkorDB
- ArangoDB

TigerGraph is implemented and can be run explicitly (`--db tigergraph`), but it is excluded from `--all` in the current setup.

## Assessment Alignment

The codebase covers the expected benchmark components from the assessment:
- Dataset ingest benchmark (throughput + total load time)
- Read latency benchmarks (p50/p95) for:
  - 1-hop traversal
  - 2-hop traversal
  - 3-hop traversal
  - point lookup
  - filtered lookup
  - aggregation
- Mixed concurrent workload benchmark (80% reads / 20% writes) at multiple client levels
- Result export in both JSON and CSV

Primary orchestration and models:
- `src/main/java/ai/graphdb/benchmark/Main.java`
- `src/main/java/ai/graphdb/benchmark/benchmark/BenchmarkRunner.java`
- `src/main/java/ai/graphdb/benchmark/benchmark/GraphBenchmark.java`
- `src/main/java/ai/graphdb/benchmark/model/BenchmarkResult.java`
- `src/main/java/ai/graphdb/benchmark/report/CsvReporter.java`
- `src/main/java/ai/graphdb/benchmark/report/JsonReporter.java`

## Dataset

Benchmark dataset is SNAP Pokec (sampled for benchmark size), expected as:
- `data/pokec_nodes.csv`
- `data/pokec_edges.csv`

Current benchmark run uses:
- `50,000` nodes
- `28,027` relationships

## Workload Matrix

Workload categories:
- `INGEST`
- `HOP_1`, `HOP_2`, `HOP_3`
- `POINT_LOOKUP`
- `FILTERED_LOOKUP`
- `AGGREGATION`
- `MIXED_C1`, `MIXED_C10`, `MIXED_C40`

Default benchmark tuning (override via env vars):
- Warm-up iterations: `20`
- Measured iterations: `100`
- Mixed workload duration: `30s`
- Mixed workload ratio: `80%` read / `20%` write
- Concurrency levels: `1,10,40`

## Environment Configuration

Configuration is read from environment variables (or local `.env`):

Required for active default suite:
- `COGNODB_URI`, `COGNODB_PASSWORD` (optional `COGNODB_USER`)
- `NEO4J_AURA_URI`, `NEO4J_AURA_PASSWORD` (optional `NEO4J_AURA_USER`)
- `FALKORDB_HOST`, `FALKORDB_PORT` (optional `FALKORDB_USER`, `FALKORDB_PASSWORD`)
- `ARANGODB_HOST`, `ARANGODB_PASSWORD`

Optional:
- `TIGERGRAPH_HOST`, `TIGERGRAPH_TOKEN`, `TIGERGRAPH_GRAPH`
- `BENCH_WARMUP_ITERATIONS`, `BENCH_MEASURE_ITERATIONS`, `BENCH_CONCURRENCY_LEVELS`, `BENCH_TRAVERSAL_START_NODES`

## Build And Run

```powershell
cd D:\Karan_Practice_Java\congnodb-benchmark-new\cognodb-benchmark
mvn clean package
```

Download/prepare dataset:

```powershell
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --download
```

Run full active suite:

```powershell
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --all
```

Run a single database:

```powershell
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --db cognodb
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --db neo4j
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --db falkor
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --db arango
java -cp target\cognodb-benchmark-1.0.0.jar ai.graphdb.benchmark.Main --db tigergraph
```

Outputs are written to:
- `results/results_<timestamp>.json`
- `results/results_<timestamp>.csv`

## Final Run Highlights (From Assessment Result File)

Source:
- `results/results_2026-08-25_20-10-57.json`

Run metadata:
- Generated at: `2026-08-25T20:10:57.798618600`
- Total records: `40`

### Key Metrics Snapshot

| Database | Ingest Nodes/s | HOP_1 p50 (ms) | Aggregation p50 (ms) | Mixed C40 QPS | Notes |
|---|---:|---:|---:|---:|---|
| CognoDB Cloud | 289.77 | 492 | 634 | 74.33 | Mixed workload caveat notes free-tier throttling |
| Neo4j AuraDB Free | 741.00 | 261 | 288 | 145.60 | Stable mid-tier latency and throughput |
| FalkorDB | 1,325.45 | 1 | 13 | 645.83 | Best read latency across traversal/lookup/aggregation |
| ArangoDB | 13,027.62 | 3 | 88 | 11,061.77 | Best ingest and highest C40 mixed throughput |

### Winners By Workload Family

- **Ingest throughput (`nodes/sec`)**: ArangoDB (`13,027.62`)
- **Traversal latency (`HOP_1/HOP_2/HOP_3`, p50)**: FalkorDB (`1 ms`, `2 ms`, `1 ms`)
- **Point + filtered lookup latency (p50)**: FalkorDB (`1 ms`, `1 ms`)
- **Aggregation latency (p50)**: FalkorDB (`13 ms`)
- **Mixed throughput (`MIXED_C1`, `MIXED_C10`)**: FalkorDB (`930.40`, `271.00` qps)
- **Mixed throughput (`MIXED_C40`)**: ArangoDB (`11,061.77` qps)

## Caveats And Fairness Notes

- FalkorDB ingest is successful in this run; no ingest timeout failure is recorded in the latest result file.
- The suite compares managed cloud free tiers and self-hosted/local instances; results are best interpreted as practical platform outcomes, not pure engine-only microbenchmarks.
- Some footprint fields are intentionally reported as `not observable` where platform telemetry is unavailable.
- Mixed-workload caveats in the JSON include explicit throttling notes for CognoDB and single-writer behaviour notes for FalkorDB that should be carried into any formal assessment submission.

## Project Status

- Core benchmark framework is complete.
- Multi-database adapters and loaders are implemented under `src/main/java/ai/graphdb/benchmark/benchmark` and `src/main/java/ai/graphdb/benchmark/loader`.
- Reporting pipeline is complete (JSON + CSV).
- Assessment-ready results are available in `results/`.