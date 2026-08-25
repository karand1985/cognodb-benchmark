package ai.graphdb.benchmark.benchmark;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.HdrHistogram.Histogram;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.graphdb.benchmark.config.EnvConfig;
import ai.graphdb.benchmark.loader.FalkorDBLoader;
import ai.graphdb.benchmark.model.BenchmarkResult;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;

/**
 * Benchmarks FalkorDB on all 6 required workloads using the native Redis RESP
 * protocol via Jedis 4.3.x JedisPooled.
 *
 * Why Jedis?
 * ----------
 * FalkorDB self-hosted (falkordb/falkordb:latest) runs Redis on port 6379.
 * It does NOT include a Bolt proxy on port 7687, so the Neo4j Java driver
 * fails with "Connection to the database terminated" during the Bolt handshake.
 * Jedis 4.3.2 — the last 4.x release with the RedisGraph graph command module —
 * provides graphQuery / graphDelete / graphList that work directly against
 * FalkorDB 4.x (a fully-compatible RedisGraph fork).
 *
 * JedisPooled (extends UnifiedJedis) is thread-safe, manages its own connection
 * pool, and exposes all graph commands.  The classic Jedis class does NOT have
 * graph commands in 4.x; JedisPooled is the correct class to use.
 *
 * Connection: FALKORDB_HOST (default: localhost) / FALKORDB_PORT (default: 6379).
 */
public class FalkorDBBenchmark implements GraphBenchmark {

    private static final Logger log = LoggerFactory.getLogger(FalkorDBBenchmark.class);
    private static final long MAX_LATENCY_MS = 3_600_000L;

    private final JedisPooled jedis;
    private final EnvConfig   config;

    public FalkorDBBenchmark(EnvConfig config) {
        this.config = config;

        String host     = config.falkorDbHost();
        int    port     = config.falkorDbPort();
        String password = config.falkorDbPassword();

        // JedisPooled constructors require GenericObjectPoolConfig<Connection>,
        // NOT the legacy JedisPoolConfig (which is GenericObjectPoolConfig<Object>).
        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(60);           // enough for concurrency level 40 + overhead
        poolConfig.setMaxWaitMillis(30_000L);
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestOnReturn(false);

        if (password != null && !password.isBlank()) {
            this.jedis = new JedisPooled(poolConfig, host, port, 30_000, password);
        } else {
            this.jedis = new JedisPooled(poolConfig, host, port, 30_000);
        }

        log.info("[FalkorDB] JedisPooled created — {}:{}", host, port);
    }

    @Override
    public String databaseName() { return "FalkorDB"; }

    // -------------------------------------------------------------------------
    // Connection check
    // -------------------------------------------------------------------------

    @Override
    public void verifyConnection() {
        // graphList() returns existing graph names and is always safe to call.
        // It verifies both the Redis connection and FalkorDB module availability.
        jedis.graphList();
    }

    // -------------------------------------------------------------------------
    // 1. Ingest
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkIngest(Path nodesCsv, Path edgesCsv) {
        FalkorDBLoader loader = new FalkorDBLoader(jedis);
        long startMs = System.currentTimeMillis();

        long[] counts;
        try {
            counts = loader.load(nodesCsv, edgesCsv);
        } catch (Exception e) {
            throw new RuntimeException("FalkorDB ingest failed: " + e.getMessage(), e);
        }

        long   totalMs  = System.currentTimeMillis() - startMs;
        long   nodes    = counts[0];
        long   rels     = counts[1];
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
            runTimedQuery(query, nodes.get(i % nodes.size()));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runTimedQuery(query, nodes.get(i % nodes.size()));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "HOP_" + hopDepth, histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        return r;
    }

    private String buildHopQuery(int hops) {
        return switch (hops) {
            case 1 -> "MATCH (u:User {id:$id})-[:FRIENDS_WITH]->(n:User) RETURN count(n) AS cnt";
            case 2 -> "MATCH (u:User {id:$id})-[:FRIENDS_WITH]->(:User)" +
                      "-[:FRIENDS_WITH]->(n:User) RETURN count(n) AS cnt";
            case 3 -> "MATCH (u:User {id:$id})-[:FRIENDS_WITH]->(:User)" +
                      "-[:FRIENDS_WITH]->(:User)-[:FRIENDS_WITH]->(n:User) RETURN count(n) AS cnt";
            default -> throw new IllegalArgumentException("Unsupported hop depth: " + hops);
        };
    }

    // -------------------------------------------------------------------------
    // 3. Point lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkPointLookup(List<String> nodeIds,
                                                 int warmupIter, int measureIter) {
        String query = "MATCH (u:User {id:$id}) RETURN u.id, u.age, u.region LIMIT 1";
        List<String> ids = new ArrayList<>(nodeIds);
        Collections.shuffle(ids);

        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runTimedQuery(query, ids.get(i % ids.size()));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runTimedQuery(query, ids.get(i % ids.size()));
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
        String query = "MATCH (u:User) WHERE u.age >= 25 AND u.age <= 35 " +
                       "RETURN u.id, u.region LIMIT 100";
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) { runTimedQueryNoParam(query); }
        for (int i = 0; i < measureIter; i++) {
            histogram.recordValue(Math.max(runTimedQueryNoParam(query), 1));
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
        String query = "MATCH (u:User) RETURN u.region AS region, count(u) AS cnt ORDER BY cnt DESC";
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) { runTimedQueryNoParam(query); }
        for (int i = 0; i < measureIter; i++) {
            histogram.recordValue(Math.max(runTimedQueryNoParam(query), 1));
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
        String readQuery  = "MATCH (u:User {id:$id})-[:FRIENDS_WITH]->(n) RETURN count(n) AS cnt";
        String writeQuery = "MATCH (u:User {id:$id}) SET u.age = u.age + 1 RETURN u.id";

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
                    String  id     = ids.get((localOps + threadIdx) % ids.size());
                    boolean isRead = (localOps % 5 != 0); // 80% reads
                    try {
                        runTimedQuery(isRead ? readQuery : writeQuery, id);
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
        if (jedis != null) jedis.close();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a query that takes a single $id parameter by inlining the value.
     * Node IDs are integer strings from our own dataset — no injection risk.
     */
    private long runTimedQuery(String cypher, String nodeId) {
        String query = cypher.replace("$id", "'" + nodeId + "'");
        long start = System.currentTimeMillis();
        jedis.graphQuery(FalkorDBLoader.GRAPH_NAME, query);
        return System.currentTimeMillis() - start;
    }

    /** Runs a parameterless query (filtered lookup, aggregation). */
    private long runTimedQueryNoParam(String cypher) {
        long start = System.currentTimeMillis();
        jedis.graphQuery(FalkorDBLoader.GRAPH_NAME, cypher);
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