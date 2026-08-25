package ai.wexa.benchmark.benchmark;

import ai.wexa.benchmark.config.EnvConfig;
import ai.wexa.benchmark.loader.ArangoDBLoader;
import ai.wexa.benchmark.model.BenchmarkResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmarks ArangoDB Community Edition on all 6 required workloads.
 *
 * ArangoDB characteristics relevant to this benchmark:
 *  - Multi-model database: document + graph in one engine.
 *  - Query language: AQL (Arango Query Language) — different from Cypher/Gremlin.
 *  - REST API on port 8529 — no separate driver needed (Java HttpClient).
 *  - Graph traversals use AQL FOR v, e, p IN N..N OUTBOUND syntax.
 *  - Self-hosted Docker with --memory=256m for fair resource comparison.
 *  - Named graph: "pokec_graph", vertex collection: "users", edge: "friends".
 *
 * AQL traversal syntax (equivalent to Cypher MATCH hops):
 *   FOR v IN 1..1 OUTBOUND 'users/{id}' GRAPH 'pokec_graph' RETURN v
 *
 * All queries go through POST /_db/pokec/_api/cursor (AQL cursor API).
 */
public class ArangoDBBenchmark implements GraphBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ArangoDBBenchmark.class);
    private static final long MAX_LATENCY_MS = 3_600_000L;

    private final EnvConfig    config;
    private final HttpClient   http;
    private final ObjectMapper mapper;
    private final String       baseUrl;
    private final String       authHeader;
    private final String       cursorUrl;

    public ArangoDBBenchmark(EnvConfig config) {
        this.config  = config;
        this.http    = HttpClient.newBuilder()
            .executor(Executors.newFixedThreadPool(50))
            .build();
        this.mapper  = new ObjectMapper();

        String host  = config.arangoDbHost();
        this.baseUrl = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        String creds = Base64.getEncoder()
            .encodeToString(("root:" + config.arangoDbPassword()).getBytes());
        this.authHeader = "Basic " + creds;
        this.cursorUrl  = this.baseUrl + "/_db/" + ArangoDBLoader.DB_NAME + "/_api/cursor";
    }

    @Override
    public String databaseName() { return "ArangoDB"; }

    // -------------------------------------------------------------------------
    // Connection check
    // -------------------------------------------------------------------------

    @Override
    public void verifyConnection() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_api/version"))
                .header("Authorization", authHeader)
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("ArangoDB returned HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("ArangoDB connection failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // 1. Ingest
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkIngest(Path nodesCsv, Path edgesCsv) {
        ArangoDBLoader loader = new ArangoDBLoader(
            config.arangoDbHost(), config.arangoDbPassword());
        long startMs = System.currentTimeMillis();

        long[] counts;
        try {
            counts = loader.load(nodesCsv, edgesCsv);
        } catch (Exception e) {
            throw new RuntimeException("ArangoDB ingest failed: " + e.getMessage(), e);
        }

        long totalMs    = System.currentTimeMillis() - startMs;
        long nodes      = counts[0];
        long rels       = counts[1];
        double totalSec = totalMs / 1000.0;

        BenchmarkResult r = BenchmarkResult.ingest(
            databaseName(), nodes, rels,
            nodes / totalSec, rels / totalSec, totalMs);
        r.measuredAt   = Instant.now().toString();
        r.instanceSpec = "ArangoDB Community 3.12 — Docker, --memory=256m, 1 vCPU cap";
        return r;
    }

    // -------------------------------------------------------------------------
    // 2. Traversal (1-hop, 2-hop, 3-hop)
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkTraversal(int hopDepth, List<String> startNodeIds,
                                               int warmupIter, int measureIter) {
        List<String> nodes = new ArrayList<>(startNodeIds);
        Collections.shuffle(nodes);
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            String id = nodes.get(i % nodes.size());
            runAql(buildHopAql(hopDepth), Map.of("startId", startKey(id)));
        }
        for (int i = 0; i < measureIter; i++) {
            String id = nodes.get(i % nodes.size());
            long ms = runAql(buildHopAql(hopDepth), Map.of("startId", startKey(id)));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "HOP_" + hopDepth, histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "AQL graph traversal: FOR v IN " + hopDepth + ".." + hopDepth
                       + " OUTBOUND @startId GRAPH 'pokec_graph'";
        return r;
    }

    /**
     * AQL graph traversal — exact hop depth enforced by MIN..MAX = N..N.
     * @startId is a document handle e.g. "users/12345"
     */
    private String buildHopAql(int hops) {
        return "FOR v IN " + hops + ".." + hops + " OUTBOUND @startId " +
               "GRAPH '" + ArangoDBLoader.GRAPH_NAME + "' " +
               "RETURN COUNT(v)";
    }

    // -------------------------------------------------------------------------
    // 3. Point lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkPointLookup(List<String> nodeIds,
                                                 int warmupIter, int measureIter) {
        // AQL point lookup by indexed 'id' field using persistent index
        String aql = "FOR u IN " + ArangoDBLoader.VERTEX_COL +
                     " FILTER u.id == @id LIMIT 1 RETURN { id: u.id, age: u.age, region: u.region }";
        List<String> ids = new ArrayList<>(nodeIds);
        Collections.shuffle(ids);
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runAql(aql, Map.of("id", ids.get(i % ids.size())));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runAql(aql, Map.of("id", ids.get(i % ids.size())));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "POINT_LOOKUP", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "Persistent index on users.id (unique). AQL FILTER u.id == @id LIMIT 1.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 4. Filtered lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkFilteredLookup(int warmupIter, int measureIter) {
        String aql = "FOR u IN " + ArangoDBLoader.VERTEX_COL +
                     " FILTER u.age >= 25 AND u.age <= 35 " +
                     " LIMIT 100 RETURN { id: u.id, region: u.region }";
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runAql(aql, Map.of());
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runAql(aql, Map.of());
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "FILTERED_LOOKUP", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "AQL FILTER age >= 25 AND age <= 35, LIMIT 100. No secondary index on age — full scan.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 5. Aggregation
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkAggregation(int warmupIter, int measureIter) {
        String aql = "FOR u IN " + ArangoDBLoader.VERTEX_COL +
                     " COLLECT region = u.region WITH COUNT INTO cnt " +
                     " SORT cnt DESC RETURN { region, cnt }";
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runAql(aql, Map.of());
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runAql(aql, Map.of());
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "AGGREGATION", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "AQL COLLECT region WITH COUNT — full collection scan + group-by.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 6. Mixed concurrent read/write
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkMixedConcurrent(int concurrency, int durationSec,
                                                     List<String> startNodeIds) {
        // Read: 1-hop traversal from a random node
        String readAql = "FOR v IN 1..1 OUTBOUND @startId GRAPH '" +
                         ArangoDBLoader.GRAPH_NAME + "' RETURN COUNT(v)";
        // Write: update age property of a random user
        String writeAql = "FOR u IN " + ArangoDBLoader.VERTEX_COL +
                          " FILTER u.id == @id UPDATE u WITH { age: u.age + 1 } IN " +
                          ArangoDBLoader.VERTEX_COL + " RETURN NEW.id";

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
                        if (isRead) {
                            runAql(readAql, Map.of("startId", startKey(id)));
                        } else {
                            runAql(writeAql, Map.of("id", id));
                        }
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("[ArangoDB] Mixed workload error: {}", e.getMessage());
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
        r.caveats    = "80% AQL graph reads, 20% AQL UPDATE writes. " + durationSec + "s duration.";
        return r;
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        // HttpClient is managed by JVM — no explicit close needed
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Executes an AQL query via the cursor API and returns wall-clock latency in ms.
     * bindVars can be empty map for queries with no parameters.
     */
    private long runAql(String aql, Map<String, Object> bindVars) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query",    aql);
            body.put("bindVars", bindVars);
            body.put("batchSize", 1);   // we only care about timing, not result size

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cursorUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("[ArangoDB] Query error: {}", e.getMessage());
        }
        return System.currentTimeMillis() - start;
    }

    /**
     * Converts a plain node id string into an ArangoDB document handle.
     * e.g. "12345" → "users/12345"
     */
    private String startKey(String id) {
        return ArangoDBLoader.VERTEX_COL + "/" + id;
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
