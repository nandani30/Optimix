package com.optimix.optimizer;

import com.optimix.config.DatabaseConfig;
import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.cost.CostCalculator;
import com.optimix.optimizer.join.JoinOptimizer;
import com.optimix.optimizer.patterns.OptimizationPattern;
import com.optimix.optimizer.patterns.PatternRegistry;
import com.optimix.optimizer.statistics.StatisticsCollector;
import com.optimix.util.CredentialEncryption;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Base64;

/**
 * Main optimization pipeline orchestrator.
 *
 * Pipeline:
 *   1. Parse SQL → AST  (JSqlParser)
 *   2. Collect real statistics from MySQL  (if connection available)
 *   3. Run EXPLAIN on the original query  (if connection available)
 *   4. Run all 40 patterns  (detect → apply)
 *   5. Run DP Join Optimizer  (reorder joins using EXPLAIN row estimates)
 *   6. Run EXPLAIN on the optimized query  (if rewrite occurred and connection available)
 *   7. Build index recommendations using SHOW INDEX confirmation
 *   8. Build and return OptimizationResult
 *
 * STRICT RULES:
 *   - NO fake cost numbers, speedup multipliers, or invented metrics
 *   - EXPLAIN data is the only source of execution plan information
 *   - SHOW INDEX confirms index existence before recommending new indexes
 *   - Impact levels are LOW / MEDIUM / HIGH — never fabricated numbers
 */
public class OptimizationEngine {

    private static final Logger log = LoggerFactory.getLogger(OptimizationEngine.class);

    private final CostCalculator      costCalc     = new CostCalculator();
    private final StatisticsCollector statsCollect = new StatisticsCollector();
    private final ExplainRunner       explainRunner = new ExplainRunner();

    // ── Main optimization entry point ─────────────────────────────────────────

    /**
     * Fully optimize a SQL query.
     *
     * @param sql          The SQL query to optimize
     * @param connectionId Optional saved MySQL connection ID (for real statistics)
     * @param userId       Current authenticated user
     * @return             Complete OptimizationResult with real DB data where available
     */
    public OptimizationResult optimize(String sql, Integer connectionId, long userId)
            throws Exception {

        log.info("Optimizing query ({} chars) for user {}", sql.length(), userId);

        // ── Step 1: Parse ──────────────────────────────────────────────────────
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Could not parse SQL query. Please check your syntax. " +
                "Details: " + e.getMessage());
        }

        // ── Step 2: Load connection profile + collect real statistics ──────────
        ExplainRunner.ConnectionProfile profile = null;
        List<String> tableNames = extractTableNames(stmt);
        Map<String, TableStatistics> stats;

        if (connectionId != null) {
            try {
                javax.crypto.SecretKey encKey = loadEncryptionKey(connectionId, userId);
                stats   = statsCollect.collect(connectionId, userId, tableNames, encKey);
                profile = loadConnectionProfile(connectionId, userId, encKey);
            } catch (Exception e) {
                log.warn("Could not load DB connection: {}. Running without DB context.", e.getMessage());
                stats = buildFallbackStats(tableNames);
            }
        } else {
            stats = buildFallbackStats(tableNames);
        }

        // ── Step 3: EXPLAIN on original query ──────────────────────────────────
        OptimizationResult.ExecutionPlan originalPlan = null;
        if (profile != null) {
            originalPlan = explainRunner.runExplain(sql, profile);
            if (originalPlan != null) {
                log.info("EXPLAIN succeeded for original query");
            }
        }

        // ── Step 4: Run all 40 patterns ────────────────────────────────────────
        List<OptimizationResult.PatternApplication> applied = new ArrayList<>();
        for (OptimizationPattern pattern : PatternRegistry.all()) {
            try {
                if (pattern.detect(stmt, stats)) {
                    Optional<OptimizationResult.PatternApplication> result =
                        pattern.apply(stmt, stats);
                    result.ifPresent(applied::add);
                }
            } catch (Exception e) {
                log.warn("Pattern {} threw during apply: {}", pattern.getId(), e.getMessage());
            }
        }

        // ── Step 5: Run DP join optimizer (uses EXPLAIN row estimates if available) ─
        String joinExplanation = "";
        if (tableNames.size() >= 2) {
            // If EXPLAIN is available, update stats row estimates from real EXPLAIN output
            if (originalPlan != null) {
                mergeExplainRowsIntoStats(originalPlan, stats);
            }
            try {
                JoinOptimizer joinOpt   = new JoinOptimizer(costCalc, stats);
                JoinOptimizer.JoinPlan plan = joinOpt.findOptimalOrder(tableNames);
                joinExplanation = buildJoinExplanation(plan, tableNames, originalPlan != null);
            } catch (Exception e) {
                log.warn("Join optimizer failed: {}", e.getMessage());
                joinExplanation = "Join order analysis unavailable.";
            }
        }

        // ── Step 6: Get optimized SQL ──────────────────────────────────────────
        // Patterns may have mutated the AST; toString() gives the rewritten SQL
        String optimizedSql = applied.isEmpty() ? sql : stmt.toString();
        boolean queryChanged = !optimizedSql.equals(sql);

        // ── Step 7: EXPLAIN on optimized query (only if query actually changed) ─
        OptimizationResult.ExecutionPlan optimizedPlan = null;
        if (profile != null && queryChanged) {
            try {
                optimizedPlan = explainRunner.runExplain(optimizedSql, profile);
                if (optimizedPlan != null) {
                    log.info("EXPLAIN succeeded for optimized query");
                }
            } catch (Exception e) {
                log.warn("EXPLAIN on optimized query failed: {}", e.getMessage());
            }
        }

        // ── Step 8: Build index recommendations (SHOW INDEX confirmed) ─────────
        List<OptimizationResult.IndexRecommendation> indexRecs =
            buildIndexRecommendations(applied, stats, profile);

        // ── Step 9: Build result ───────────────────────────────────────────────
        OptimizationResult result = new OptimizationResult();
        result.originalQuery        = sql;
        result.optimizedQuery       = optimizedSql;
        result.patternsApplied      = applied;
        result.indexRecommendations = indexRecs;
        result.joinOrderExplanation = joinExplanation;
        result.summary              = buildSummary(applied, queryChanged, profile != null);
        result.originalPlan         = originalPlan;
        result.optimizedPlan        = optimizedPlan;

        log.info("Optimization complete: {} patterns applied, query changed: {}",
            applied.size(), queryChanged);

        return result;
    }

    /**
     * Analyze only — returns detected issues without rewriting.
     */
    public Map<String, Object> analyze(String sql, Integer connectionId, long userId)
            throws Exception {

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL parse error: " + e.getMessage());
        }

        List<String> tableNames = extractTableNames(stmt);
        Map<String, TableStatistics> stats;

        if (connectionId != null) {
            try {
                javax.crypto.SecretKey encKey = loadEncryptionKey(connectionId, userId);
                stats = statsCollect.collect(connectionId, userId, tableNames, encKey);
            } catch (Exception e) {
                stats = buildFallbackStats(tableNames);
            }
        } else {
            stats = buildFallbackStats(tableNames);
        }

        List<String> issues = new ArrayList<>();
        for (OptimizationPattern p : PatternRegistry.all()) {
            try {
                if (p.detect(stmt, stats)) issues.add(p.getName());
            } catch (Exception ignored) {}
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables",          tableNames);
        result.put("issues",          issues);
        result.put("dbConnected",     connectionId != null);
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private List<String> extractTableNames(Statement stmt) {
        try {
            return new TablesNamesFinder().getTableList(stmt);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, TableStatistics> buildFallbackStats(List<String> tableNames) {
        Map<String, TableStatistics> fallbacks = new HashMap<>();
        for (String t : tableNames) {
            fallbacks.put(t.toLowerCase(), CostCalculator.defaultStats(t));
        }
        return fallbacks;
    }

    private javax.crypto.SecretKey loadEncryptionKey(int connectionId, long userId) throws Exception {
        try (java.sql.Connection conn = DatabaseConfig.getConnection()) {
            java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT encryption_salt FROM saved_connections WHERE connection_id = ? AND user_id = ?");
            ps.setInt(1, connectionId); ps.setLong(2, userId);
            java.sql.ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("Connection not found");
            byte[] salt = Base64.getDecoder().decode(rs.getString("encryption_salt"));
            String secret = System.getenv().getOrDefault("ENCRYPTION_SECRET",
                "optimix-server-secret-change-in-production");
            char[] keyMaterial = (secret + ":" + userId).toCharArray();
            return CredentialEncryption.deriveKey(keyMaterial, salt);
        }
    }

    private ExplainRunner.ConnectionProfile loadConnectionProfile(
            int connectionId, long userId, javax.crypto.SecretKey encKey) throws Exception {
        try (java.sql.Connection conn = DatabaseConfig.getConnection()) {
            java.sql.PreparedStatement ps = conn.prepareStatement("""
                SELECT host, port, database_name, mysql_username,
                       encrypted_password, encryption_iv
                FROM saved_connections
                WHERE connection_id = ? AND user_id = ?
            """);
            ps.setInt(1, connectionId); ps.setLong(2, userId);
            java.sql.ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            ExplainRunner.ConnectionProfile p = new ExplainRunner.ConnectionProfile();
            p.host         = rs.getString("host");
            p.port         = rs.getInt("port");
            p.databaseName = rs.getString("database_name");
            p.username     = rs.getString("mysql_username");
            p.password     = CredentialEncryption.decrypt(
                rs.getString("encrypted_password"),
                rs.getString("encryption_iv"),
                encKey);
            return p;
        }
    }

    /**
     * If EXPLAIN is available, update the stats row counts from real EXPLAIN estimates.
     * This makes the JoinOptimizer use the DB's own cardinality estimates, not our fallback.
     */
    private void mergeExplainRowsIntoStats(OptimizationResult.ExecutionPlan plan,
                                            Map<String, TableStatistics> stats) {
        if (plan == null || plan.root == null) return;
        mergeNode(plan.root, stats);
    }

    private void mergeNode(OptimizationResult.PlanNode node, Map<String, TableStatistics> stats) {
        if (node == null) return;
        if (node.table != null && node.rowEstimate != null) {
            TableStatistics s = stats.get(node.table.toLowerCase());
            if (s != null && node.rowEstimate > 0) {
                s.rowCount = node.rowEstimate; // use DB's own estimate
            }
        }
        if (node.children != null) {
            for (OptimizationResult.PlanNode child : node.children) {
                mergeNode(child, stats);
            }
        }
    }

    /**
     * Build a human-readable join order explanation.
     * Only uses information available from the DP optimizer (table names, structure).
     * Does NOT invent cost numbers or performance claims.
     */
    private String buildJoinExplanation(JoinOptimizer.JoinPlan plan, List<String> tables,
                                         boolean hasRealStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("Suggested join order (Selinger DP based on ");
        sb.append(hasRealStats ? "EXPLAIN row estimates" : "fallback row estimates");
        sb.append("):\n");
        sb.append("  ").append(plan.toReadableString()).append("\n\n");

        if (!hasRealStats) {
            sb.append("Note: No DB connection — estimates use default fallback values.\n");
            sb.append("Connect a database for EXPLAIN-backed join order analysis.\n");
        } else {
            sb.append("Row estimates sourced from EXPLAIN. Actual join algorithm is chosen by MySQL at runtime.\n");
        }

        sb.append(tables.size()).append(" tables analyzed.");
        return sb.toString();
    }

    /**
     * Build index recommendations using SHOW INDEX to confirm which indexes already exist.
     * Only recommends indexes that are confirmed to be missing.
     */
    private List<OptimizationResult.IndexRecommendation> buildIndexRecommendations(
            List<OptimizationResult.PatternApplication> applied,
            Map<String, TableStatistics> stats,
            ExplainRunner.ConnectionProfile profile) {

        List<OptimizationResult.IndexRecommendation> recs = new ArrayList<>();

        // P40 (missing index) pattern identifies unindexed tables
        boolean p40Applied = applied.stream().anyMatch(p -> p.patternId.equals("P40_MISSING_INDEX"));
        if (!p40Applied) return recs;

        stats.forEach((tableName, tableStats) -> {
            if (tableStats.indexes.isEmpty() && tableStats.rowCount > 1000) {
                // Confirm via SHOW INDEX if we have a connection
                boolean confirmed = false;
                List<TableStatistics.IndexStats> liveIndexes = null;

                if (profile != null) {
                    liveIndexes = explainRunner.runShowIndex(tableName, profile);
                    confirmed   = liveIndexes.isEmpty(); // SHOW INDEX returned nothing → truly no indexes
                }

                // If SHOW INDEX found indexes, the stats were stale — do not recommend
                if (profile != null && !confirmed) {
                    log.info("Skipping index rec for '{}' — SHOW INDEX found existing indexes", tableName);
                    return;
                }

                OptimizationResult.IndexRecommendation rec = new OptimizationResult.IndexRecommendation();
                rec.tableName       = tableName;
                rec.columns         = List.of("id");
                rec.reason          = confirmed
                    ? "SHOW INDEX confirmed no indexes on '" + tableName + "' (" + tableStats.rowCount + " rows)."
                    : "No indexes detected for '" + tableName + "' (" + tableStats.rowCount + " rows). " +
                      "No DB connection — connect to verify with SHOW INDEX.";
                rec.createStatement = "CREATE INDEX idx_" + tableName + "_id ON " + tableName + "(id);";
                rec.confirmed       = confirmed;
                recs.add(rec);
            }
        });

        return recs;
    }

    private String buildSummary(List<OptimizationResult.PatternApplication> applied,
                                 boolean queryChanged, boolean hasDbConnection) {
        if (applied.isEmpty()) {
            return "No optimizations found — query already looks optimal.";
        }
        long high   = applied.stream().filter(p -> "HIGH".equals(p.impactLevel)).count();
        long medium = applied.stream().filter(p -> "MEDIUM".equals(p.impactLevel)).count();

        StringBuilder sb = new StringBuilder();
        sb.append(applied.size()).append(" optimization");
        if (applied.size() != 1) sb.append("s");
        sb.append(" found");

        if (high > 0 || medium > 0) {
            sb.append(" (");
            if (high > 0)   sb.append(high).append(" HIGH");
            if (high > 0 && medium > 0) sb.append(", ");
            if (medium > 0) sb.append(medium).append(" MEDIUM");
            sb.append(" impact)");
        }
        sb.append(".");

        if (queryChanged) {
            sb.append(" Query was rewritten.");
        }

        if (!hasDbConnection) {
            sb.append(" Connect a database for EXPLAIN-backed analysis.");
        }

        return sb.toString();
    }
}
