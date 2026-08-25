package ai.wexa.benchmark.benchmark;

import ai.wexa.benchmark.config.EnvConfig;
import ai.wexa.benchmark.loader.FalkorDBLoader;
import ai.wexa.benchmark.model.BenchmarkResult;
import org.HdrHistogram.Histogram;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmarks FalkorDB on all 6 required workloads.
 *
 * FalkorDB is a Redis-backed graph database that speaks the Bolt protocol,
 * making it compatible with the Neo4j Java driver. Key characteristics:
 *
 *  - Memory-resident graph engine (like Redis) — expect very low latency.
 *  - All queries are scoped to a named graph ("pokec") via SessionConfig.
 *  - Cypher dialect is mostly Neo4j-compatible with minor differences:
 *    * CREATE INDEX has no IF NOT EXISTS in older versions.
 *    * Variable-length patterns ([*1..3]) work but may behave differently.
 *    * CALL {} (subquery) is not supported — use inline MATCH chains.
 *  - Self-hosted via Docker with RAM cap to match free-tier resources:
 *    docker run -p 7687:7687 --memory=256m falkordb/falkordb:latest
 *  - Resource cap documented in instanceSpec for fair comparison.
 *
 * Setup (run before benchmarking):
 *   docker run -d --name falkordb -p 7687:7687 --memory=256m \
 *     falkordb/falkordb:latest
 */
public class FalkorDBBenchmark implements GraphBenchmark {

    private static final Logger log = LoggerFactory.getLogger(FalkorDBBenchmark.class);
    private static final long MAX_LATENCY_MS = 3_600_000L;

    private final Driver driver;
    private final EnvConfig config;

    public FalkorDBBenchmark(EnvConfig config) {
        this.config = config;

        // FalkorDB typically has no auth by default when self-hosted
        String password = config.falkorDbPassword();
        AuthToken auth = (password == null || password.isBlank())
            ? AuthTokens.none()
            : AuthTokens.basic(config.falkorDbUser(), password);

        this.driver = GraphDatabase.driver(
            config.falkorDbUri(),
            auth,
            Config.builder()
                .withMaxConnectionPoolSize(50)
                .withConnectionAcquisitionTimeout(30, TimeUnit.SECONDS)
                .build()
        );
    }

    @Override
    public String databaseName() {
        return "FalkorDB";
    }

    // -------------------------------------------------------------------------
    // Session helper — all FalkorDB queries are scoped to named graph "pokec"
    // -------------------------------------------------------------------------

    private SessionConfig graphSession() {
        return SessionConfig.builder()
            .withDatabase(FalkorDBLoader.GRAPH_NAME)
            .build();
    }

    // -------------------------------------------------------------------------
    // Connection check
    // -------------------------------------------------------------------------

    @Override
    public void verifyConnection() {
        try (Session session = driver.session(graphSession())) {
            session.run("RETURN 1").consume();
        }
    }

    // -------------------------------------------------------------------------
    // 1. Ingest
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkIngest(Path nodesCsv, Path edgesCsv) {
        FalkorDBLoader loader = new FalkorDBLoader(driver);
        long startMs = System.currentTimeMillis();

        long[] counts;
        try {
            counts = loader.load(nodesCsv, edgesCsv);
        } catch (Exception e) {
            throw new RuntimeException("FalkorDB ingest failed: " + e.getMessage(), e);
        }

        long totalMs    = System.currentTimeMillis() - startMs;
        long nodes      = counts[0];
        long rels       = counts[1];
        double totalSec = totalMs / 1000.0;

        BenchmarkResult r = BenchmarkResult.ingest(
            databaseName(), nodes, rels,
            nodes / totalSec,
            rels  / totalSec,
            totalMs
        );
        r.measuredAt   = Instant.now().toString();
        r.instanceSpec = "FalkorDB self-hosted Docker — memory capped to 256 MB " +
                         "(--memory=256m) to match CognoDB c0 free tier";
        return r;
    }

    // -------------------------------------------------------------------------
    // 2. Traversal (1-hop, 2-hop, 3-hop)
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkTraversal(int hopDepth, List<String> startNodeIds,
                                               int warmupIter, int measureIter) {
        String query = buildHopQuery(hopDepth);
        List<String> nodes = new ArrayList<>(startNodeIds);
        Collections.shuffle(nodes);

        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runTimedQuery(query, Map.of("id", nodes.get(i % nodes.size())));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runTimedQuery(query, Map.of("id", nodes.get(i % nodes.size())));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "HOP_" + hopDepth, histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        return r;
    }

    private String buildHopQuery(int hops) {
        // FalkorDB supports explicit MATCH chains — same as CognoDB/Neo4j.
        // Variable-length patterns work but explicit chains are more portable.
        return switch (hops) {
            case 1 -> """
                MATCH (u:User {id: $id})-[:FRIENDS_WITH]->(neighbor:User)
                RETURN count(neighbor) AS cnt
                """;
            case 2 -> """
                MATCH (u:User {id: $id})-[:FRIENDS_WITH]->(:User)-[:FRIENDS_WITH]->(neighbor:User)
                RETURN count(neighbor) AS cnt
                """;
            case 3 -> """
                MATCH (u:User {id: $id})-[:FRIENDS_WITH]->(:User)
                      -[:FRIENDS_WITH]->(:User)-[:FRIENDS_WITH]->(neighbor:User)
                RETURN count(neighbor) AS cnt
                """;
            default -> throw new IllegalArgumentException("Unsupported hop depth: " + hops);
        };
    }

    // -------------------------------------------------------------------------
    // 3. Point lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkPointLookup(List<String> nodeIds,
                                                 int warmupIter, int measureIter) {
        String query = "MATCH (u:User {id: $id}) RETURN u.id, u.age, u.region LIMIT 1";
        List<String> ids = new ArrayList<>(nodeIds);
        Collections.shuffle(ids);

        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runTimedQuery(query, Map.of("id", ids.get(i % ids.size())));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runTimedQuery(query, Map.of("id", ids.get(i % ids.size())));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "POINT_LOOKUP", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "Indexed on User.id (CREATE INDEX FOR (u:User) ON (u.id))";
        return r;
    }

    // -------------------------------------------------------------------------
    // 4. Filtered lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkFilteredLookup(int warmupIter, int measureIter) {
        String query = """
            MATCH (u:User)
            WHERE u.age >= 25 AND u.age <= 35
            RETURN u.id, u.region
            LIMIT 100
            """;
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runTimedQuery(query, Map.of());
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runTimedQuery(query, Map.of());
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "FILTERED_LOOKUP", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "Filter: age BETWEEN 25 AND 35, LIMIT 100. No secondary index on age. " +
                       "FalkorDB is memory-resident so full scans are fast.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 5. Aggregation
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkAggregation(int warmupIter, int measureIter) {
        String query = """
            MATCH (u:User)
            RETURN u.region AS region, count(u) AS cnt
            ORDER BY cnt DESC
            """;
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runTimedQuery(query, Map.of());
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runTimedQuery(query, Map.of());
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "AGGREGATION", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "Full label scan: COUNT(User) GROUP BY region. " +
                       "FalkorDB uses sparse matrix representation — aggregations are fast.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 6. Mixed concurrent read/write
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkMixedConcurrent(int concurrency, int durationSec,
                                                     List<String> startNodeIds) {
        String readQuery  = "MATCH (u:User {id: $id})-[:FRIENDS_WITH]->(n) RETURN count(n) AS cnt";
        String writeQuery = "MATCH (u:User {id: $id}) SET u.age = u.age + 1 RETURN u.id";

        AtomicLong opsCompleted = new AtomicLong(0);
        List<String> ids = new ArrayList<>(startNodeIds);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        long endTime = System.currentTimeMillis() + (durationSec * 1000L);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < concurrency; t++) {
            int threadIdx = t;
            futures.add(executor.submit(() -> {
                int localOps = 0;
                while (System.currentTimeMillis() < endTime) {
                    String id = ids.get((localOps + threadIdx) % ids.size());
                    boolean isRead = (localOps % 5 != 0); // 80% reads
                    try {
                        runTimedQuery(isRead ? readQuery : writeQuery, Map.of("id", id));
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("[FalkorDB] Mixed workload error: {}", e.getMessage());
                    }
                    localOps++;
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) {}
        }
        executor.shutdown();

        double qps = opsCompleted.get() / (double) durationSec;
        BenchmarkResult r = BenchmarkResult.mixed(databaseName(), concurrency, qps, 80);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "80% 1-hop reads, 20% property writes, " + durationSec + "s duration. " +
                       "FalkorDB is single-writer; concurrent writes serialise internally.";
        return r;
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        if (driver != null) driver.close();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private long runTimedQuery(String cypher, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try (Session session = driver.session(graphSession())) {
            session.run(cypher, params).consume();
        }
        return System.currentTimeMillis() - start;
    }

    private BenchmarkResult fromHistogram(String db, String workload,
                                           Histogram h, int iterations) {
        return BenchmarkResult.latency(
            db, workload,
            h.getValueAtPercentile(50),
            h.getValueAtPercentile(95),
            h.getMinValue(),
            h.getMaxValue(),
            h.getMean(),
            iterations
        );
    }
}
