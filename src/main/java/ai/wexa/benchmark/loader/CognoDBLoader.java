package ai.wexa.benchmark.loader;

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
 * Loads the Pokec dataset into CognoDB Cloud using batched Bolt writes.
 *
 * CognoDB-specific notes (learned from Assignment 1):
 *  - Use explicit @Query CREATE / MERGE — SDN-style save() is not reliable.
 *  - Batch size 500 works well; larger batches can hit memory limits on c0 tier.
 *  - Relationship creation must reference both nodes by their string id property,
 *    not by internal Neo4j id, since CognoDB's id handling differs.
 *  - MERGE ... RETURN count(*) is required; bare MERGE may not commit.
 */
public class CognoDBLoader {

    private static final Logger log = LoggerFactory.getLogger(CognoDBLoader.class);
    private static final int BATCH_SIZE = 500;

    private final Driver driver;

    public CognoDBLoader(Driver driver) {
        this.driver = driver;
    }

    /**
     * Clears existing data, then loads nodes and edges from the CSVs.
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
        log.info("[CognoDB] Clearing existing data...");
        try (Session session = driver.session()) {
            // Delete relationships first, then nodes (CognoDB Bolt constraint)
            session.executeWrite(tx -> {
                tx.run("MATCH ()-[r]-() DELETE r");
                return null;
            });
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DELETE n");
                return null;
            });
        }
        log.info("[CognoDB] Database cleared.");
    }

    private void createIndex() {
        log.info("[CognoDB] Creating index on User.id...");
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // CognoDB supports CREATE INDEX syntax (Neo4j 4.x+ compatible)
                tx.run("CREATE INDEX user_id_idx IF NOT EXISTS FOR (u:User) ON (u.id)");
                return null;
            });
        }
        log.info("[CognoDB] Index created.");
    }

    private long loadNodes(Path nodesCsv) throws Exception {
        log.info("[CognoDB] Loading nodes from {}...", nodesCsv.getFileName());
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
                    log.debug("[CognoDB] Nodes loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushNodeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[CognoDB] Loaded {} nodes.", total);
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
        log.info("[CognoDB] Loading edges from {}...", edgesCsv.getFileName());
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
                    log.debug("[CognoDB] Edges loaded so far: {}", total);
                }
            }
            if (!batch.isEmpty()) {
                flushEdgeBatch(batch);
                total += batch.size();
            }
        }

        log.info("[CognoDB] Loaded {} edges.", total);
        return total;
    }

    private void flushEdgeBatch(List<Map<String, Object>> batch) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // Match both nodes by their string id property (not internal id)
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
