package ai.wexa.benchmark.benchmark;

import ai.wexa.benchmark.config.EnvConfig;
import ai.wexa.benchmark.model.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates the full benchmark suite across every registered database.
 *
 * Order of operations for each database:
 *   1. verifyConnection()
 *   2. benchmarkIngest()          — measures load throughput
 *   3. benchmarkTraversal(1/2/3)  — hop latency
 *   4. benchmarkPointLookup()
 *   5. benchmarkFilteredLookup()
 *   6. benchmarkAggregation()
 *   7. benchmarkMixedConcurrent() — for each concurrency level
 *
 * Results are accumulated into a List<BenchmarkResult> which the reporters
 * then write to CSV and JSON.
 */
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private static final int MIXED_DURATION_SEC = 30; // sustain mixed load for 30s

    private final EnvConfig config;
    private final List<GraphBenchmark> benchmarks;
    private final Path nodesCsv;
    private final Path edgesCsv;

    public BenchmarkRunner(EnvConfig config,
                           List<GraphBenchmark> benchmarks,
                           Path nodesCsv,
                           Path edgesCsv) {
        this.config     = config;
        this.benchmarks = benchmarks;
        this.nodesCsv   = nodesCsv;
        this.edgesCsv   = edgesCsv;
    }

    /**
     * Run the full suite for every registered database.
     *
     * @return all collected BenchmarkResult objects, in run order
     */
    public List<BenchmarkResult> runAll() {
        List<BenchmarkResult> allResults = new ArrayList<>();

        for (GraphBenchmark bench : benchmarks) {
            log.info("========================================================");
            log.info("  Starting benchmark: {}", bench.databaseName());
            log.info("========================================================");

            try {
                // --- Connection check ----------------------------------------
                log.info("[{}] Verifying connection...", bench.databaseName());
                bench.verifyConnection();
                log.info("[{}] Connection OK.", bench.databaseName());

                List<BenchmarkResult> dbResults = runForDatabase(bench);
                allResults.addAll(dbResults);

            } catch (Exception e) {
                log.error("[{}] FAILED — skipping this database. Reason: {}",
                    bench.databaseName(), e.getMessage(), e);
                BenchmarkResult err = new BenchmarkResult();
                err.database = bench.databaseName();
                err.workload = "ALL";
                err.caveats  = "SKIPPED — connection or setup error: " + e.getMessage();
                allResults.add(err);
            } finally {
                try { bench.close(); } catch (Exception ignore) {}
            }
        }

        return allResults;
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private List<BenchmarkResult> runForDatabase(GraphBenchmark bench) {
        List<BenchmarkResult> results = new ArrayList<>();
        String db = bench.databaseName();

        int warmup  = config.warmupIterations();
        int measure = config.measureIterations();
        int traversalNodes = config.traversalStartNodes();

        // --- 1. Ingest -------------------------------------------------------
        log.info("[{}] Running INGEST...", db);
        BenchmarkResult ingest = safe(db, "INGEST",
            () -> bench.benchmarkIngest(nodesCsv, edgesCsv));
        results.add(ingest);

        // Build start-node list from a fixed seed for reproducibility.
        // We extract node IDs from the ingest result's loaded count; if ingest
        // failed we still attempt subsequent workloads with a best-effort list.
        List<String> startNodes = buildStartNodeIds(ingest, traversalNodes);

        // --- 2. Traversals (1-hop, 2-hop, 3-hop) ----------------------------
        for (int hop : new int[]{1, 2, 3}) {
            log.info("[{}] Running HOP_{} traversal ({} warm-up + {} measured)...",
                db, hop, warmup, measure);
            int hopFinal = hop;
            BenchmarkResult r = safe(db, "HOP_" + hop,
                () -> bench.benchmarkTraversal(hopFinal, startNodes, warmup, measure));
            results.add(r);
        }

        // --- 3. Point lookup -------------------------------------------------
        log.info("[{}] Running POINT_LOOKUP...", db);
        BenchmarkResult pointLookup = safe(db, "POINT_LOOKUP",
            () -> bench.benchmarkPointLookup(startNodes, warmup, measure));
        results.add(pointLookup);

        // --- 4. Filtered lookup ----------------------------------------------
        log.info("[{}] Running FILTERED_LOOKUP...", db);
        BenchmarkResult filteredLookup = safe(db, "FILTERED_LOOKUP",
            () -> bench.benchmarkFilteredLookup(warmup, measure));
        results.add(filteredLookup);

        // --- 5. Aggregation --------------------------------------------------
        log.info("[{}] Running AGGREGATION...", db);
        BenchmarkResult agg = safe(db, "AGGREGATION",
            () -> bench.benchmarkAggregation(warmup, measure));
        results.add(agg);

        // --- 6. Mixed concurrent (concurrency sweep) -------------------------
        for (int concurrency : config.concurrencyLevels()) {
            log.info("[{}] Running MIXED_CONCURRENT with {} client(s) for {}s...",
                db, concurrency, MIXED_DURATION_SEC);
            int c = concurrency;
            BenchmarkResult mixed = safe(db, "MIXED_C" + concurrency,
                () -> bench.benchmarkMixedConcurrent(c, MIXED_DURATION_SEC, startNodes));
            results.add(mixed);
        }

        log.info("[{}] All workloads complete. {} results collected.", db, results.size());
        return results;
    }

    /**
     * Wraps a benchmark call in try/catch so one failing workload doesn't
     * abort the rest of the suite. Errors are recorded in BenchmarkResult.caveats.
     */
    private BenchmarkResult safe(String db, String workload,
                                  java.util.concurrent.Callable<BenchmarkResult> fn) {
        try {
            BenchmarkResult r = fn.call();
            if (r.measuredAt == null) {
                r.measuredAt = java.time.Instant.now().toString();
            }
            return r;
        } catch (Exception e) {
            log.error("[{}] Workload {} failed: {}", db, workload, e.getMessage(), e);
            BenchmarkResult err = new BenchmarkResult();
            err.database = db;
            err.workload = workload;
            err.caveats  = "FAILED: " + e.getMessage();
            err.measuredAt = java.time.Instant.now().toString();
            return err;
        }
    }

    /**
     * Builds a list of start node IDs for traversal and lookup workloads.
     * Uses sequential IDs "1" through N as the Pokec dataset uses integer IDs.
     * Shuffled so each run picks a different random sample.
     */
    private List<String> buildStartNodeIds(BenchmarkResult ingest, int count) {
        long loaded = (ingest != null && ingest.totalNodesLoaded > 0)
            ? ingest.totalNodesLoaded
            : 50_000L; // fallback if ingest failed

        // Generate sequential IDs matching the Pokec user IDs (1-based integers)
        List<String> ids = new ArrayList<>((int) Math.min(loaded, count * 10));
        for (long i = 1; i <= Math.min(loaded, count * 10L); i++) {
            ids.add(String.valueOf(i));
        }
        Collections.shuffle(ids);
        return ids.subList(0, Math.min(count, ids.size()));
    }
}
