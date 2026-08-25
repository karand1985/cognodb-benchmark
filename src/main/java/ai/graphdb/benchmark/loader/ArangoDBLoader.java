package ai.graphdb.benchmark.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads the Pokec dataset into ArangoDB using the REST API (/_api/document).
 *
 * ArangoDB setup:
 *  - Multi-model database: supports both document and graph models.
 *  - Graph is managed via the Named Graph API (/_api/gharial).
 *  - Nodes  → stored as documents in the "users" vertex collection.
 *  - Edges  → stored as documents in the "friends" edge collection.
 *  - Both collections are part of a named graph called "pokec_graph".
 *  - REST API runs on port 8529 by default.
 *  - Auth: Basic (root / <ARANGO_ROOT_PASSWORD>).
 *
 * Batch loading uses /_api/document/{collection}?overwriteMode=replace
 * with an array body — ArangoDB accepts up to 10,000 docs per batch.
 * We use 500 to stay consistent with other loaders in this suite.
 */
public class ArangoDBLoader {

    private static final Logger log = LoggerFactory.getLogger(ArangoDBLoader.class);
    private static final int BATCH_SIZE = 500;

    // Collection and graph names used throughout the benchmark
    public static final String GRAPH_NAME      = "pokec_graph";
    public static final String VERTEX_COL      = "users";
    public static final String EDGE_COL        = "friends";
    public static final String DB_NAME         = "pokec";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;   // e.g. http://localhost:8529
    private final String authHeader; // "Basic <base64>"

    public ArangoDBLoader(String host, String password) {
        this.http       = HttpClient.newHttpClient();
        this.mapper     = new ObjectMapper();
        this.baseUrl    = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        String creds    = Base64.getEncoder().encodeToString(("root:" + password).getBytes());
        this.authHeader = "Basic " + creds;
    }

    /**
     * Creates the database, collections and graph, then loads nodes and edges.
     *
     * @return long[2] — { nodesLoaded, edgesLoaded }
     */
    public long[] load(Path nodesCsv, Path edgesCsv) throws Exception {
        setupDatabase();
        long nodes = loadNodes(nodesCsv);
        long edges = loadEdges(edgesCsv);
        createIndex();
        return new long[]{ nodes, edges };
    }

    // -------------------------------------------------------------------------
    // Schema setup
    // -------------------------------------------------------------------------

    private void setupDatabase() throws Exception {
        log.info("[ArangoDB] Setting up database and collections...");

        // Drop database if it exists (clean slate)
        delete("/_api/database/" + DB_NAME);

        // Create fresh database
        post("/_api/database", Map.of("name", DB_NAME));

        // Create vertex collection
        postToDb("/_api/collection", Map.of("name", VERTEX_COL, "type", 2));

        // Create edge collection (type 3 = edge)
        postToDb("/_api/collection", Map.of("name", EDGE_COL, "type", 3));

        // Create named graph linking vertex and edge collections
        Map<String, Object> edgeDef = Map.of(
            "collection", EDGE_COL,
            "from", List.of(VERTEX_COL),
            "to",   List.of(VERTEX_COL)
        );
        postToDb("/_api/gharial", Map.of(
            "name",         GRAPH_NAME,
            "edgeDefinitions", List.of(edgeDef)
        ));

        log.info("[ArangoDB] Database '{}', collections and graph '{}' created.", DB_NAME, GRAPH_NAME);
    }

    private void createIndex() throws Exception {
        log.info("[ArangoDB] Creating persistent index on users.id...");
        postToDb("/_api/index?collection=" + VERTEX_COL, Map.of(
            "type",   "persistent",
            "fields", List.of("id"),
            "unique", true
        ));
        log.info("[ArangoDB] Index created.");
    }

    // -------------------------------------------------------------------------
    // Node loading
    // -------------------------------------------------------------------------

    private long loadNodes(Path nodesCsv) throws Exception {
        log.info("[ArangoDB] Loading nodes from {}...", nodesCsv.getFileName());
        long total = 0;

        try (Reader reader = new FileReader(nodesCsv.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

            for (CSVRecord record : records) {
                // _key in ArangoDB must be a string; use the Pokec user id
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("_key",  record.get("id"));
                doc.put("id",    record.get("id"));
                doc.put("gender", record.get("gender"));
                doc.put("region", record.get("region"));
                doc.put("age",    safeInt(record.get("age")));
                batch.add(doc);

                if (batch.size() == BATCH_SIZE) {
                    flushNodeBatch(batch);
                    total += batch.size();
                    batch.clear();
                    log.debug("[ArangoDB] Nodes loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushNodeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[ArangoDB] Loaded {} nodes.", total);
        return total;
    }

    private void flushNodeBatch(List<Map<String, Object>> batch) throws Exception {
        // POST array of docs to vertex collection
        postToDb("/_api/document/" + VERTEX_COL + "?overwriteMode=replace",
            batch);
    }

    // -------------------------------------------------------------------------
    // Edge loading
    // -------------------------------------------------------------------------

    private long loadEdges(Path edgesCsv) throws Exception {
        log.info("[ArangoDB] Loading edges from {}...", edgesCsv.getFileName());
        long total = 0;

        try (Reader reader = new FileReader(edgesCsv.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

            for (CSVRecord record : records) {
                String src = record.get("source_id");
                String dst = record.get("target_id");
                // ArangoDB edge documents require _from and _to as collection/key
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("_from", VERTEX_COL + "/" + src);
                edge.put("_to",   VERTEX_COL + "/" + dst);
                batch.add(edge);

                if (batch.size() == BATCH_SIZE) {
                    flushEdgeBatch(batch);
                    total += batch.size();
                    batch.clear();
                    log.debug("[ArangoDB] Edges loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushEdgeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[ArangoDB] Loaded {} edges.", total);
        return total;
    }

    private void flushEdgeBatch(List<Map<String, Object>> batch) throws Exception {
        postToDb("/_api/document/" + EDGE_COL + "?overwriteMode=replace",
            batch);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    /** POST to the _system database (for database-level operations) */
    private void post(String path, Object body) throws Exception {
        String url = baseUrl + path;
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** POST to the pokec database */
    void postToDb(String path, Object body) throws Exception {
        String url = baseUrl + "/_db/" + DB_NAME + path;
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            log.warn("[ArangoDB] POST {} returned {}: {}", path, resp.statusCode(), resp.body());
        }
    }

    /** DELETE (used for dropping the database on reset) */
    private void delete(String path) throws Exception {
        String url = baseUrl + path;
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .DELETE()
            .build();
        http.send(req, HttpResponse.BodyHandlers.ofString()); // ignore 404
    }

    /** GET to the pokec database — returns response body as String */
    String getFromDb(String path) throws Exception {
        String url = baseUrl + "/_db/" + DB_NAME + path;
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .GET()
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // Expose for benchmark queries
    HttpClient httpClient()   { return http; }
    ObjectMapper objectMapper() { return mapper; }
    String baseUrl()          { return baseUrl; }
    String authHeader()       { return authHeader; }

    private int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }
}
