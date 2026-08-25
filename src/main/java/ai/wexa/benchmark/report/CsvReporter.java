package ai.wexa.benchmark.report;

import ai.wexa.benchmark.model.BenchmarkResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes BenchmarkResult objects to a CSV file under the results/ directory.
 * One row per result. All columns present for all workload types;
 * inapplicable fields are written as empty strings.
 */
public class CsvReporter {

    private static final Logger log = LoggerFactory.getLogger(CsvReporter.class);

    private static final String[] HEADERS = {
        "database", "workload", "measured_at",
        "p50_ms", "p95_ms", "min_ms", "max_ms", "mean_ms", "iteration_count",
        "nodes_per_sec", "relationships_per_sec", "total_load_ms",
        "total_nodes_loaded", "total_relationships_loaded",
        "queries_per_sec", "concurrent_clients", "read_percent",
        "stored_data_size", "instance_spec", "caveats"
    };

    private final Path resultsDir;

    public CsvReporter(Path resultsDir) {
        this.resultsDir = resultsDir;
    }

    /**
     * Write all results to results/results_<timestamp>.csv
     *
     * @return the path of the written file
     */
    public Path write(List<BenchmarkResult> results) throws IOException {
        Files.createDirectories(resultsDir);

        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path outputPath = resultsDir.resolve("results_" + timestamp + ".csv");

        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader(HEADERS)
            .build();

        try (CSVPrinter printer = new CSVPrinter(new FileWriter(outputPath.toFile()), format)) {
            for (BenchmarkResult r : results) {
                printer.printRecord(
                    r.database,
                    r.workload,
                    r.measuredAt,
                    fmt(r.p50Ms),
                    fmt(r.p95Ms),
                    fmt(r.minMs),
                    fmt(r.maxMs),
                    fmt(r.meanMs),
                    r.iterationCount > 0 ? r.iterationCount : "",
                    fmt(r.nodesPerSecond),
                    fmt(r.relationshipsPerSecond),
                    fmt(r.totalLoadMs),
                    r.totalNodesLoaded > 0 ? r.totalNodesLoaded : "",
                    r.totalRelationshipsLoaded > 0 ? r.totalRelationshipsLoaded : "",
                    fmt(r.queriesPerSecond),
                    r.concurrentClients > 0 ? r.concurrentClients : "",
                    r.readPercent > 0 ? r.readPercent : "",
                    r.storedDataSize,
                    r.instanceSpec,
                    r.caveats
                );
            }
        }

        log.info("CSV results written to: {}", outputPath.toAbsolutePath());
        return outputPath;
    }

    /** Format a double; return empty string for sentinel -1 values */
    private String fmt(double value) {
        if (value < 0) return "";
        return String.format("%.3f", value);
    }
}
