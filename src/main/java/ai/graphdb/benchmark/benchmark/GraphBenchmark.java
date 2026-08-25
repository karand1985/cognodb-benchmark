package ai.graphdb.benchmark.benchmark;

import java.nio.file.Path;
import java.util.List;

import ai.graphdb.benchmark.model.BenchmarkResult;

/**
 * Contract that every database benchmark implementation must satisfy.
 *
 * Each method corresponds to one required metric category from the assignment.
 * Implementations live in the benchmark/ package:
 *   CognoDBBenchmark, Neo4jAuraBenchmark, FalkorDBBenchmark,
 *   NeptuneBenchmark, TigerGraphBenchmark.
 *
 * BenchmarkRunner calls these methods in order for every registered database.
 */
public interface GraphBenchmark {

    /**
     * Human-readable name of the database being benchmarked.
     * Used in result labels and CSV/JSON output.
     * e.g. "CognoDB Cloud", "Neo4j AuraDB Free", "FalkorDB"
     */
    String databaseName();

    /**
     * Verify connectivity before running any workload.
     * Should throw a descriptive RuntimeException if the connection fails
     * so the runner can skip this DB and report the error clearly.
     */
    void verifyConnection();

    /**
     * Drop all existing data and reload the dataset from scratch.
     * Measures and returns ingest throughput (nodes/sec, rels/sec, total time).
     *
     * @param nodesCsv  Path to pokec_nodes.csv (header: id,gender,region,age)
     * @param edgesCsv  Path to pokec_edges.csv (header: source_id,target_id)
     */
    BenchmarkResult benchmarkIngest(Path nodesCsv, Path edgesCsv);

    /**
     * Run N-hop traversal queries from a random set of start nodes.
     * Warm up first, then measure latency over measureIterations iterations.
     *
     * @param hopDepth        1, 2, or 3
     * @param startNodeIds    randomly chosen node IDs to traverse from
     * @param warmupIter      iterations to discard before measuring
     * @param measureIter     iterations to measure
     */
    BenchmarkResult benchmarkTraversal(int hopDepth,
                                       List<String> startNodeIds,
                                       int warmupIter,
                                       int measureIter);

    /**
     * Point lookup: find a single node by its indexed id property.
     * p50 and p95 latency in ms.
     *
     * @param nodeIds         randomly chosen node IDs to look up
     * @param warmupIter      warm-up iterations
     * @param measureIter     measured iterations
     */
    BenchmarkResult benchmarkPointLookup(List<String> nodeIds,
                                         int warmupIter,
                                         int measureIter);

    /**
     * Filtered lookup: find all nodes matching a property range filter
     * (e.g. users aged 25–35). Reports p50/p95 latency.
     *
     * @param warmupIter   warm-up iterations
     * @param measureIter  measured iterations
     */
    BenchmarkResult benchmarkFilteredLookup(int warmupIter, int measureIter);

    /**
     * Aggregation: count users grouped by region.
     * Reports p50/p95 latency across repeated executions.
     *
     * @param warmupIter   warm-up iterations
     * @param measureIter  measured iterations
     */
    BenchmarkResult benchmarkAggregation(int warmupIter, int measureIter);

    /**
     * Mixed concurrent read/write workload.
     * Runs 80% read (random traversal) and 20% write (update a property)
     * with the given number of concurrent client threads.
     * Reports sustained queries/second.
     *
     * @param concurrency    number of concurrent client threads
     * @param durationSec    how long to sustain the load (seconds)
     * @param startNodeIds   node IDs available for reads and writes
     */
    BenchmarkResult benchmarkMixedConcurrent(int concurrency,
                                             int durationSec,
                                             List<String> startNodeIds);

    /**
     * Clean up connections and release any resources held by this benchmark.
     * Called by BenchmarkRunner in a finally block.
     */
    void close();
}
