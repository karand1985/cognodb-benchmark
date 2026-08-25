package ai.graphdb.benchmark.loader;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.io.FileReader;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the Pokec dataset into FalkorDB using the native Redis RESP protocol
 * via Jedis 4.3.x JedisPooled (which retains the RedisGraph-compatible graph
 * command module: graphQuery / graphDelete / graphList).
 *
 * Why Jedis instead of the Neo4j Bolt driver?
 * -------------------------------------------
 * FalkorDB is a Redis module. Its self-hosted Docker image
 * (falkordb/falkordb:latest) runs Redis on port 6379 only — it does NOT ship
 * a Bolt proxy on port 7687.  Connecting via the Neo4j Java driver therefore
 * fails with "Connection to the database terminated" during the Bolt handshake.
 *
 * The correct approach is the Redis RESP protocol via Jedis 4.3.2 JedisPooled.
 * JedisPooled manages its own thread-safe connection pool and exposes graph
 * commands directly.  FalkorDB is a fully-compatible RedisGraph fork.
 *
 * Connection: redis://localhost:6379 (configurable via FALKORDB_HOST / FALKORDB_PORT).
 */
public class FalkorDBLoader {

    private static final Logger log = LoggerFactory.getLogger(FalkorDBLoader.class);
    private static final int    BATCH_SIZE = 500;
    public  static final String GRAPH_NAME = "pokec";

    private final JedisPooled jedis;

    public FalkorDBLoader(JedisPooled jedis) {
        this.jedis = jedis;
    }

    /**
     * Clears the pokec graph then loads nodes and edges.
     *
     * @return long[2] — { nodesLoaded, edgesLoaded }
     */
    public long[] load(Path nodesCsv, Path edgesCsv) throws Exception {
        clearGraph();
        long nodes = loadNodes(nodesCsv);
        createIndex();
        long edges = loadEdges(edgesCsv);
        return new long[]{ nodes, edges };
    }

    // -------------------------------------------------------------------------

    private void clearGraph() {
        log.info("[FalkorDB] Clearing graph '{}'...", GRAPH_NAME);
        try {
            jedis.graphDelete(GRAPH_NAME);
            log.info("[FalkorDB] Graph deleted.");
        } catch (Exception e) {
            // Graph may not exist yet on the first run — that is fine.
            log.debug("[FalkorDB] graphDelete skipped (graph may not exist): {}", e.getMessage());
        }
    }

    private void createIndex() {
        log.info("[FalkorDB] Creating index on User.id...");
        // FalkorDB index syntax — no IF NOT EXISTS keyword
        jedis.graphQuery(GRAPH_NAME, "CREATE INDEX FOR (u:User) ON (u.id)");
        log.info("[FalkorDB] Index created.");
    }

    private long loadNodes(Path nodesCsv) throws Exception {
        log.info("[FalkorDB] Loading nodes from {}...", nodesCsv.getFileName());
        long total = 0;

        try (Reader reader = new FileReader(nodesCsv.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

            for (CSVRecord record : records) {
                Map<String, Object> row = new HashMap<>();
                row.put("id",     record.get("id"));
                row.put("gender", record.get("gender"));
                row.put("region", record.get("region"));
                row.put("age",    safeInt(record.get("age")));
                batch.add(row);

                if (batch.size() == BATCH_SIZE) {
                    flushNodeBatch(batch);
                    total += batch.size();
                    batch.clear();
                    log.debug("[FalkorDB] Nodes loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushNodeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[FalkorDB] Loaded {} nodes.", total);
        return total;
    }

    /**
     * Flushes one batch of nodes with a single inline CREATE statement.
     * Inline literals avoid PARAMS serialisation complexity over RESP.
     */
    private void flushNodeBatch(List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder("CREATE ");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            Map<String, Object> row = batch.get(i);
            sb.append("(:User {id:'").append(esc(row.get("id").toString()))
              .append("',gender:'").append(esc(row.get("gender").toString()))
              .append("',region:'").append(esc(row.get("region").toString()))
              .append("',age:").append(row.get("age")).append("})");
        }
        jedis.graphQuery(GRAPH_NAME, sb.toString());
    }

    private long loadEdges(Path edgesCsv) throws Exception {
        log.info("[FalkorDB] Loading edges from {}...", edgesCsv.getFileName());
        long total = 0;

        try (Reader reader = new FileReader(edgesCsv.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

            for (CSVRecord record : records) {
                Map<String, Object> row = new HashMap<>();
                row.put("src", record.get("source_id"));
                row.put("dst", record.get("target_id"));
                batch.add(row);

                if (batch.size() == BATCH_SIZE) {
                    flushEdgeBatch(batch);
                    total += batch.size();
                    batch.clear();
                    log.debug("[FalkorDB] Edges loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushEdgeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[FalkorDB] Loaded {} edges.", total);
        return total;
    }

    /**
     * Flushes one batch of edges using UNWIND over an inline map-list literal.
     * FalkorDB supports UNWIND with list-of-map literals in standard Cypher.
     */
    private void flushEdgeBatch(List<Map<String, Object>> batch) {
        StringBuilder sb = new StringBuilder("UNWIND [");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            Map<String, Object> row = batch.get(i);
            sb.append("{src:'").append(esc(row.get("src").toString()))
              .append("',dst:'").append(esc(row.get("dst").toString())).append("'}");
        }
        sb.append("] AS row ")
          .append("MATCH (src:User {id:row.src}),(dst:User {id:row.dst}) ")
          .append("CREATE (src)-[:FRIENDS_WITH]->(dst)");
        jedis.graphQuery(GRAPH_NAME, sb.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Escapes single-quotes and backslashes for inline Cypher string literals. */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }
}