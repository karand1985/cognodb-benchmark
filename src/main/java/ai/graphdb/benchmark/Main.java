package ai.graphdb.benchmark;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.graphdb.benchmark.benchmark.ArangoDBBenchmark;
import ai.graphdb.benchmark.benchmark.BenchmarkRunner;
import ai.graphdb.benchmark.benchmark.CognoDBBenchmark;
import ai.graphdb.benchmark.benchmark.FalkorDBBenchmark;
import ai.graphdb.benchmark.benchmark.GraphBenchmark;
import ai.graphdb.benchmark.benchmark.Neo4jAuraBenchmark;
import ai.graphdb.benchmark.benchmark.TigerGraphBenchmark;
import ai.graphdb.benchmark.config.EnvConfig;
import ai.graphdb.benchmark.data.DataDownloader;
import ai.graphdb.benchmark.model.BenchmarkResult;
import ai.graphdb.benchmark.report.CsvReporter;
import ai.graphdb.benchmark.report.JsonReporter;

/**
 * Entry point for the Graph Database Cloud Benchmark.
 *
 * Usage:
 *   java -jar target/cognodb-benchmark-1.0.0.jar [options]
 *
 * Options (can be combined):
 *   --all           Run loaders + benchmarks for every database (default)
 *   --db cognodb    Run only CognoDB
 *   --db neo4j      Run only Neo4j AuraDB
 *   --db falkor     Run only FalkorDB
 *   --db neptune    Run only Amazon Neptune
 *   --db tigergraph Run only TigerGraph
 *   --skip-ingest   Skip the data loading phase (data already loaded)
 *   --help          Print this help and exit
 *
 * Credentials are read from environment variables (or a .env file).
 * See .env.example for the full list of required variables.
 *
 * Dataset files are expected at:
 *   data/pokec_nodes.csv
 *   data/pokec_edges.csv
 * Run data/download_pokec.sh first to generate them.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        List<String> argList = Arrays.asList(args);

        if (argList.contains("--help")) {
            printHelp();
            System.exit(0);
        }

        // --download: run the Java dataset downloader then exit
        // Works on Windows, Mac, Linux — no bash or chmod needed
        if (argList.contains("--download")) {
            Path dataDir = Paths.get(System.getProperty("user.dir")).resolve("data");
            try {
                new DataDownloader().run(dataDir);
            } catch (Exception e) {
                log.error("Dataset download failed: {}", e.getMessage(), e);
                System.exit(1);
            }
            System.exit(0);
        }

        log.info("=============================================================");
        log.info("  Graph Database Cloud Benchmark — starting");
        log.info("=============================================================");

        // --- Config ----------------------------------------------------------
        EnvConfig config = new EnvConfig();

        // --- Dataset paths ---------------------------------------------------
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path nodesCsv    = projectRoot.resolve("data/pokec_nodes.csv");
        Path edgesCsv    = projectRoot.resolve("data/pokec_edges.csv");
        Path resultsDir  = projectRoot.resolve("results");

        validateDatasetFiles(nodesCsv, edgesCsv);

        // --- Select databases to benchmark -----------------------------------
        List<GraphBenchmark> benchmarks = selectBenchmarks(argList, config);
        if (benchmarks.isEmpty()) {
            log.error("No databases selected. Use --all or --db <name>. Exiting.");
            System.exit(1);
        }

        log.info("Databases to benchmark: {}", benchmarks.stream()
            .map(GraphBenchmark::databaseName).toList());

        // --- Run -------------------------------------------------------------
        BenchmarkRunner runner = new BenchmarkRunner(config, benchmarks, nodesCsv, edgesCsv);
        List<BenchmarkResult> results = runner.runAll();

        // --- Report ----------------------------------------------------------
        try {
            CsvReporter  csvReporter  = new CsvReporter(resultsDir);
            JsonReporter jsonReporter = new JsonReporter(resultsDir);

            Path csvPath  = csvReporter.write(results);
            Path jsonPath = jsonReporter.write(results);

            log.info("=============================================================");
            log.info("  Benchmark complete. {} results written.", results.size());
            log.info("  CSV  → {}", csvPath.toAbsolutePath());
            log.info("  JSON → {}", jsonPath.toAbsolutePath());
            log.info("=============================================================");

        } catch (Exception e) {
            log.error("Failed to write results: {}", e.getMessage(), e);
            System.exit(2);
        }
        
        return;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Instantiates benchmark implementations based on --db / --all flags.
     * Each implementation reads its own credentials from EnvConfig.
     */
    private static List<GraphBenchmark> selectBenchmarks(List<String> args, EnvConfig config) {
        List<GraphBenchmark> selected = new ArrayList<>();

        boolean all       = args.contains("--all") || !args.contains("--db");
        int dbIdx         = args.indexOf("--db");
        String dbFilter   = (dbIdx >= 0 && dbIdx + 1 < args.size())
            ? args.get(dbIdx + 1).toLowerCase()
            : "";

        // Active benchmark suite — 4 databases, all genuinely free, no trial expiry
        if (all || dbFilter.equals("cognodb")) selected.add(new CognoDBBenchmark(config));
        if (all || dbFilter.equals("neo4j"))   selected.add(new Neo4jAuraBenchmark(config));
        if (all || dbFilter.equals("falkor"))  selected.add(new FalkorDBBenchmark(config));
        if (all || dbFilter.equals("arango"))  selected.add(new ArangoDBBenchmark(config));

        // TigerGraph is implemented (TigerGraphBenchmark / TigerGraphLoader) but excluded
        // from --all: its free tier is credit-limited, not permanently free like the 4 above.
        // Run manually if needed: java -jar benchmark.jar --db tigergraph
        if (dbFilter.equals("tigergraph")) selected.add(new TigerGraphBenchmark(config));

        return selected;
    }

    private static void validateDatasetFiles(Path nodesCsv, Path edgesCsv) {
        if (!nodesCsv.toFile().exists()) {
            log.error("Dataset file not found: {}", nodesCsv.toAbsolutePath());
            log.error("Run  data/download_pokec.sh  first to generate the dataset.");
            System.exit(1);
        }
        if (!edgesCsv.toFile().exists()) {
            log.error("Dataset file not found: {}", edgesCsv.toAbsolutePath());
            log.error("Run  data/download_pokec.sh  first to generate the dataset.");
            System.exit(1);
        }
        log.info("Dataset files found: {} | {}", nodesCsv.getFileName(), edgesCsv.getFileName());
    }

    private static void printHelp() {
        System.out.println("""
            Graph Database Cloud Benchmark
            ================================
            Usage: java -jar target/cognodb-benchmark-1.0.0.jar [options]

            Options:
              --download         Download and sample the Pokec dataset (run this first)
              --all              Run all 4 active databases (default if no --db given)
              --db cognodb       Run CognoDB Cloud only
              --db neo4j         Run Neo4j AuraDB Free only
              --db falkor        Run FalkorDB only
              --db arango        Run ArangoDB only
              --db tigergraph    Run TigerGraph only (excluded from --all; credit-limited tier)
              --help             Show this help

            Environment variables (see .env.example):
              COGNODB_URI, COGNODB_PASSWORD
              NEO4J_AURA_URI, NEO4J_AURA_PASSWORD
              FALKORDB_URI
              NEPTUNE_ENDPOINT
              TIGERGRAPH_HOST, TIGERGRAPH_TOKEN

            Dataset:
              Run data/download_pokec.sh before the first run to
              generate data/pokec_nodes.csv and data/pokec_edges.csv.
            """);
    }
}
