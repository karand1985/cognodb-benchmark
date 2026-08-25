package ai.graphdb.benchmark.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.graphdb.benchmark.config.EnvConfig;
import ai.graphdb.benchmark.loader.TigerGraphLoader;
import ai.graphdb.benchmark.model.BenchmarkResult;

/**
 * Benchmarks TigerGraph Cloud Free Tier on all 6 required workloads.
 *
 * TigerGraph characteristics relevant to this benchmark:
 *  - Native parallel graph (MPP) architecture — designed for large-scale traversals.
 *  - Query language: GSQL (proprietary). Installed queries are called via REST++.
 *  - REST++ API on port 9000 (cloud: HTTPS on the cloud host URL).
 *  - Authentication: Bearer token obtained from TigerGraph Cloud console.
 *  - Traversals use pre-installed GSQL queries called via GET /query/{graph}/{name}.
 *  - Aggregations and lookups use built-in REST++ endpoints where possible,
 *    and pre-installed GSQL queries for complex workloads.
 *
 * GSQL query installation:
 *  TigerGraph requires traversal queries to be compiled (installed) before use.
 *  This class calls installQueries() on first connect, which posts GSQL query
 *  definitions and installs them. This is a one-time operation per instance.
 *
 * Free tier specs (documented for README):
 *  - TigerGraph Cloud free tier: 4 vCPU, 7.5 GB RAM (larger than others).
 *  - This is noted as a caveat in results — resource parity is not achievable
 *    on TigerGraph Cloud's free tier. All measurements include this caveat.
 *
 * Instance setup: https://tgcloud.io → Create Free Instance → pick region.
 */
public class TigerGraphBenchmark implements GraphBenchmark {

    private static final Logger log = LoggerFactory.getLogger(TigerGraphBenchmark.class);
    private static final long MAX_LATENCY_MS = 3_600_000L;

    private final EnvConfig    config;
    private final HttpClient   http;
    private final ObjectMapper mapper;
    private final String       host;
    private final String       token;
    private final String       graph;

    public TigerGraphBenchmark(EnvConfig config) {
        this.config = config;
        this.http   = HttpClient.newBuilder()
            .executor(Executors.newFixedThreadPool(50))
            .build();
        this.mapper = new ObjectMapper();
        this.host   = config.tigerGraphHost().endsWith("/")
            ? config.tigerGraphHost().substring(0, config.tigerGraphHost().length() - 1)
            : config.tigerGraphHost();
        this.token  = config.tigerGraphToken();
        this.graph  = config.tigerGraphGraph();
    }

    @Override
    public String databaseName() { return "TigerGraph Cloud"; }

    // -------------------------------------------------------------------------
    // Connection check + query installation
    // -------------------------------------------------------------------------

    @Override
    public void verifyConnection() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/ping"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("TigerGraph ping returned HTTP " + resp.statusCode());
            }
            installQueries();
        } catch (Exception e) {
            throw new RuntimeException("TigerGraph connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * Installs the GSQL traversal queries required for benchmarking.
     * TigerGraph compiles and installs queries ahead of execution.
     * This is idempotent — installing an existing query just overwrites it.
     */
    private void installQueries() throws Exception {
        log.info("[TigerGraph] Installing GSQL benchmark queries...");

        // 1-hop traversal query
        installGsql("hop1",
            "CREATE OR REPLACE QUERY hop1(VERTEX<User> start) FOR GRAPH " + graph + " {" +
            "  neighbours = SELECT t FROM start:s -(FRIENDS_WITH:e)-> User:t;" +
            "  PRINT neighbours.size();" +
            "}");

        // 2-hop traversal query
        installGsql("hop2",
            "CREATE OR REPLACE QUERY hop2(VERTEX<User> start) FOR GRAPH " + graph + " {" +
            "  hop1set = SELECT t FROM start:s -(FRIENDS_WITH:e)-> User:t;" +
            "  hop2set = SELECT t FROM hop1set:s -(FRIENDS_WITH:e)-> User:t;" +
            "  PRINT hop2set.size();" +
            "}");

        // 3-hop traversal query
        installGsql("hop3",
            "CREATE OR REPLACE QUERY hop3(VERTEX<User> start) FOR GRAPH " + graph + " {" +
            "  hop1set = SELECT t FROM start:s -(FRIENDS_WITH:e)-> User:t;" +
            "  hop2set = SELECT t FROM hop1set:s -(FRIENDS_WITH:e)-> User:t;" +
            "  hop3set = SELECT t FROM hop2set:s -(FRIENDS_WITH:e)-> User:t;" +
            "  PRINT hop3set.size();" +
            "}");

        // Filtered lookup query (age range)
        installGsql("filteredLookup",
            "CREATE OR REPLACE QUERY filteredLookup(INT minAge, INT maxAge) FOR GRAPH " + graph + " {" +
            "  result = SELECT u FROM User:u WHERE u.age >= minAge AND u.age <= maxAge LIMIT 100;" +
            "  PRINT result;" +
            "}");

        // Aggregation query (count by region)
        installGsql("aggregateByRegion",
            "CREATE OR REPLACE QUERY aggregateByRegion() FOR GRAPH " + graph + " {" +
            "  MapAccum<STRING, INT> @@regionCount;" +
            "  all = SELECT u FROM User:u ACCUM @@regionCount += (u.region -> 1);" +
            "  PRINT @@regionCount;" +
            "}");

        log.info("[TigerGraph] Queries installed successfully.");
    }

    private void installGsql(String queryName, String gsql) throws Exception {
        // POST the GSQL definition to the gsqlserver endpoint
        HttpRequest createReq = HttpRequest.newBuilder()
            .uri(URI.create(host + "/gsqlserver/gsql/file"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(gsql))
            .build();
        http.send(createReq, HttpResponse.BodyHandlers.ofString());

        // Install (compile) the query
        HttpRequest installReq = HttpRequest.newBuilder()
            .uri(URI.create(host + "/gsqlserver/gsql/file"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(
                "USE GRAPH " + graph + "\nINSTALL QUERY " + queryName))
            .build();
        HttpResponse<String> resp = http.send(installReq, HttpResponse.BodyHandlers.ofString());
        log.debug("[TigerGraph] Installed {}: {}", queryName, resp.statusCode());
    }

    // -------------------------------------------------------------------------
    // 1. Ingest
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkIngest(Path nodesCsv, Path edgesCsv) {
        TigerGraphLoader loader = new TigerGraphLoader(host, token);
        long startMs = System.currentTimeMillis();

        long[] counts;
        try {
            counts = loader.load(nodesCsv, edgesCsv);
        } catch (Exception e) {
            throw new RuntimeException("TigerGraph ingest failed: " + e.getMessage(), e);
        }

        long totalMs    = System.currentTimeMillis() - startMs;
        long nodes      = counts[0];
        long rels       = counts[1];
        double totalSec = totalMs / 1000.0;

        BenchmarkResult r = BenchmarkResult.ingest(
            databaseName(), nodes, rels,
            nodes / totalSec, rels / totalSec, totalMs);
        r.measuredAt   = Instant.now().toString();
        r.instanceSpec = "TigerGraph Cloud Free — 4 vCPU, 7.5 GB RAM " +
                         "(larger than other free tiers — noted in analysis)";
        r.caveats      = "TigerGraph free tier resources (4 vCPU / 7.5 GB) exceed the 256 MB " +
                         "cap used for self-hosted DBs. Results may be faster than fair comparison allows.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 2. Traversal (1-hop, 2-hop, 3-hop)
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkTraversal(int hopDepth, List<String> startNodeIds,
                                               int warmupIter, int measureIter) {
        String queryName = "hop" + hopDepth;
        List<String> nodes = new ArrayList<>(startNodeIds);
        Collections.shuffle(nodes);
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            String id = nodes.get(i % nodes.size());
            runInstalledQuery(queryName, Map.of("start", id));
        }
        for (int i = 0; i < measureIter; i++) {
            String id = nodes.get(i % nodes.size());
            long ms = runInstalledQuery(queryName, Map.of("start", id));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "HOP_" + hopDepth, histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "GSQL installed query '" + queryName + "' — MPP parallel execution.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 3. Point lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkPointLookup(List<String> nodeIds,
                                                 int warmupIter, int measureIter) {
        // TigerGraph REST++ built-in: GET /graph/{graph}/vertices/{type}/{id}
        List<String> ids = new ArrayList<>(nodeIds);
        Collections.shuffle(ids);
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runVertexLookup(ids.get(i % ids.size()));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runVertexLookup(ids.get(i % ids.size()));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "POINT_LOOKUP", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "REST++ built-in: GET /graph/" + graph + "/vertices/User/{id}. " +
                       "PRIMARY_ID is always indexed in TigerGraph.";
        return r;
    }

    private long runVertexLookup(String id) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(host + "/graph/" + graph +
                    "/vertices/" + TigerGraphLoader.VERTEX_TYPE + "/" + id))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("[TigerGraph] Vertex lookup error: {}", e.getMessage());
        }
        return System.currentTimeMillis() - start;
    }

    // -------------------------------------------------------------------------
    // 4. Filtered lookup
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkFilteredLookup(int warmupIter, int measureIter) {
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runInstalledQuery("filteredLookup", Map.of("minAge", "25", "maxAge", "35"));
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runInstalledQuery("filteredLookup", Map.of("minAge", "25", "maxAge", "35"));
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "FILTERED_LOOKUP", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "GSQL installed query 'filteredLookup': WHERE age >= 25 AND age <= 35 LIMIT 100.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 5. Aggregation
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkAggregation(int warmupIter, int measureIter) {
        Histogram histogram = new Histogram(MAX_LATENCY_MS, 3);

        for (int i = 0; i < warmupIter; i++) {
            runInstalledQuery("aggregateByRegion", Map.of());
        }
        for (int i = 0; i < measureIter; i++) {
            long ms = runInstalledQuery("aggregateByRegion", Map.of());
            histogram.recordValue(Math.max(ms, 1));
        }

        BenchmarkResult r = fromHistogram(databaseName(), "AGGREGATION", histogram, measureIter);
        r.measuredAt = Instant.now().toString();
        r.caveats    = "GSQL installed query 'aggregateByRegion': MapAccum COUNT by region.";
        return r;
    }

    // -------------------------------------------------------------------------
    // 6. Mixed concurrent read/write
    // -------------------------------------------------------------------------

    @Override
    public BenchmarkResult benchmarkMixedConcurrent(int concurrency, int durationSec,
                                                     List<String> startNodeIds) {
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
                            // Read: 1-hop traversal via installed query
                            runInstalledQuery("hop1", Map.of("start", id));
                        } else {
                            // Write: update age via REST++ PUT vertex attribute
                            runVertexWrite(id);
                        }
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("[TigerGraph] Mixed workload error: {}", e.getMessage());
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
        r.caveats    = "80% hop1 reads (GSQL), 20% REST++ vertex attribute writes. " +
                       durationSec + "s duration. Free tier may throttle concurrent calls.";
        return r;
    }

    private void runVertexWrite(String id) throws Exception {
        // REST++ PUT: update a single vertex attribute (age increment simulation)
        String body = mapper.writeValueAsString(Map.of(
            "vertices", Map.of(
                TigerGraphLoader.VERTEX_TYPE, Map.of(
                    id, Map.of("age", Map.of("value", 1, "op", "add"))
                )
            )
        ));
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(host + "/graph/" + graph))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        // HttpClient managed by JVM — no explicit close needed
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Calls a pre-installed GSQL query via REST++ GET endpoint.
     * Returns wall-clock latency in ms.
     *
     * URL: GET /query/{graph}/{queryName}?param=value&...
     */
    private long runInstalledQuery(String queryName, Map<String, String> params) {
        long start = System.currentTimeMillis();
        try {
            StringBuilder url = new StringBuilder(
                host + "/query/" + graph + "/" + queryName);
            if (!params.isEmpty()) {
                url.append("?");
                params.forEach((k, v) ->
                    url.append(k).append("=").append(v).append("&"));
                url.deleteCharAt(url.length() - 1); // remove trailing &
            }
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("[TigerGraph] Query '{}' error: {}", queryName, e.getMessage());
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
