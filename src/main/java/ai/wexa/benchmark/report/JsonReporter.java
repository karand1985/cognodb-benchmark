package ai.wexa.benchmark.report;

import ai.wexa.benchmark.model.BenchmarkResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes BenchmarkResult objects to a pretty-printed JSON file
 * under the results/ directory.
 */
public class JsonReporter {

    private static final Logger log = LoggerFactory.getLogger(JsonReporter.class);

    private final Path resultsDir;
    private final ObjectMapper mapper;

    public JsonReporter(Path resultsDir) {
        this.resultsDir = resultsDir;
        this.mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Write all results to results/results_<timestamp>.json
     *
     * @return the path of the written file
     */
    public Path write(List<BenchmarkResult> results) throws IOException {
        Files.createDirectories(resultsDir);

        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path outputPath = resultsDir.resolve("results_" + timestamp + ".json");

        // Wrap in a top-level envelope with metadata
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("benchmarkSuite",  "Graph Database Cloud Benchmark");
        envelope.put("generatedAt",     LocalDateTime.now().toString());
        envelope.put("totalResults",    results.size());
        envelope.put("results",         results);

        mapper.writeValue(outputPath.toFile(), envelope);

        log.info("JSON results written to: {}", outputPath.toAbsolutePath());
        return outputPath;
    }
}
