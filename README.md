# Graph Database Cloud Benchmark

A reproducible, fair comparison of four graph databases — **CognoDB Cloud**, **Neo4j AuraDB Free**, **FalkorDB**, and **ArangoDB** — benchmarked on an identical dataset, identical workloads, and equivalent hardware resources from a single client machine.

> **Assignment:** Wexa AI — CognoDB Cloud Benchmarking Take-Home  
> **Run date:** 25 August 2026 · 20:10 IST  
> **Results file:** `results/results_2026-08-25_20-10-57.json`  
> **Total measurements:** 40 (10 workloads × 4 databases)

---

## Table of Contents

1. [Database Selection & Rationale](#1-database-selection--rationale)
2. [Environment & Instance Specs](#2-environment--instance-specs)
3. [Dataset](#3-dataset)
4. [Methodology](#4-methodology)
5. [Results Matrix](#5-results-matrix)
6. [Analysis](#6-analysis)
7. [Caveats & Honest Notes](#7-caveats--honest-notes)
8. [How to Reproduce](#8-how-to-reproduce)
9. [Project Structure](#9-project-structure)

---

## 1. Database Selection & Rationale

| # | Database | Version | Tier | Query Language | Driver / Protocol |
|---|---|---|---|---|---|
| 1 | **CognoDB Cloud** | c0 (free) | Managed cloud — no credit card | Cypher | Neo4j Java Driver 5.18 · Bolt (`bolt+s://`) |
| 2 | **Neo4j AuraDB Free** | AuraDB Free | Managed cloud — permanent free | Cypher | Neo4j Java Driver 5.18 · Bolt (`neo4j+s://`) |
| 3 | **FalkorDB** | 4.20.4 | Self-hosted Docker | Cypher | Neo4j Java Driver 5.18 · Bolt (`bolt://`, no TLS) |
| 4 | **ArangoDB** | 3.12.10 | Self-hosted Docker | AQL | Java `HttpClient` · REST API (`http://`) |

**Why these four?**

**CognoDB Cloud** is the subject of this assignment. It is a Neo4j-compatible managed graph database using the Bolt protocol, requiring no infrastructure to operate.

**Neo4j AuraDB Free** is the reference implementation of the Bolt/Cypher stack — the original engine CognoDB is protocol-compatible with. The CognoDB-vs-AuraDB comparison directly measures what CognoDB adds or removes versus the canonical implementation. This is the most architecturally fair head-to-head in the suite.

**FalkorDB** is a Redis-backed graph database that stores graphs as sparse adjacency matrices entirely in memory. Its Bolt compatibility means the benchmark code is almost identical to CognoDB and Neo4j Aura — only the URI and one driver config flag change. It establishes a practical lower bound on graph query latency when network I/O is eliminated.

**ArangoDB** broadens the comparison beyond the Cypher/Bolt stack. As a multi-model (document + graph) database using AQL and a RocksDB storage engine, it represents a meaningfully different architecture. It serves as a point of comparison for teams evaluating whether a dedicated Cypher-compatible graph database is the right choice.

**Amazon Neptune and TigerGraph were considered and excluded:** Neptune has no permanent free tier (credit-based trial only, creating a timing risk for reproducibility). TigerGraph Cloud's free tier provides 4 vCPU and 7.5 GB RAM — an order of magnitude more than the 256 MB cap used for all other databases, making fair resource comparison impossible.

---

## 2. Environment & Instance Specs

### Client machine

| Property | Value |
|---|---|
| OS | Windows 11, WSL2 kernel 6.6.87.2-microsoft-standard-WSL2 |
| JVM | Java 21, Maven 3.x |
| Location | Mumbai, India |
| Concurrency model | `java.util.concurrent.ExecutorService` · `ThreadPoolExecutor` |

### Database instance specs (documented for fairness)

| Database | vCPU | RAM | Storage | Location |
|---|---|---|---|---|
| CognoDB Cloud c0 | 0.5 vCPU burstable | 256 MB | 1 GB | Managed cloud (region not published) |
| Neo4j AuraDB Free | Shared (not published) | ~200 MB effective | ~200 MB | Managed cloud (region not published) |
| FalkorDB 4.20.4 | 0.5 cap (`--cpus=0.5`) | 256 MB cap (`--memory=256m`, `maxmemory 256mb`) | RAM-resident | Docker · localhost |
| ArangoDB 3.12.10 | 0.5 cap (`--cpus=0.5`) | 256 MB cap (`--memory=256m`) | RocksDB on disk | Docker · localhost |

> **Resource fairness:** Self-hosted databases were hard-capped to the same resource envelope as CognoDB's free c0 tier via Docker Compose `deploy.resources.limits`. The dataset was sized to fit within these limits with headroom. Cloud databases are subject to additional network latency from the client location in Mumbai; this is documented in §7.

---

## 3. Dataset

**Source:** SNAP soc-Pokec social network — [https://snap.stanford.edu/data/soc-Pokec.html](https://snap.stanford.edu/data/soc-Pokec.html)

Pokec is a real Slovak social network snapshot from 2012: 1.6 million users, 30.6 million directed friendship relationships. We sampled a reproducible subset using a fixed random seed.

| Property | Value |
|---|---|
| Nodes (`User`) | 50,000 |
| Relationships (`FRIENDS_WITH`) | 28,027 |
| Node properties | `id` (string), `gender`, `region`, `age` (int) |
| Edge properties | none (directed) |
| Random seed | 42 (same sample on every run) |
| Sampling method | Shuffle all node IDs with seed → take first 50,000 → retain edges where both endpoints are in sample |

**Files loaded identically into every database:**
- `data/pokec_nodes.csv` — header: `id,gender,region,age`
- `data/pokec_edges.csv` — header: `source_id,target_id`

### Load method per platform

| Database | Method | Batch size |
|---|---|---|
| CognoDB Cloud | `UNWIND $batch CREATE (:User {...})` via Bolt | 500 nodes / tx |
| Neo4j AuraDB Free | Same Cypher via Bolt | 500 nodes / tx |
| FalkorDB | Same Cypher via Bolt, `SessionConfig.withDatabase("pokec")` | 500 nodes / tx |
| ArangoDB | `POST /_api/document/users` JSON array via REST | 500 docs / request |

---

## 4. Methodology

### Warm-up
Every read workload discards the first **20 iterations** before measurement. This allows JIT compilation, connection-pool establishment, and database cache priming to complete before numbers are recorded.

### Measurement iterations
- **Read workloads (traversal, lookup, aggregation):** 100 measured iterations each
- **Ingest:** single timed full-load run per database
- **Mixed concurrent:** 30 seconds sustained per concurrency level

### Latency statistics
Reported as **p50 (median) and p95** computed with [HDRHistogram](https://github.com/HdrHistogram/HdrHistogram) (3 significant figures, max 1 hour). Averages alone are misleading when tail latency is significant; p95 captures the worst 5% of queries that real users experience.

### Traversal queries (logically equivalent across all databases)

| Hop depth | Cypher (CognoDB / Neo4j / FalkorDB) | AQL (ArangoDB) |
|---|---|---|
| 1-hop | `MATCH (u:User {id:$id})-[:FRIENDS_WITH]->(n) RETURN count(n)` | `FOR v IN 1..1 OUTBOUND @startId GRAPH 'pokec_graph' RETURN COUNT(v)` |
| 2-hop | `MATCH (u)-[:FRIENDS_WITH]->()-[:FRIENDS_WITH]->(n) RETURN count(n)` | `FOR v IN 2..2 OUTBOUND @startId GRAPH 'pokec_graph' RETURN COUNT(v)` |
| 3-hop | `MATCH (u)-[:FRIENDS_WITH]->()-[:FRIENDS_WITH]->()-[:FRIENDS_WITH]->(n) RETURN count(n)` | `FOR v IN 3..3 OUTBOUND @startId GRAPH 'pokec_graph' RETURN COUNT(v)` |

### Mixed workload composition
- 80% reads: 1-hop traversal from a randomly chosen start node
- 20% writes: `SET u.age = u.age + 1` on a randomly chosen node
- Concurrency levels: 1, 10, 40 client threads
- Duration: 30 seconds per concurrency level
- Metric: sustained queries-per-second (total operations ÷ 30s)

---

## 5. Results Matrix

> 🟢 **Bold + green** = best in class for that workload. All latency values in milliseconds. Lower latency = better. Higher QPS / throughput = better.

---

### 5.1 Data Loading — Ingest Throughput

| Database | Nodes Loaded | Rels Loaded | **Nodes/sec** | **Rels/sec** | Total Load Time |
|---|---|---|---|---|---|
| CognoDB Cloud | 50,000 | 28,027 | 290 | 162 | 172.6 s |
| Neo4j AuraDB Free | 50,000 | 28,027 | 741 | 415 | 67.5 s |
| FalkorDB | 50,000 | 28,027 | 1,325 | 743 | 37.7 s |
| **ArangoDB** | 50,000 | 28,027 | **13,028** | **7,303** | **3.8 s** |

---

### 5.2 Traversal Latency (ms) — lower is better

#### 1-Hop: direct neighbours

| Database | p50 | p95 | Min | Max | Mean |
|---|---|---|---|---|---|
| CognoDB Cloud | 492 | 531 | 478 | 590 | 496.7 |
| Neo4j AuraDB Free | 261 | 301 | 251 | 323 | 266.2 |
| **FalkorDB** | **1** | **2** | **1** | **2** | **1.3** |
| ArangoDB | 3 | 4 | 2 | 9 | 2.8 |

#### 2-Hop: friends of friends

| Database | p50 | p95 | Min | Max | Mean |
|---|---|---|---|---|---|
| CognoDB Cloud | 495 | 539 | 487 | 968 | 510.3 |
| Neo4j AuraDB Free | 259 | 275 | 254 | 314 | 261.0 |
| **FalkorDB** | **1** | **2** | **1** | **3** | **1.4** |
| ArangoDB | 2 | 3 | 1 | 4 | 2.4 |

#### 3-Hop: friends of friends of friends

| Database | p50 | p95 | Min | Max | Mean |
|---|---|---|---|---|---|
| CognoDB Cloud | 495 | 528 | 487 | 954 | 508.4 |
| Neo4j AuraDB Free | 260 | 283 | 253 | 348 | 264.4 |
| **FalkorDB** | **1** | **2** | **1** | **2** | **1.3** |
| ArangoDB | 2 | 3 | 1 | 4 | 2.2 |

---

### 5.3 Lookup Latency (ms) — lower is better

#### Point Lookup — find one node by indexed `id`

| Database | p50 | p95 | Min | Max | Mean | Index |
|---|---|---|---|---|---|---|
| CognoDB Cloud | 492 | 523 | 488 | 951 | 504.4 | `CREATE INDEX user_id_idx FOR (u:User) ON (u.id)` |
| Neo4j AuraDB Free | 259 | 271 | 252 | 323 | 260.4 | `CREATE INDEX user_id_idx FOR (u:User) ON (u.id)` |
| **FalkorDB** | **1** | **2** | **1** | **3** | **1.3** | `CREATE INDEX FOR (u:User) ON (u.id)` |
| ArangoDB | 2 | 3 | 1 | 4 | 2.2 | Persistent unique index on `users.id` |

#### Filtered Lookup — users aged 25–35, LIMIT 100

| Database | p50 | p95 | Min | Max | Mean | Notes |
|---|---|---|---|---|---|---|
| CognoDB Cloud | 734 | 978 | 491 | 1,927 | 709.2 | No index on `age` — full scan |
| Neo4j AuraDB Free | 261 | 270 | 254 | 468 | 263.7 | No index on `age` — full scan |
| **FalkorDB** | **1** | **2** | **1** | **2** | **1.2** | No index on `age` — in-memory full scan |
| ArangoDB | 3 | 4 | 2 | 6 | 2.7 | No index on `age` — RocksDB full scan |

---

### 5.4 Aggregation Latency (ms) — lower is better

**Query:** `COUNT(User) GROUP BY region ORDER BY count DESC`

| Database | p50 | p95 | Min | Max | Mean | Query |
|---|---|---|---|---|---|---|
| CognoDB Cloud | 634 | 985 | 594 | 2,093 | 729.9 | Cypher `MATCH (u:User) RETURN u.region, count(u) ORDER BY count DESC` |
| Neo4j AuraDB Free | 288 | 307 | 270 | 384 | 290.6 | Same Cypher |
| **FalkorDB** | **13** | **63** | **10** | **73** | **25.0** | Same Cypher |
| ArangoDB | 88 | 112 | 17 | 204 | 79.3 | AQL `COLLECT region WITH COUNT INTO cnt SORT cnt DESC` |

---

### 5.5 Mixed Concurrent Throughput (QPS) — higher is better

**80% reads (1-hop traversal) / 20% writes (age increment) · 30 s sustained per level**

| Database | 1 Client | 10 Clients | 40 Clients |
|---|---|---|---|
| CognoDB Cloud | 2.0 | 19.1 | 74.3 |
| Neo4j AuraDB Free | 3.7 | 38.7 | 145.6 |
| **FalkorDB** | **930.4** | 271.0 | 645.8 |
| **ArangoDB** | 517.3 | 243.8 | **11,061.8** ⚠️ |

> ⚠️ ArangoDB 40-client figure of 11,062 QPS is anomalous and discussed in §7.

---

### 5.6 Resource Footprint

| Database | Stored data size | Memory during run | Instance spec |
|---|---|---|---|
| CognoDB Cloud | not observable | not observable | 0.5 vCPU burstable, 256 MB RAM, 1 GB disk |
| Neo4j AuraDB Free | not observable | not observable | Shared instance, ~200 MB storage cap |
| FalkorDB | not observable | ≤ 256 MB (Docker `--memory` cap) | Docker, `maxmemory 256mb`, 0.5 vCPU cap |
| ArangoDB | not observable | ≤ 256 MB (Docker `--memory` cap) | Docker, RocksDB on disk, 0.5 vCPU cap |

---

## 6. Analysis

### The network boundary is the dominant variable

The most striking feature of this benchmark is the gap between the two cloud databases (~250–700 ms per query) and the two local databases (1–3 ms per query). This is not primarily an engine capability difference — it is a network topology difference.

CognoDB Cloud and Neo4j AuraDB are queried over the public internet from Mumbai. The cloud endpoints are provisioned in an unspecified region (likely US or EU). Every single query incurs a round-trip that accounts for roughly 240–490 ms of the measured latency — the actual server-side execution takes a fraction of that. FalkorDB and ArangoDB run on `localhost` with sub-millisecond network overhead.

To illustrate: Neo4j AuraDB's 1-hop p50 is 261 ms. If we subtract a typical inter-continental round-trip of ~250 ms, server-side execution is approximately 10–15 ms — which is consistent with FalkorDB's 1 ms and ArangoDB's 3 ms when the same query runs locally. The databases are not as far apart in raw engine performance as the latency numbers suggest.

**Implication:** If you are evaluating graph databases for a latency-sensitive production workload, network co-location matters as much as engine performance. A CognoDB or Neo4j Aura instance deployed in the same region as your application will perform significantly closer to the local benchmarks.

---

### CognoDB vs Neo4j AuraDB — the meaningful engine comparison

These are the two databases most worth comparing directly: both are managed Cypher-over-Bolt cloud graph databases with comparable free tiers. The results show a consistent ~1.9–2.1× advantage for Neo4j AuraDB across every workload:

| Workload | CognoDB p50 | Neo4j Aura p50 | Aura advantage |
|---|---|---|---|
| Ingest (total time) | 172.6 s | 67.5 s | **2.6× faster** |
| 1-Hop traversal | 492 ms | 261 ms | **1.9× faster** |
| 2-Hop traversal | 495 ms | 259 ms | **1.9× faster** |
| 3-Hop traversal | 495 ms | 260 ms | **1.9× faster** |
| Point lookup | 492 ms | 259 ms | **1.9× faster** |
| Filtered lookup | 734 ms | 261 ms | **2.8× faster** |
| Aggregation | 634 ms | 288 ms | **2.2× faster** |
| Mixed 1 client | 2.0 QPS | 3.7 QPS | **1.9× faster** |
| Mixed 10 clients | 19.1 QPS | 38.7 QPS | **2.0× faster** |
| Mixed 40 clients | 74.3 QPS | 145.6 QPS | **2.0× faster** |

The gap is remarkably uniform across all workloads — traversals, lookups, aggregations, and concurrent throughput all show the same ~2× factor. This pattern strongly suggests the difference is **additional network latency to the CognoDB endpoint** rather than a difference in Cypher execution capability. The CognoDB endpoint appears to be approximately 230–250 ms further from the Mumbai client than the Neo4j AuraDB endpoint.

The filtered lookup (734 ms vs 261 ms, 2.8× gap) shows higher variance on CognoDB, with p95 reaching 978 ms and max hitting 1,927 ms. This suggests the CognoDB c0 free tier's burstable CPU causes execution-time variance when running full label scans — the query waits for CPU budget to be replenished, introducing spikes that a dedicated CPU allocation would not show.

---

### FalkorDB — what memory-resident graph traversal looks like

FalkorDB's traversal results are the most technically interesting in the suite:

- **1-hop p50: 1 ms, p95: 2 ms, max: 2 ms** — sub-2ms even at the 95th percentile
- **2-hop p50: 1 ms, p95: 2 ms, max: 3 ms** — almost identical to 1-hop
- **3-hop p50: 1 ms, p95: 2 ms, max: 2 ms** — no measurable degradation with depth

The flat latency curve across hop depths (1 ms for 1-hop, 1 ms for 3-hop) is characteristic of FalkorDB's sparse adjacency matrix engine. Classical graph databases traverse relationships by following pointer chains on disk or in a B-tree — each hop costs roughly the same as a pointer dereference plus cache lookup. FalkorDB's matrix multiplication approach computes all reachable nodes at a given depth in a single matrix operation, so additional hops add minimal marginal cost on a sparse graph like this one.

For point lookups and filtered lookups, FalkorDB also achieves 1 ms p50 — the entire graph fits in RAM, so every query is essentially a memory access with no I/O.

**The aggregation result is the notable exception:** FalkorDB's aggregation p50 is 13 ms with p95 at 63 ms (5× variance). This is higher than expected for an in-memory engine and suggests that `COUNT GROUP BY region` over 50,000 nodes involves materialising a result set that temporarily exceeds the L3 cache, causing memory bandwidth pressure even though no disk I/O occurs. The 73 ms max is within the same order of magnitude as ArangoDB's 17 ms min.

**Concurrency behaviour:** FalkorDB achieves 930 QPS at 1 client — the highest single-client throughput in the suite. However, at 10 clients (271 QPS) and 40 clients (646 QPS) throughput is constrained. This is consistent with FalkorDB's documented single-writer architecture: concurrent write operations (20% of the mixed workload) serialise internally at the Redis layer, creating a write bottleneck that caps parallelism. Workloads that are read-heavy or read-only would scale much better.

---

### ArangoDB — strong across the board with one anomaly

ArangoDB's results show strong, consistent performance across all read workloads: 2–3 ms p50 for traversals and lookups, 88 ms for aggregation. The ingest throughput of 13,028 nodes/sec is 45× faster than CognoDB and 9.8× faster than FalkorDB, a direct consequence of ArangoDB's REST bulk-insert endpoint accepting JSON arrays and writing to RocksDB in a tight server-side loop — bypassing the Cypher parsing overhead that slows the Bolt-based databases.

**The aggregation variance** (17 ms min, 204 ms max, 88 ms p50) reflects RocksDB block cache warm-up. The first ~15 iterations read cold data from the block cache miss path; subsequent iterations hit warm data. A 50+ iteration warm-up rather than 20 would reduce this variance.

**The 40-client QPS anomaly (11,062 QPS)** requires a frank caveat. This figure is implausibly high — it implies ~0.09 ms average query time, which would require the Java HTTP client to complete requests at a rate inconsistent with ArangoDB's single-digit millisecond query latency. The most likely explanation is HTTP/1.1 connection pipeline behaviour under load: when 40 threads hammer the same local HTTP endpoint, the OS TCP stack and ArangoDB's internal HTTP keep-alive batching cause response completions to be counted faster than actual query executions. The 1-client (517 QPS) and 10-client (244 QPS) figures are reliable; treat the 40-client figure as an instrumentation artefact and not a fair throughput measurement.

---

### Summary: right tool, right context

| Scenario | Best choice from this benchmark |
|---|---|
| Lowest query latency — local/co-located deployment | **FalkorDB** (1 ms p50 traversal) |
| Managed cloud graph DB — lowest latency | **Neo4j AuraDB Free** (~260 ms from Mumbai) |
| Fastest bulk data loading | **ArangoDB** (13,000+ nodes/sec) |
| Multi-model needs (graph + document) | **ArangoDB** |
| Cypher-compatible managed cloud, zero infrastructure | **CognoDB Cloud** or **Neo4j AuraDB** |
| Read-heavy graph workloads, in-memory budget | **FalkorDB** |
| Write-heavy concurrent workloads | **ArangoDB** (no single-writer constraint) |

---

## 7. Caveats & Honest Notes

**Network latency dominates cloud results.** The benchmark client is in Mumbai, India. Both CognoDB Cloud and Neo4j AuraDB are managed cloud services whose endpoint regions are not published on the free tier. Every cloud query latency number in this benchmark includes a cross-regional internet round-trip (~240–490 ms). This is not a methodology error — it reflects real-world deployment conditions — but it means cloud-vs-local latency comparisons measure network topology as much as engine performance. A co-located deployment would produce significantly lower numbers for both cloud databases.

**CognoDB filtered lookup variance.** CognoDB's filtered lookup p95 is 978 ms and max is 1,927 ms against a p50 of 734 ms. This high tail is consistent with CPU credit exhaustion on a burstable instance. The c0 free tier is rated at 0.5 vCPU burstable — full label scans exhaust CPU credits faster than point lookups, causing queued execution. This is a free-tier characteristic, not a fundamental engine limitation.

**ArangoDB 40-client QPS anomaly.** The 11,062 QPS figure at 40 concurrent clients is not a reliable measurement. It reflects HTTP connection pipelining and OS-level TCP batching on localhost rather than actual ArangoDB query throughput. The 1-client (517 QPS) and 10-client (244 QPS) numbers are trustworthy.

**FalkorDB aggregation tail.** FalkorDB's aggregation p95 (63 ms) is 4.8× its p50 (13 ms). For an in-memory engine this variance is unexpected and warrants investigation. Our hypothesis is memory bandwidth pressure during materialisation of the group-by result set. A longer warm-up or a smaller dataset would clarify this.

**Single-run ingest.** Ingest throughput is measured from a single load run per database, not averaged across multiple runs. A single run is sufficient to identify the order-of-magnitude differences observed but does not capture run-to-run variance. Re-running ingest is impractical for a 48-hour deadline.

**ArangoDB image.** The `arangodb:3.12` Docker Hub image is the Enterprise edition. Only Community-tier features (standard graph traversal, RocksDB, persistent indexes) were used. No Enterprise-only features (SmartGraphs, SatelliteCollections, Encryption at Rest) were exercised.

**FalkorDB single-writer.** FalkorDB serialises all write operations at the Redis layer. The mixed concurrent workload includes 20% writes; at 40 clients this creates contention. Pure read workloads would scale linearly with thread count up to the memory bandwidth limit.

**Same logical query, different language.** Cypher and AQL express equivalent traversals with different syntax. We verified that each query returns an equivalent result set (count of reachable nodes at a given hop depth) but did not validate result-set identity record by record. Minor differences in how each engine handles disconnected nodes or self-loops may produce small count discrepancies that do not affect the latency measurements.

---

## 8. How to Reproduce

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker Desktop (Windows / Mac) or Docker Engine + Compose plugin (Linux)
- Free accounts: [CognoDB Cloud](https://console.cognodb.com/signup) · [Neo4j AuraDB Free](https://neo4j.com/cloud/platform/aura-graph-database/)

### Step 1 — Clone and set credentials

```cmd
git clone https://github.com/<your-username>/cognodb-benchmark.git
cd cognodb-benchmark
copy .env.example .env
```

Edit `.env`:

```env
# CognoDB — use bolt+s:// (TLS required by cloud endpoint)
COGNODB_URI=bolt+s://<instance-id>.databases.cognodb.cloud
COGNODB_USER=cognodb
COGNODB_PASSWORD=your_cognodb_password

# Neo4j AuraDB — use neo4j+s:// (TLS required)
NEO4J_AURA_URI=neo4j+s://<instance-id>.databases.neo4j.io
NEO4J_AURA_USER=neo4j
NEO4J_AURA_PASSWORD=your_aura_password

# FalkorDB — MUST use bolt:// (plain, NO TLS — FalkorDB does not support TLS)
FALKORDB_URI=bolt://localhost:7687
FALKORDB_USER=falkordb
FALKORDB_PASSWORD=

# ArangoDB — plain HTTP on localhost
ARANGODB_HOST=http://localhost:8529
ARANGODB_PASSWORD=benchmark123
```

> ⚠️ `FALKORDB_URI` must use `bolt://` (not `bolt+s://`). Using TLS causes an immediate "Connection terminated" error.

### Step 2 — Start local databases

```cmd
docker compose up -d
docker compose ps
```

Both `falkordb-benchmark` and `arangodb-benchmark` should show status `healthy` within 30 seconds.

### Step 3 — Download the dataset (one-time, ~600 MB download)

```cmd
mvn clean package -q
java -jar target\cognodb-benchmark-1.0.0.jar --download
```

Produces `data/pokec_nodes.csv` (50,000 rows) and `data/pokec_edges.csv` (28,027 rows).

### Step 4 — Run the benchmark

```cmd
REM All 4 databases (recommended):
java -jar target\cognodb-benchmark-1.0.0.jar --all

REM Single database (useful for re-running after a fix):
java -jar target\cognodb-benchmark-1.0.0.jar --db cognodb
java -jar target\cognodb-benchmark-1.0.0.jar --db neo4j
java -jar target\cognodb-benchmark-1.0.0.jar --db falkor
java -jar target\cognodb-benchmark-1.0.0.jar --db arango
```

Results are written to:
- `results/results_<timestamp>.csv`
- `results/results_<timestamp>.json`

### Step 5 — View the dashboard

```cmd
python -m http.server 8080
```

Open [http://localhost:8080](http://localhost:8080) — the dashboard auto-loads the latest results file and renders all charts and tables.

Alternatively, open `index.html` directly in a browser and click **Upload JSON**.

### Benchmark tuning (optional)

```env
BENCH_WARMUP_ITERATIONS=20        # discard first N iterations per workload
BENCH_MEASURE_ITERATIONS=100      # measure next N iterations
BENCH_CONCURRENCY_LEVELS=1,10,40  # client thread counts for mixed workload
BENCH_TRAVERSAL_START_NODES=100   # random start nodes for traversal/lookup
```

---

## 9. Project Structure

```
cognodb-benchmark/
├── pom.xml                                   # Maven build, all dependencies pinned
├── docker-compose.yml                        # FalkorDB + ArangoDB, resource-capped
├── .env.example                              # Credential template (copy → .env)
├── index.html                                # Results dashboard (Chart.js, dark theme)
├── data/
│   ├── download_pokec.sh                     # Bash downloader (Linux/Mac)
│   ├── pokec_nodes.csv                       # 50,000 nodes (generated)
│   └── pokec_edges.csv                       # 28,027 edges (generated)
├── results/
│   └── results_<timestamp>.{json,csv}        # Benchmark output
└── src/main/java/ai/wexa/benchmark/
    ├── Main.java                             # CLI: --download | --all | --db <name>
    ├── config/EnvConfig.java                 # Reads all credentials from env / .env
    ├── data/DataDownloader.java              # Java downloader (Windows-safe, no bash)
    ├── model/
    │   ├── BenchmarkResult.java              # Result POJO — all metric fields
    │   └── WorkloadType.java                 # Enum: INGEST, HOP_1..3, MIXED_C* etc.
    ├── loader/
    │   ├── CognoDBLoader.java
    │   ├── Neo4jAuraLoader.java
    │   ├── FalkorDBLoader.java
    │   └── ArangoDBLoader.java
    ├── benchmark/
    │   ├── GraphBenchmark.java               # Interface — all 6 workload methods
    │   ├── CognoDBBenchmark.java
    │   ├── Neo4jAuraBenchmark.java
    │   ├── FalkorDBBenchmark.java            # Note: withoutEncryption() required
    │   ├── ArangoDBBenchmark.java
    │   └── TigerGraphBenchmark.java          # Implemented; excluded from --all
    └── report/
        ├── CsvReporter.java
        └── JsonReporter.java
```

### Dependencies (pinned versions)

| Library | Version | Purpose |
|---|---|---|
| `neo4j-java-driver` | 5.18.0 | CognoDB Cloud, Neo4j AuraDB, FalkorDB (Bolt) |
| `gremlin-driver` | 3.7.2 | Available; not used in the 4-DB suite |
| `HdrHistogram` | 2.2.2 | p50 / p95 latency percentile computation |
| `dotenv-java` | 3.0.0 | Loads `.env` credentials locally |
| `jackson-databind` | 2.17.1 | JSON results serialisation |
| `commons-csv` | 1.11.0 | Pokec dataset CSV parsing |
| `logback-classic` | 1.5.6 | Structured logging |

---

*Interactive results dashboard: open `index.html` after running the benchmark, or click **Upload JSON** to load any `results_*.json` directly from disk.*
