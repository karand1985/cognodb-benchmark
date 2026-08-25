package ai.wexa.benchmark.benchmark;

import ai.wexa.benchmark.config.EnvConfig;
import ai.wexa.benchmark.loader.CognoDBLoader;
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
 * Benchmarks CognoDB Cloud on all 6 required workloads.
 *
 * CognoDB speaks the Bolt protocol with Neo4j-compatible Cypher.
 * Known quirks (from Assignment 1):
 *  - Use string-based node ids, not internal Neo4j ids
 *  - Explicit MATCH + CREATE for relationships (MERGE can behave unexpectedly)
 *  - Relationship hydration needs explicit OPTIONAL MATCH with variable binding
 *  - Pattern comprehension is not supported — use explicit queries
 */
public class CognoDBBenchmark implements GraphBenchmark {

    private static final Logger log = LoggerFactory.getLogger(CognoDBBenchmark.class);

    // HDRHistogram tracks latency up to 1 hour (3_600_000 ms), 3 significant figures
    private static final long MAX_LATENCY_MS = 3_600_000L;

    private final Driver driver;
    private final EnvConfig config;

    public CognoDBBenchmark(EnvConfig config) {
        this.config = config;
        this.driver = GraphDatabase.driver(
            config.cognoDbUri(),
            AuthTokens.basic(config.cognoDbUser(), config.cognoDbPassword()),
            Config.builder()
                .withMaxConnectionPoolSize(50)
                .withConnectionAcquisitionTimeout(30, TimeUnit.SECONDS)
                .build()
        );
    }

    @Override
    public String databaseName() {
        return "CognoDB Cloud";
    }

    // -------------------------------------------------------------------------
    // Connection check
    // -------------------------------------------------------------------------

    @Override
    public void verifyConnection() {
        try (Session session = driver.session()) {
            session.run("RETURN 1").consume();
        }
    }

    // -------------------------------------------------------------------------
    // 1. Ingest
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkIngest(Path nodesCsv, Path edgesCsv) {
        CognoDBLoader loader = new CognoDBLoader(driver);
        long startMs = System.currentTimeMillis();

        long[] counts;
        try {
            counts = loader.load(nodesCsv, edgesCsv);
        } catch (Exception e) {
            throw new RuntimeException("CognoDB ingest failed: " + e.getMessage(), e);
        }

        long totalMs   = System.currentTimeMillis() - startMs;
        long nodes     = counts[0];
        long rels      = counts[1];
        double totalSec = totalMs / 1000.0;

        BenchmarkResult r = BenchmarkResult.ingest(
            databaseName(), nodes, rels,
            nodes / totalSec,
            rels  / totalSec,
            totalMs
        );
        r.measuredAt   = Instant.now().toString();
        r.instanceSpec = "CognoDB c0 — 0.5 vCPU burstable, 256 MB RAM, 1 GB disk";
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

        // Warm-up
        for (int i = 0; i < warmupIter; i++) {
            String id = nodes.get(i % nodes.size());
            runTimedQuery(query, Map.of("id", id));
        }

        // Measurement
        for (int i = 0; i < measureIter; i++) {
            String id = nodes.get(i % nodes.size());
            long ms = runTimedQuery(query, Map.of("id", id));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "HOP_" + hopDepth, histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        return r;
    }

    private String buildHopQuery(int hops) {
        // Explicit MATCH chains — CognoDB does not support variable-length
        // patterns reliably on the free tier; explicit hops are safer and
        // consistent with equivalent queries on other platforms.
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
        r.caveats = "Indexed on User.id (CREATE INDEX user_id_idx)";
        return r;
    }

    // -------------------------------------------------------------------------
    // 4. Filtered lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkFilteredLookup(int warmupIter, int measureIter) {
        // Find users aged 25–35 — a property range filter, not an index point lookup
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
        r.caveats = "Filter: age BETWEEN 25 AND 35, LIMIT 100. No secondary index on age.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 5. Aggregation
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkAggregation(int warmupIter, int measureIter) {
        // Count users grouped by region — full label scan + group-by
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
        r.caveats = "Full label scan: COUNT(User) GROUP BY region ORDER BY cnt DESC";
        return r;
    }

    // -------------------------------------------------------------------------
    // 6. Mixed concurrent read/write
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkMixedConcurrent(int concurrency, int durationSec,
                                                     List<String> startNodeIds) {
        // 80% reads (1-hop traversal), 20% writes (update a user's age)
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
                        // Log and continue — some free-tier throttling is expected
                        log.debug("[CognoDB] Mixed workload error: {}", e.getMessage());
                    }
                    localOps++;
                }
            }));
        }

        // Wait for all threads to finish
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignore) {}
        }
        executor.shutdown();

        double qps = opsCompleted.get() / (double) durationSec;
        BenchmarkResult r = BenchmarkResult.mixed(databaseName(), concurrency, qps, 80);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "80% 1-hop reads, 20% property writes, " + durationSec + "s duration. " +
                       "Free-tier throttling may affect results.";
        return r;
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a query and returns its wall-clock latency in milliseconds.
     */
    private long runTimedQuery(String cypher, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try (Session session = driver.session()) {
            session.run(cypher, params).consume();
        }
        return System.currentTimeMillis() - start;
    }

    /**
     * Builds a BenchmarkResult from an HDRHistogram.
     */
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
