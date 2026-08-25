package ai.graphdb.benchmark.model;

/**
 * All benchmark workload categories required by the assignment.
 * Each maps to exactly one measured metric in the results matrix.
 */
public enum WorkloadType {

    /** Data loading — nodes/sec, rels/sec, total wall-clock ms */
    INGEST,

    /** Traversal — 1-hop neighbour lookup from a random start node */
    HOP_1,

    /** Traversal — 2-hop neighbour lookup from a random start node */
    HOP_2,

    /** Traversal — 3-hop neighbour lookup from a random start node */
    HOP_3,

    /** Lookup — find a single node by its indexed id property */
    POINT_LOOKUP,

    /** Lookup — find nodes matching a property filter (e.g. age range) */
    FILTERED_LOOKUP,

    /** Aggregation — count nodes grouped by a property (e.g. region) */
    AGGREGATION,

    /**
     * Mixed concurrent read/write throughput.
     * Reported separately for each concurrency level (1, 10, 40 clients).
     * The workloadType in BenchmarkResult carries the concurrency suffix,
     * e.g. "MIXED_C1", "MIXED_C10", "MIXED_C40".
     */
    MIXED_CONCURRENT
}
