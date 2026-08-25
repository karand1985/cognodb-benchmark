package ai.graphdb.benchmark.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;

/**
 * Reads all database credentials and configuration from environment variables.
 *
 * Locally  : create a .env file in the project root (see .env.example).
 * CI / prod: set the real environment variables — dotenv is ignored when they exist.
 *
 * NEVER commit real credentials — the .env file is in .gitignore.
 */
public class EnvConfig {

    private final Dotenv dotenv;

    public EnvConfig() {
        Dotenv d;
        try {
            // ignoreIfMissing() — silently skips .env when running in CI where
            // real env vars are already set.
            d = Dotenv.configure().ignoreIfMissing().load();
        } catch (DotenvException e) {
            d = Dotenv.configure().ignoreIfMissing().load();
        }
        this.dotenv = d;
    }

    // -------------------------------------------------------------------------
    // CognoDB Cloud
    // -------------------------------------------------------------------------
    public String cognoDbUri()      { return require("COGNODB_URI"); }
    public String cognoDbUser()     { return getOrDefault("COGNODB_USER", "cognodb"); }
    public String cognoDbPassword() { return require("COGNODB_PASSWORD"); }

    // -------------------------------------------------------------------------
    // Neo4j AuraDB Free
    // -------------------------------------------------------------------------
    public String neo4jAuraUri()      { return require("NEO4J_AURA_URI"); }
    public String neo4jAuraUser()     { return getOrDefault("NEO4J_AURA_USER", "neo4j"); }
    public String neo4jAuraPassword() { return require("NEO4J_AURA_PASSWORD"); }

    // -------------------------------------------------------------------------
    // FalkorDB (self-hosted Docker — Redis RESP protocol, port 6379)
    //
    // FalkorDB is a Redis module: the native protocol is Redis RESP on port 6379,
    // NOT Bolt on 7687.  FALKORDB_URI is kept for reference but is no longer used
    // to open a Bolt connection.  Use FALKORDB_HOST / FALKORDB_PORT instead.
    // -------------------------------------------------------------------------
    /** Kept for backward compatibility; not used to open a Bolt connection. */
    public String falkorDbUri()      { return getOrDefault("FALKORDB_URI", "redis://localhost:6379"); }
    public String falkorDbHost()     { return getOrDefault("FALKORDB_HOST", "localhost"); }
    public int    falkorDbPort()     { return Integer.parseInt(getOrDefault("FALKORDB_PORT", "6379")); }
    public String falkorDbUser()     { return getOrDefault("FALKORDB_USER", "falkordb"); }
    public String falkorDbPassword() { return getOrDefault("FALKORDB_PASSWORD", ""); }

    // -------------------------------------------------------------------------
    // Amazon Neptune
    // -------------------------------------------------------------------------
    public String neptuneEndpoint() { return require("NEPTUNE_ENDPOINT"); }
    public int    neptunePort()     { return Integer.parseInt(getOrDefault("NEPTUNE_PORT", "8182")); }

    // -------------------------------------------------------------------------
    // ArangoDB (self-hosted Docker)
    // -------------------------------------------------------------------------
    public String arangoDbHost()     { return require("ARANGODB_HOST"); }
    public String arangoDbPassword() { return require("ARANGODB_PASSWORD"); }

    // -------------------------------------------------------------------------
    // TigerGraph Cloud
    // -------------------------------------------------------------------------
    public String tigerGraphHost()  { return require("TIGERGRAPH_HOST"); }
    public String tigerGraphToken() { return require("TIGERGRAPH_TOKEN"); }
    public String tigerGraphGraph() { return getOrDefault("TIGERGRAPH_GRAPH", "PokecGraph"); }

    // -------------------------------------------------------------------------
    // Benchmark tuning (optional overrides)
    // -------------------------------------------------------------------------

    /** Warm-up iterations discarded before measurement. Default: 20 */
    public int warmupIterations() {
        return Integer.parseInt(getOrDefault("BENCH_WARMUP_ITERATIONS", "20"));
    }

    /** Measurement iterations per read workload. Default: 100 */
    public int measureIterations() {
        return Integer.parseInt(getOrDefault("BENCH_MEASURE_ITERATIONS", "100"));
    }

    /** Concurrency levels for the mixed workload sweep. Default: 1,10,40 */
    public int[] concurrencyLevels() {
        String raw = getOrDefault("BENCH_CONCURRENCY_LEVELS", "1,10,40");
        String[] parts = raw.split(",");
        int[] levels = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            levels[i] = Integer.parseInt(parts[i].trim());
        }
        return levels;
    }

    /** Number of random start nodes used for traversal benchmarks. Default: 100 */
    public int traversalStartNodes() {
        return Integer.parseInt(getOrDefault("BENCH_TRAVERSAL_START_NODES", "100"));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String require(String key) {
        String value = dotenv.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required environment variable '" + key + "' is not set. " +
                "Add it to your .env file or export it in your shell. " +
                "See .env.example for the full list."
            );
        }
        return value;
    }

    private String getOrDefault(String key, String defaultValue) {
        String value = dotenv.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
