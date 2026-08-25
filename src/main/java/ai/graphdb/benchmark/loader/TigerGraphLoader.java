package ai.graphdb.benchmark.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads the Pokec dataset into TigerGraph Cloud using the REST++ API.
 *
 * TigerGraph setup:
 *  - Cloud free tier: sign up at https://tgcloud.io — free instance.
 *  - REST++ API runs on port 9000 (or via HTTPS on the cloud host).
 *  - Authentication: Bearer token (obtained from the TigerGraph console).
 *  - Graph schema must be created before loading — this loader calls
 *    the Schema Change Job API to create the graph programmatically.
 *  - Nodes  → TigerGraph vertex type "User"
 *  - Edges  → TigerGraph edge type "FRIENDS_WITH" (directed)
 *  - Batch loading via POST /graph/{graph_name}/vertices and /edges.
 *    TigerGraph REST++ accepts one vertex/edge per request or a JSON array.
 *    We use the upsert endpoint which handles batches natively.
 *
 * NOTE: TigerGraph requires a schema (vertex/edge type definitions) to
 * exist before data can be loaded. The setupSchema() method uses the
 * GSQL endpoint to create the schema if it does not already exist.
 * Run this loader once before benchmarking.
 */
public class TigerGraphLoader {

    private static final Logger log = LoggerFactory.getLogger(TigerGraphLoader.class);
    private static final int BATCH_SIZE = 200; // TigerGraph REST++ upsert batch

    public static final String GRAPH_NAME   = "PokecGraph";
    public static final String VERTEX_TYPE  = "User";
    public static final String EDGE_TYPE    = "FRIENDS_WITH";

    private final HttpClient   http;
    private final ObjectMapper mapper;
    private final String       host;    // e.g. https://xyz.i.tgcloud.io
    private final String       token;   // Bearer token from TigerGraph console

    public TigerGraphLoader(String host, String token) {
        this.http   = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
        this.host   = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.token  = token;
    }

    /**
     * Creates graph schema (if needed), clears existing data, loads nodes and edges.
     *
     * @return long[2] — { nodesLoaded, edgesLoaded }
     */
    public long[] load(Path nodesCsv, Path edgesCsv) throws Exception {
        setupSchema();
        clearGraph();
        long nodes = loadNodes(nodesCsv);
        long edges = loadEdges(edgesCsv);
        return new long[]{ nodes, edges };
    }

    // -------------------------------------------------------------------------
    // Schema setup via GSQL endpoint
    // -------------------------------------------------------------------------

    private void setupSchema() throws Exception {
        log.info("[TigerGraph] Creating graph schema (idempotent)...");

        // GSQL script to define the graph schema
        String gsql = String.join("\n",
            "USE GLOBAL",
            "CREATE VERTEX " + VERTEX_TYPE + " (PRIMARY_ID id STRING, " +
                "gender STRING, region STRING, age INT) WITH primary_id_as_attribute=\"true\"",
            "CREATE DIRECTED EDGE " + EDGE_TYPE + " (FROM " + VERTEX_TYPE +
                ", TO " + VERTEX_TYPE + ")",
            "CREATE GRAPH " + GRAPH_NAME + " (" + VERTEX_TYPE + ", " + EDGE_TYPE + ")"
        );

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(host + "/gsqlserver/gsql/file"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(gsql))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        // Schema may already exist — 400 with "already exists" is acceptable
        if (resp.statusCode() >= 400 && !resp.body().contains("already exists")) {
            log.warn("[TigerGraph] Schema setup returned {}: {}", resp.statusCode(), resp.body());
        } else {
            log.info("[TigerGraph] Schema ready.");
        }
    }

    // -------------------------------------------------------------------------
    // Clear existing data
    // -------------------------------------------------------------------------

    private void clearGraph() throws Exception {
        log.info("[TigerGraph] Clearing existing graph data...");
        // DELETE all vertices of type User (cascades to edges in TigerGraph)
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(host + "/graph/" + GRAPH_NAME +
                "/vertices/" + VERTEX_TYPE))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("[TigerGraph] Clear response: {}", resp.statusCode());
    }

    // -------------------------------------------------------------------------
    // Node loading
    // -------------------------------------------------------------------------

    private long loadNodes(Path nodesCsv) throws Exception {
        log.info("[TigerGraph] Loading nodes from {}...", nodesCsv.getFileName());
        long total = 0;

        try (Reader reader = new FileReader(nodesCsv.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            // TigerGraph upsert body: { "vertices": { "User": { "<id>": { ... } } } }
            Map<String, Object> vertexMap = new LinkedHashMap<>();

            for (CSVRecord record : records) {
                String id = record.get("id");
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("gender", Map.of("value", record.get("gender")));
                attrs.put("region", Map.of("value", record.get("region")));
                attrs.put("age",    Map.of("value", safeInt(record.get("age"))));
                vertexMap.put(id, attrs);

                if (vertexMap.size() == BATCH_SIZE) {
                    flushNodeBatch(vertexMap);
                    total += vertexMap.size();
                    vertexMap.clear();
                    log.debug("[TigerGraph] Nodes loaded so far: {}", total);
                }
            }
            if (!vertexMap.isEmpty()) {
                flushNodeBatch(vertexMap);
                total += vertexMap.size();
            }
        }

        log.info("[TigerGraph] Loaded {} nodes.", total);
        return total;
    }

    private void flushNodeBatch(Map<String, Object> vertexMap) throws Exception {
        Map<String, Object> body = Map.of(
            "vertices", Map.of(VERTEX_TYPE, vertexMap)
        );
        upsert(body);
    }

    // -------------------------------------------------------------------------
    // Edge loading
    // -------------------------------------------------------------------------

    private long loadEdges(Path edgesCsv) throws Exception {
        log.info("[TigerGraph] Loading edges from {}...", edgesCsv.getFileName());
        long total = 0;

        try (Reader reader = new FileReader(edgesCsv.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            // TigerGraph edge upsert: { "edges": { "User": { "<src>": { "FRIENDS_WITH": { "<dst>": {} } } } } }
            // Build per-source batches
            Map<String, Map<String, Map<String, Object>>> edgeMap = new LinkedHashMap<>();
            int batchCount = 0;

            for (CSVRecord record : records) {
                String src = record.get("source_id");
                String dst = record.get("target_id");

                edgeMap
                    .computeIfAbsent(src, k -> new LinkedHashMap<>())
                    .computeIfAbsent(EDGE_TYPE, k -> new LinkedHashMap<>())
                    .put(dst, Map.of());

                batchCount++;
                if (batchCount == BATCH_SIZE) {
                    flushEdgeBatch(edgeMap);
                    total += batchCount;
                    edgeMap.clear();
                    batchCount = 0;
                    log.debug("[TigerGraph] Edges loaded so far: {}", total);
                }
            }
            if (batchCount > 0) {
                flushEdgeBatch(edgeMap);
                total += batchCount;
            }
        }

        log.info("[TigerGraph] Loaded {} edges.", total);
        return total;
    }

    private void flushEdgeBatch(
            Map<String, Map<String, Map<String, Object>>> edgeMap) throws Exception {
        Map<String, Object> body = Map.of(
            "edges", Map.of(VERTEX_TYPE, edgeMap)
        );
        upsert(body);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private void upsert(Object body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(host + "/graph/" + GRAPH_NAME))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            log.warn("[TigerGraph] Upsert returned {}: {}", resp.statusCode(), resp.body());
        }
    }

    // Expose for benchmark queries
    HttpClient httpClient()   { return http; }
    ObjectMapper objectMapper() { return mapper; }
    String host()             { return host; }
    String token()            { return token; }

    private int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }
}
