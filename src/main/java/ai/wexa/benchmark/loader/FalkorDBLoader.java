package ai.wexa.benchmark.loader;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the Pokec dataset into FalkorDB using batched Bolt writes.
 *
 * FalkorDB notes:
 *  - FalkorDB is a Redis module that speaks the Bolt protocol (bolt://).
 *  - Data is scoped to a named graph — passed via SessionConfig database().
 *    The graph name is "pokec" throughout this benchmark.
 *  - FalkorDB Cypher is largely Neo4j-compatible but has some differences:
 *    * No IF NOT EXISTS on CREATE INDEX (omitted here for compatibility).
 *    * DETACH DELETE is supported.
 *    * UNWIND + CREATE for batched node creation works correctly.
 *  - Self-hosted via Docker: docker run -p 7687:7687 falkordb/falkordb:latest
 *  - Default credentials: no auth (or username=falkordb, empty password).
 *  - Batch size 500 is safe; FalkorDB is memory-resident so it handles it fast.
 */
public class FalkorDBLoader {

    private static final Logger log = LoggerFactory.getLogger(FalkorDBLoader.class);
    private static final int    BATCH_SIZE  = 500;
    static final         String GRAPH_NAME  = "pokec";

    private final Driver driver;

    public FalkorDBLoader(Driver driver) {
        this.driver = driver;
    }

    /**
     * Clears the pokec graph, then loads nodes and edges.
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

    private SessionConfig graphSession() {
        return SessionConfig.builder().withDatabase(GRAPH_NAME).build();
    }

    private void clearGraph() {
        log.info("[FalkorDB] Clearing graph '{}'...", GRAPH_NAME);
        try (Session session = driver.session(graphSession())) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                return null;
            });
        }
        log.info("[FalkorDB] Graph cleared.");
    }

    private void createIndex() {
        log.info("[FalkorDB] Creating index on User.id...");
        try (Session session = driver.session(graphSession())) {
            session.executeWrite(tx -> {
                // FalkorDB index syntax — no IF NOT EXISTS keyword
                tx.run("CREATE INDEX FOR (u:User) ON (u.id)");
                return null;
            });
        }
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

    private void flushNodeBatch(List<Map<String, Object>> batch) {
        try (Session session = driver.session(graphSession())) {
            session.executeWrite(tx -> {
                tx.run(
                    "UNWIND $batch AS row " +
                    "CREATE (u:User {id: row.id, gender: row.gender, " +
                    "                region: row.region, age: row.age})",
                    Values.parameters("batch", batch)
                );
                return null;
            });
        }
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

    private void flushEdgeBatch(List<Map<String, Object>> batch) {
        try (Session session = driver.session(graphSession())) {
            session.executeWrite(tx -> {
                tx.run(
                    "UNWIND $batch AS row " +
                    "MATCH (src:User {id: row.src}), (dst:User {id: row.dst}) " +
                    "CREATE (src)-[:FRIENDS_WITH]->(dst)",
                    Values.parameters("batch", batch)
                );
                return null;
            });
        }
    }

    private int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }
}
