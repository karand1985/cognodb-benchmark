package ai.graphdb.benchmark.loader;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
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
 * Loads the Pokec dataset into Neo4j AuraDB Free using batched Bolt writes.
 *
 * AuraDB Free notes:
 *  - Standard Neo4j Cypher — no quirks; MERGE, CREATE, indexes all work as expected.
 *  - Free tier allows up to 200,000 nodes; we stay well within that.
 *  - Batch size 500 is safe; AuraDB Free can handle up to 1000 per tx.
 *  - CREATE INDEX ... IF NOT EXISTS is supported (Neo4j 4.1.3+).
 */
public class Neo4jAuraLoader {

    private static final Logger log = LoggerFactory.getLogger(Neo4jAuraLoader.class);
    private static final int BATCH_SIZE = 500;

    private final Driver driver;

    public Neo4jAuraLoader(Driver driver) {
        this.driver = driver;
    }

    /**
     * Clears existing data, then loads nodes and edges.
     *
     * @return long[2] — { nodesLoaded, edgesLoaded }
     */
    public long[] load(Path nodesCsv, Path edgesCsv) throws Exception {
        clearDatabase();
        long nodes = loadNodes(nodesCsv);
        createIndex();
        long edges = loadEdges(edgesCsv);
        return new long[]{ nodes, edges };
    }

    // -------------------------------------------------------------------------

    private void clearDatabase() {
        log.info("[Neo4j Aura] Clearing existing data...");
        try (Session session = driver.session()) {
            // AuraDB supports DETACH DELETE — cleans nodes + relationships in one pass
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                return null;
            });
        }
        log.info("[Neo4j Aura] Database cleared.");
    }

    private void createIndex() {
        log.info("[Neo4j Aura] Creating index on User.id...");
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE INDEX user_id_idx IF NOT EXISTS FOR (u:User) ON (u.id)");
                return null;
            });
        }
        log.info("[Neo4j Aura] Index created.");
    }

    private long loadNodes(Path nodesCsv) throws Exception {
        log.info("[Neo4j Aura] Loading nodes from {}...", nodesCsv.getFileName());
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
                    log.debug("[Neo4j Aura] Nodes loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushNodeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[Neo4j Aura] Loaded {} nodes.", total);
        return total;
    }

    private void flushNodeBatch(List<Map<String, Object>> batch) {
        try (Session session = driver.session()) {
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
        log.info("[Neo4j Aura] Loading edges from {}...", edgesCsv.getFileName());
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
                    log.debug("[Neo4j Aura] Edges loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushEdgeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[Neo4j Aura] Loaded {} edges.", total);
        return total;
    }

    private void flushEdgeBatch(List<Map<String, Object>> batch) {
        try (Session session = driver.session()) {
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
