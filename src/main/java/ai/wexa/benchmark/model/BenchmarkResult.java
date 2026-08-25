package ai.wexa.benchmark.model;

/**
 * Holds the result of one workload measurement for one database.
 *
 * Latency fields are in milliseconds.
 * Throughput fields are in operations/second.
 * Fields not applicable to a given workload are left as -1.
 */
public class BenchmarkResult {

    // --- Identity ------------------------------------------------------------

    /** Database name, e.g. "CognoDB", "Neo4j AuraDB", "FalkorDB" */
    public String database;

    /** Workload label, e.g. "HOP_2", "MIXED_C10", "INGEST" */
    public String workload;

    /** ISO-8601 timestamp when this measurement was taken */
    public String measuredAt;

    // --- Latency (ms) — for read workloads -----------------------------------

    public double p50Ms  = -1;
    public double p95Ms  = -1;
    public double minMs  = -1;
    public double maxMs  = -1;
    public double meanMs = -1;

    /** Number of iterations actually measured (after warm-up) */
    public int iterationCount = -1;

    // --- Ingest throughput ---------------------------------------------------

    public double nodesPerSecond        = -1;
    public double relationshipsPerSecond = -1;

    /** Total wall-clock load time in milliseconds */
    public double totalLoadMs = -1;

    public long totalNodesLoaded        = -1;
    public long totalRelationshipsLoaded = -1;

    // --- Mixed concurrent workload -------------------------------------------

    /** Sustained queries/second across all clients */
    public double queriesPerSecond = -1;

    /** Number of concurrent clients used for this measurement */
    public int concurrentClients = -1;

    /** Read percentage in the mixed workload (e.g. 80 means 80% reads) */
    public int readPercent = -1;

    // --- Resource footprint (where observable) -------------------------------

    /**
     * Reported storage used after load, e.g. "512 MB".
     * Set to "not observable" when the platform does not expose this.
     */
    public String storedDataSize = "not observable";

    /**
     * Instance spec as advertised by the platform,
     * e.g. "0.5 vCPU, 256 MB RAM, 1 GB disk"
     */
    public String instanceSpec = "not observable";

    // --- Caveats -------------------------------------------------------------

    /**
     * Any honest notes about this measurement:
     * throttling observed, timeouts, query-language differences, etc.
     */
    public String caveats = "";

    // --- Constructors --------------------------------------------------------

    public BenchmarkResult() {}

    /** Convenience constructor for latency results */
    public static BenchmarkResult latency(String database, String workload,
                                          double p50, double p95,
                                          double min, double max, double mean,
                                          int iterations) {
        BenchmarkResult r = new BenchmarkResult();
        r.database       = database;
        r.workload       = workload;
        r.p50Ms          = p50;
        r.p95Ms          = p95;
        r.minMs          = min;
        r.maxMs          = max;
        r.meanMs         = mean;
        r.iterationCount = iterations;
        return r;
    }

    /** Convenience constructor for ingest results */
    public static BenchmarkResult ingest(String database,
                                         long nodes, long rels,
                                         double nodesPerSec, double relsPerSec,
                                         double totalMs) {
        BenchmarkResult r = new BenchmarkResult();
        r.database                  = database;
        r.workload                  = "INGEST";
        r.totalNodesLoaded          = nodes;
        r.totalRelationshipsLoaded  = rels;
        r.nodesPerSecond            = nodesPerSec;
        r.relationshipsPerSecond    = relsPerSec;
        r.totalLoadMs               = totalMs;
        return r;
    }

    /** Convenience constructor for mixed concurrent workload results */
    public static BenchmarkResult mixed(String database, int concurrency,
                                        double qps, int readPct) {
        BenchmarkResult r   = new BenchmarkResult();
        r.database          = database;
        r.workload          = "MIXED_C" + concurrency;
        r.concurrentClients = concurrency;
        r.queriesPerSecond  = qps;
        r.readPercent       = readPct;
        return r;
    }

    @Override
    public String toString() {
        return String.format("[%s | %s] p50=%.1f ms  p95=%.1f ms  qps=%.1f  nodesPerSec=%.1f",
            database, workload, p50Ms, p95Ms, queriesPerSecond, nodesPerSecond);
    }
}
