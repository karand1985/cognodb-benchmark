package ai.graphdb.benchmark.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and samples the SNAP soc-Pokec social network dataset.
 *
 * Pure Java replacement for download_pokec.sh — works on Windows, Mac, Linux
 * with no bash, chmod, or GNU coreutils required.
 *
 * Output (written to the data/ directory):
 *   pokec_nodes.csv  — 50,000 sampled user nodes  (id, gender, region, age)
 *   pokec_edges.csv  — 200,000 sampled FRIENDS_WITH edges (source_id, target_id)
 *
 * Run via Maven (recommended):
 *   mvn compile exec:java -Dexec.mainClass="ai.wexa.benchmark.data.DataDownloader"
 *
 * Or via the main JAR with --download flag:
 *   java -jar target/cognodb-benchmark-1.0.0.jar --download
 *
 * Or run the DataDownloader.main() directly from your IDE.
 */
public class DataDownloader {

    private static final Logger log = LoggerFactory.getLogger(DataDownloader.class);

    // SNAP dataset URLs
    private static final String PROFILES_URL  =
        "https://snap.stanford.edu/data/soc-pokec-profiles.txt.gz";
    private static final String RELATIONS_URL =
        "https://snap.stanford.edu/data/soc-pokec-relationships.txt.gz";

    // Sampling targets — sized to fit every free tier
    private static final int  TARGET_NODES = 50_000;
    private static final int  TARGET_EDGES = 200_000;

    // Fixed seed for reproducibility across runs and platforms
    private static final long RANDOM_SEED  = 42L;

    public static void main(String[] args) throws Exception {
        Path dataDir = resolveDataDir();
        log.info("Data directory: {}", dataDir.toAbsolutePath());
        new DataDownloader().run(dataDir);
    }

    public void run(Path dataDir) throws Exception {
        Files.createDirectories(dataDir);

        Path profilesGz  = dataDir.resolve("soc-pokec-profiles.txt.gz");
        Path relationsGz = dataDir.resolve("soc-pokec-relationships.txt.gz");
        Path nodesCsv    = dataDir.resolve("pokec_nodes.csv");
        Path edgesCsv    = dataDir.resolve("pokec_edges.csv");

        // --- Step 1: Download ---
        log.info("=== Step 1/4 — Downloading Pokec profiles (~400 MB) ===");
        download(PROFILES_URL, profilesGz);

        log.info("=== Step 2/4 — Downloading Pokec relationships (~200 MB) ===");
        download(RELATIONS_URL, relationsGz);

        // --- Step 2: Sample nodes ---
        log.info("=== Step 3/4 — Sampling {} nodes ===", TARGET_NODES);
        Set<String> sampledIds = sampleNodeIds(profilesGz);
        long nodesWritten = writeNodesCsv(profilesGz, sampledIds, nodesCsv);

        // --- Step 3: Sample edges ---
        log.info("=== Step 4/4 — Sampling up to {} edges ===", TARGET_EDGES);
        long edgesWritten = writeEdgesCsv(relationsGz, sampledIds, edgesCsv);

        // --- Summary ---
        log.info("============================================================");
        log.info("  Dataset ready.");
        log.info("  Nodes : {}  ->  {}", nodesWritten, nodesCsv.getFileName());
        log.info("  Edges : {}  ->  {}", edgesWritten, edgesCsv.getFileName());
        log.info("  You can delete the .gz files once CSV files are verified.");
        log.info("============================================================");
    }

    // -------------------------------------------------------------------------
    // Download with redirect-following and skip-if-exists
    // -------------------------------------------------------------------------

    private void download(String url, Path dest) throws Exception {
        if (Files.exists(dest) && Files.size(dest) > 1_000_000) {
            log.info("  Already downloaded: {} — skipping.", dest.getFileName());
            return;
        }
        log.info("  Downloading: {}", url);
        log.info("  Destination: {}", dest.toAbsolutePath());

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();

        // Stream directly to file — avoids loading 400 MB into memory
        HttpResponse<Path> response = client.send(
            request, HttpResponse.BodyHandlers.ofFile(dest));

        long bytes = Files.size(dest);
        log.info("  Download complete: {} MB", bytes / (1024 * 1024));
    }

    // -------------------------------------------------------------------------
    // Step 1: Read all node IDs, shuffle, return first TARGET_NODES as a Set
    // -------------------------------------------------------------------------

    private Set<String> sampleNodeIds(Path profilesGz) throws Exception {
        log.info("  Reading all node IDs from profiles...");
        List<String> allIds = new ArrayList<>(2_000_000);

        try (BufferedReader reader = gzipReader(profilesGz)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // Tab-separated: column 0 is user_id
                int tab = line.indexOf('\t');
                String id = (tab > 0)
                    ? line.substring(0, tab).trim()
                    : line.trim();
                if (!id.isEmpty()) allIds.add(id);
            }
        }

        log.info("  Total nodes in full dataset: {}", allIds.size());

        // Shuffle with fixed seed — same sample on every run
        Collections.shuffle(allIds, new Random(RANDOM_SEED));

        // Take first TARGET_NODES — LinkedHashSet preserves insertion order
        List<String> sampled = allIds.subList(
            0, Math.min(TARGET_NODES, allIds.size()));
        Set<String> sampledSet = new LinkedHashSet<>(sampled);
        log.info("  Sampled {} unique node IDs.", sampledSet.size());
        return sampledSet;
    }

    // -------------------------------------------------------------------------
    // Step 2: Write nodes CSV — rows whose id is in sampledIds
    // -------------------------------------------------------------------------

    private long writeNodesCsv(Path profilesGz, Set<String> sampledIds,
                                Path nodesCsv) throws Exception {
        log.info("  Writing nodes to {}...", nodesCsv.getFileName());
        long count = 0;

        try (BufferedReader reader = gzipReader(profilesGz);
             CSVPrinter printer = csvPrinter(nodesCsv, "id", "gender", "region", "age")) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                // Pokec profile format (tab-separated, no header row):
                // Col 0:  user_id
                // Col 1:  public
                // Col 2:  completion_percentage
                // Col 3:  gender
                // Col 4:  region
                // Col 5:  last_login
                // Col 6:  registration
                // Col 7:  AGE (years — primary age field)
                // Col 8:  body
                // Col 9:  I_am_working_in_field
                // ...many more columns
                String[] cols = line.split("\t", -1);
                if (cols.length < 5) continue;

                String id = cols[0].trim();
                if (!sampledIds.contains(id)) continue;

                String gender = sanitise(safeGet(cols, 3));
                String region = sanitise(safeGet(cols, 4));
                // col 7 is the age field in Pokec profiles
                String age    = safeInt(safeGet(cols, 7));

                printer.printRecord(id, gender, region, age);
                count++;
            }
        }

        log.info("  Written {} node rows.", count);
        return count;
    }

    // -------------------------------------------------------------------------
    // Step 3: Write edges CSV — both endpoints must be in sampledIds
    // -------------------------------------------------------------------------

    private long writeEdgesCsv(Path relationsGz, Set<String> sampledIds,
                                Path edgesCsv) throws Exception {
        log.info("  Scanning relationships — both endpoints must be in node sample...");
        List<String[]> qualifying = new ArrayList<>(TARGET_EDGES * 2);

        try (BufferedReader reader = gzipReader(relationsGz)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // Relations format: source_id TAB target_id
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) continue;
                String src = parts[0].trim();
                String dst = parts[1].trim();
                if (sampledIds.contains(src) && sampledIds.contains(dst)) {
                    qualifying.add(new String[]{ src, dst });
                }
            }
        }

        log.info("  Qualifying edges (both endpoints sampled): {}", qualifying.size());

        // Shuffle and limit to TARGET_EDGES for reproducibility
        Collections.shuffle(qualifying, new Random(RANDOM_SEED));
        List<String[]> selected = qualifying.subList(
            0, Math.min(TARGET_EDGES, qualifying.size()));

        long count = 0;
        try (CSVPrinter printer = csvPrinter(edgesCsv, "source_id", "target_id")) {
            for (String[] edge : selected) {
                printer.printRecord(edge[0], edge[1]);
                count++;
            }
        }

        log.info("  Written {} edge rows.", count);
        return count;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Open a gzip-compressed file for reading, with a large buffer */
    private BufferedReader gzipReader(Path gzFile) throws Exception {
        InputStream     raw = Files.newInputStream(gzFile);
        GZIPInputStream gz  = new GZIPInputStream(raw, 65_536);
        return new BufferedReader(new InputStreamReader(gz, "UTF-8"), 65_536);
    }

    /** Create a CSV printer with a header row */
    private CSVPrinter csvPrinter(Path file, String... headers) throws Exception {
        Writer writer = new BufferedWriter(new FileWriter(file.toFile(), false));
        return new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
            .setHeader(headers)
            .build());
    }

    /** Safely get column value — returns "" for missing or "null" values */
    private String safeGet(String[] cols, int idx) {
        if (idx >= cols.length) return "";
        String v = cols[idx].trim();
        return (v.equalsIgnoreCase("null") || v.isEmpty()) ? "" : v;
    }

    /** Remove characters that break CSV: commas, newlines, tabs */
    private String sanitise(String s) {
        return s.replace(",", ";")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\t", " ");
    }

    /** Return the value as a valid integer string, or "0" */
    private String safeInt(String s) {
        try {
            Integer.parseInt(s.trim());
            return s.trim();
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * Resolves the data/ directory relative to the project root.
     * Works whether launched from the project root, from target/,
     * or directly from an IDE run configuration.
     */
    private static Path resolveDataDir() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        // If running from target/ subdirectory, go up one level
        Path dataInCwd    = cwd.resolve("data");
        Path dataInParent = cwd.getParent() != null
            ? cwd.getParent().resolve("data")
            : dataInCwd;
        return Files.exists(dataInCwd) ? dataInCwd : dataInParent;
    }
}
