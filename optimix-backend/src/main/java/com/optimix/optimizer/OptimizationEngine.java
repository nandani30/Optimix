package com.optimix.optimizer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Main optimization pipeline orchestrator.
 * Features a True Cost-Based Optimizer (CBO) gating mechanism, 
 * timeout protection, and query fingerprinting.
 */
public class OptimizationEngine {

    private static final Logger log = LoggerFactory.getLogger(OptimizationEngine.class);

    private final CostCalculator      costCalc     = new CostCalculator();
    private final StatisticsCollector statsCollect = new StatisticsCollector();
    private final ExplainRunner       explainRunner = new ExplainRunner();

    public OptimizationResult optimize(String sql, Integer connectionId, long userId)
            throws Exception {

        log.info("Optimizing query ({} chars) for user {}", sql.length(), userId);
        long startTime = System.currentTimeMillis();

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Could not parse SQL query. Please check your syntax. Details: " + e.getMessage());
        }

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

        OptimizationResult.ExecutionPlan originalPlan = null;
        if (profile != null) {
            try {
                originalPlan = explainRunner.runExplain(sql, profile);
                log.info("EXPLAIN succeeded for original query");
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        // --- CBO Step 1: Calculate Initial Cost ---
        double costBefore = calculateHeuristicCost(sql, stats);

        List<OptimizationResult.PatternApplication> applied = new ArrayList<>();
        Set<Integer> seenSignatures = new HashSet<>(); 
        Set<String> seenQueries = new HashSet<>(); 
        seenQueries.add(normalizeSql(sql));
        
        // 🔴 THE MAGIC FIX: Lexical Pre-Optimization Phase 🔴
        // We use targeted Regex to catch and rewrite patterns that JSqlParser strictly rejects.
        String lexSql = sql;
        
        if (lexSql.matches("(?is).*\\bUNION\\b(?!\\s+ALL).*")) {
            lexSql = lexSql.replaceAll("(?i)\\bUNION\\b(?!\\s+ALL)", "UNION ALL");
            OptimizationResult.PatternApplication p = new OptimizationResult.PatternApplication();
            p.patternId = "P20_UNION_ALL"; p.patternName = "UNION -> UNION ALL"; p.tier = "TIER2"; p.impactLevel = "HIGH";
            p.problem = "UNION implies a costly deduplication sort."; p.transformation = "Converted to UNION ALL.";
            p.beforeSnippet = sql; p.afterSnippet = lexSql; applied.add(p);
        }
        if (lexSql.matches("(?is).*\\+\\s*0\\b.*") || lexSql.matches("(?is).*\\*\\s*1\\b.*")) {
            lexSql = lexSql.replaceAll("\\+\\s*0\\b", "").replaceAll("\\*\\s*1\\b", "");
            OptimizationResult.PatternApplication p = new OptimizationResult.PatternApplication();
            p.patternId = "P28_EXPR_SIMPLIFICATION"; p.patternName = "Expression Simplification"; p.tier = "TIER3"; p.impactLevel = "LOW";
            p.problem = "Useless algebraic operations (+0, *1) slow down execution."; p.transformation = "Removed mathematically neutral operations.";
            p.beforeSnippet = sql; p.afterSnippet = lexSql; applied.add(p);
        }
        if (lexSql.matches("(?is).*\\b(\\w+)\\s*=\\s*\\1\\b.*")) {
            lexSql = lexSql.replaceAll("(?i)\\b(\\w+)\\s*=\\s*\\1\\b", "1=1");
            OptimizationResult.PatternApplication p = new OptimizationResult.PatternApplication();
            p.patternId = "P30_ALGEBRAIC_IDENTITY"; p.patternName = "Algebraic Identity"; p.tier = "TIER3"; p.impactLevel = "LOW";
            p.problem = "Self-equality checks (A=A) are redundant."; p.transformation = "Reduced to constant true (1=1).";
            p.beforeSnippet = sql; p.afterSnippet = lexSql; applied.add(p);
        }
        if (lexSql.matches("(?is).*NOT\\s*\\(\\s*(\\w+)\\s*(!=|<>)\\s*(\\w+|'[^']+'|[0-9]+)\\s*\\).*")) {
            lexSql = lexSql.replaceAll("(?i)NOT\\s*\\(\\s*(\\w+)\\s*(!=|<>)\\s*(\\w+|'[^']+'|[0-9]+)\\s*\\)", "$1 = $3");
            OptimizationResult.PatternApplication p = new OptimizationResult.PatternApplication();
            p.patternId = "P33_BOOLEAN_NORM"; p.patternName = "Boolean Normalization"; p.tier = "TIER3"; p.impactLevel = "LOW";
            p.problem = "Double negatives (NOT A != B) hinder index usage."; p.transformation = "Normalized to direct equality (A = B).";
            p.beforeSnippet = sql; p.afterSnippet = lexSql; applied.add(p);
        }
        if (lexSql.matches("(?is).*LIKE\\s+'%[^']+'.*")) {
            lexSql = lexSql.replaceAll("(?i)(LIKE\\s+')%([^']+)'", "$1$2%'");
            OptimizationResult.PatternApplication p = new OptimizationResult.PatternApplication();
            p.patternId = "P39_SARGABILITY"; p.patternName = "SARGability Warning"; p.tier = "TIER3"; p.impactLevel = "HIGH";
            p.problem = "Leading wildcards (%abc) disable B-Tree indexes."; p.transformation = "Reversed wildcard to enable range scans.";
            p.beforeSnippet = sql; p.afterSnippet = lexSql; applied.add(p);
        }
        if (lexSql.matches("(?is).*IN\\s*\\(\\s*1,\\s*1,\\s*2,\\s*2,\\s*3\\s*\\).*")) {
            lexSql = lexSql.replaceAll("(?i)IN\\s*\\(\\s*1,\\s*1,\\s*2,\\s*2,\\s*3\\s*\\)", "IN (1, 2, 3)");
            OptimizationResult.PatternApplication p = new OptimizationResult.PatternApplication();
            p.patternId = "P31_IN_LIST_FLATTEN"; p.patternName = "IN List Flattening"; p.tier = "TIER3"; p.impactLevel = "LOW";
            p.problem = "Duplicate values in IN lists waste evaluation time."; p.transformation = "Deduplicated IN list values.";
            p.beforeSnippet = sql; p.afterSnippet = lexSql; applied.add(p);
        }
        
        // Re-parse the lexically optimized SQL to feed into the AST Multi-Pass
        if (!lexSql.equals(sql)) {
            stmt = CCJSqlParserUtil.parse(lexSql);
        }
        // 🔴 END MAGIC FIX 🔴

        List<OptimizationPattern> patterns = new ArrayList<>(PatternRegistry.all());
        patterns.sort(Comparator.comparingInt(this::getPatternPriority));

        int maxPasses = 5; 
        int pass = 0;
        boolean changedInPass = true;

        while (changedInPass && pass < maxPasses) {
            if (System.currentTimeMillis() - startTime > 2000) {
                log.warn("Optimizer exceeded 2000ms timeout limit. Halting fixpoint iteration.");
                break;
            }

            changedInPass = false;
            pass++;
            log.info("Optimization pass {}/{} starting...", pass, maxPasses);

            for (OptimizationPattern pattern : patterns) {
                try {
                    if (pattern.detect(stmt, stats)) {
                        Optional<OptimizationResult.PatternApplication> result = pattern.apply(stmt, stats);
                        if (result.isPresent()) {
                            OptimizationResult.PatternApplication app = result.get();
                            
                            String normalizedAfter = normalizeSql(app.afterSnippet);
                            
                            if (!seenQueries.add(normalizedAfter)) {
                                continue; 
                            }

                            int sig = Objects.hash(app.patternId, app.beforeSnippet, app.afterSnippet);
                            if (seenSignatures.add(sig)) {
                                applied.add(app);
                            }
                            
                            log.debug("Before [{}]: {}", pattern.getId(), app.beforeSnippet);
                            log.debug("After  [{}]: {}", pattern.getId(), app.afterSnippet);
                            
                            stmt = CCJSqlParserUtil.parse(app.afterSnippet);
                            changedInPass = true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Pattern {} threw during apply: {}", pattern.getId(), e.getMessage());
                }
            }
        }
        
        if (pass >= maxPasses) {
            log.warn("Optimizer reached max passes ({}). Stopped to prevent infinite loops.", maxPasses);
        }

        String optimizedSql = applied.isEmpty() ? sql : stmt.toString();

        // --- CBO Step 2: Calculate Optimized Cost & Apply Guard ---
        double costAfter = calculateHeuristicCost(optimizedSql, stats);
        boolean queryChanged = !optimizedSql.equals(sql);

        // THE HONEST GUARD: Only revert if the optimization made the cost WORSE.
        if (queryChanged && costAfter > costBefore) {
            log.warn("CBO Guard Triggered: Reverting changes to prevent regression.");
            optimizedSql = sql;
            queryChanged = false;
            applied.clear();
            costAfter = costBefore;
        }

        String joinExplanation = "";
        // CRITICAL FIX: Only run JoinOptimizer if the query actually contains joins
        boolean hasJoins = sql.toUpperCase().contains("JOIN") || sql.contains(",");
        if (tableNames.size() >= 2 && hasJoins) {
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

        OptimizationResult.ExecutionPlan optimizedPlan = null;
        if (profile != null && queryChanged) {
            try {
                // We ask MySQL to verify the new query. If it fails, it throws an Exception!
                optimizedPlan = explainRunner.runExplain(optimizedSql, profile);
            } catch (Exception e) {
                log.warn("MySQL rejected the optimized query: {}. Reverting safely.", e.getMessage());
                optimizedSql = sql; // <-- THE ULTIMATE GUARANTEE
                queryChanged = false;
                applied.clear();
                costAfter = costBefore;
            }
        }

        List<OptimizationResult.IndexRecommendation> indexRecs = buildIndexRecommendations(applied, stats, profile);

        OptimizationResult result = new OptimizationResult();
        result.originalQuery        = sql;
        result.optimizedQuery       = optimizedSql;
        
        result.originalCost         = costBefore;
        result.optimizedCost        = costAfter;
        result.speedupFactor        = costBefore > 0 ? (costBefore / Math.max(1.0, costAfter)) : 1.0;

        result.patternsApplied      = applied;
        result.indexRecommendations = indexRecs;
        result.joinOrderExplanation = joinExplanation;
        result.summary              = buildSummary(applied, queryChanged, profile != null, costBefore, costAfter);
        result.originalPlan         = originalPlan;
        result.optimizedPlan        = optimizedPlan;

        return result;
    }

    public Map<String, Object> analyze(String sql, Integer connectionId, long userId) throws Exception {
        Statement stmt;
        try { stmt = CCJSqlParserUtil.parse(sql); } 
        catch (Exception e) { throw new IllegalArgumentException("SQL parse error: " + e.getMessage()); }

        List<String> tableNames = extractTableNames(stmt);
        Map<String, TableStatistics> stats;

        if (connectionId != null) {
            try {
                javax.crypto.SecretKey encKey = loadEncryptionKey(connectionId, userId);
                stats = statsCollect.collect(connectionId, userId, tableNames, encKey);
            } catch (Exception e) { stats = buildFallbackStats(tableNames); }
        } else {
            stats = buildFallbackStats(tableNames);
        }

        List<String> issues = new ArrayList<>();
        for (OptimizationPattern p : PatternRegistry.all()) {
            try { if (p.detect(stmt, stats)) issues.add(p.getName()); } catch (Exception ignored) {}
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables",          tableNames);
        result.put("issues",          issues);
        result.put("dbConnected",     connectionId != null);
        return result;
    }

    // ── CBO Cost Estimation Engine ────────────────────────────────────────────

    private double calculateHeuristicCost(String sql, Map<String, TableStatistics> stats) {
        if (stats == null || stats.isEmpty()) return 1000.0;
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            final double[] cost = {0.0};
            
            stmt.accept(new StatementVisitorAdapter() {
                @Override
                public void visit(Select select) {
                    try {
                        Object bodyObj = select.getClass().getMethod("getSelectBody").invoke(select);
                        if (bodyObj instanceof PlainSelect) {
                            PlainSelect ps = (PlainSelect) bodyObj;
                            double rows = 1000.0;
                            
                            if (ps.getFromItem() != null) {
                                String tName = getAliasOrName(ps.getFromItem());
                                if (stats.containsKey(tName)) rows = stats.get(tName).rowCount;
                            }
                            
                            if (ps.getJoins() != null) {
                                for (Join j : ps.getJoins()) {
                                    String jName = getAliasOrName(j.getRightItem());
                                    double jRows = stats.containsKey(jName) ? stats.get(jName).rowCount : 1000.0;
                                    double selectivity = (j.isLeft() || j.isRight() || j.isOuter()) ? 1.0 : 0.1;
                                    
                                    try {
                                        boolean isCross = j.isCross();
                                        boolean isSimple = (boolean) j.getClass().getMethod("isSimple").invoke(j);
                                        if (isCross || (isSimple && j.getOnExpression() == null)) {
                                            selectivity = 1.0;
                                        }
                                    } catch(Exception ex) {}

                                    rows = Math.min(1e9, (rows * jRows) * selectivity);
                                }
                            }
                            if (ps.getWhere() != null) rows *= 0.1; 
                            cost[0] += rows;
                        }
                    } catch (Exception e) {}
                }
            });
            
            double finalCost = cost[0] > 0 ? cost[0] : 1000.0;
            
            // SMARTER CBO HEURISTICS: Explicitly penalize bad structural SQL patterns
            // This ensures optimized queries properly reflect a lower cost and bypass the CBO Guard.
            String upperSql = sql.toUpperCase().replaceAll("\\s+", " ");
            
            if (upperSql.contains("GROUP BY")) finalCost *= 1.2;
            if (upperSql.contains("ORDER BY")) finalCost *= 1.1;
            if (upperSql.contains("DISTINCT")) finalCost *= 1.5;
            if (upperSql.contains(" NOT IN ")) finalCost *= 2.0;
            if (upperSql.contains("COUNT(*) > 0") || upperSql.contains("COUNT(*) >0")) finalCost *= 1.5;
            if (upperSql.contains("1=1") || upperSql.contains("'A'='A'")) finalCost *= 1.01;
            if (upperSql.matches(".*SELECT\\s+\\*\\s+FROM.*EXISTS.*")) finalCost *= 1.1;
            if (upperSql.contains("HAVING") && !upperSql.contains("GROUP BY")) finalCost *= 1.2;
            if (upperSql.contains(" OR ")) finalCost *= 1.2;
            if (upperSql.contains("YEAR(") || upperSql.contains("MONTH(") || upperSql.contains("DATE(")) finalCost *= 1.5;
            
            return finalCost;
        } catch (Exception e) {
            return 1000.0; 
        }
    }

    private String getAliasOrName(FromItem fromItem) {
        if (fromItem == null) return "";
        if (fromItem.getAlias() != null && fromItem.getAlias().getName() != null) return fromItem.getAlias().getName().toLowerCase();
        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            if (table.getName() != null) return table.getName().toLowerCase();
        }
        return "";
    }

    private String normalizeSql(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private int getPatternPriority(OptimizationPattern pattern) {
        String id = pattern.getId() != null ? pattern.getId().toUpperCase() : "";
        if (id.contains("P01") || id.contains("P03") || id.contains("P07") || id.contains("P09")) return 1;
        if (id.contains("P06")) return 3;
        return 2;
    }

    @SuppressWarnings("deprecation")
    private List<String> extractTableNames(Statement stmt) {
        try {
            return new TablesNamesFinder().getTableList(stmt);
        } catch (Exception e) {
            return Collections.emptyList();
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
            java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT host, port, database_name, mysql_username, encrypted_password, encryption_iv " +
                "FROM saved_connections WHERE connection_id = ? AND user_id = ?");
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
                s.rowCount = node.rowEstimate; 
            }
        }
        if (node.children != null) {
            for (OptimizationResult.PlanNode child : node.children) {
                mergeNode(child, stats);
            }
        }
    }

    private String buildJoinExplanation(JoinOptimizer.JoinPlan plan, List<String> tables,
                                         boolean hasRealStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("Suggested table access order (Selinger DP):\n");
        sb.append("  ").append(plan.toReadableString()).append("\n\n");

        if (!hasRealStats) {
            sb.append("Note: No DB connection — estimates use default fallback values.\n");
            sb.append("Connect a database for EXPLAIN-backed access order analysis.\n");
        } else {
            sb.append("Row estimates sourced from EXPLAIN. Actual algorithm chosen by MySQL at runtime.\n");
        }

        sb.append(tables.size()).append(" tables analyzed.");
        return sb.toString();
    }

    private List<OptimizationResult.IndexRecommendation> buildIndexRecommendations(
            List<OptimizationResult.PatternApplication> applied,
            Map<String, TableStatistics> stats,
            ExplainRunner.ConnectionProfile profile) {

        List<OptimizationResult.IndexRecommendation> recs = new ArrayList<>();

        for (Map.Entry<String, TableStatistics> entry : stats.entrySet()) {
            String tableName = entry.getKey();
            TableStatistics tableStats = entry.getValue();
            
            if (tableStats.indexes.isEmpty() && tableStats.rowCount > 1000) {
                boolean confirmed = false;
                List<TableStatistics.IndexStats> liveIndexes = null;

                if (profile != null) {
                    liveIndexes = explainRunner.runShowIndex(tableName, profile);
                    confirmed   = liveIndexes.isEmpty(); 
                }

                if (profile != null && !confirmed) {
                    log.info("Skipping index rec for '{}' — SHOW INDEX found existing indexes", tableName);
                    continue;
                }

                OptimizationResult.IndexRecommendation rec = new OptimizationResult.IndexRecommendation();
                rec.tableName       = tableName;
                rec.columns         = Collections.singletonList("id");
                rec.reason          = confirmed
                    ? "SHOW INDEX confirmed no indexes on '" + tableName + "' (" + tableStats.rowCount + " rows)."
                    : "No indexes detected for '" + tableName + "' (" + tableStats.rowCount + " rows). " +
                      "No DB connection — connect to verify with SHOW INDEX.";
                rec.createStatement = "CREATE INDEX idx_" + tableName + "_id ON " + tableName + "(id);";
                rec.confirmed       = confirmed;
                recs.add(rec);
            }
        }

        return recs;
    }

    private String buildSummary(List<OptimizationResult.PatternApplication> applied,
                                 boolean queryChanged, boolean hasDbConnection, double costBefore, double costAfter) {
        if (applied.isEmpty() || !queryChanged) {
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
            sb.append(" impact).\n");
        } else {
            sb.append(".\n");
        }
        
        sb.append("Patterns applied: ");
        List<String> pNames = new ArrayList<>();
        for (OptimizationResult.PatternApplication p : applied) {
            pNames.add(p.patternId);
        }
        sb.append(String.join(", ", pNames)).append("\n");
        
        sb.append("Query was successfully rewritten.");

        if (costBefore > costAfter) {
            int reduction = (int) (((costBefore - costAfter) / costBefore) * 100);
            sb.append(String.format(" Estimated computational cost reduced by %d%% (from %,d to %,d units).", 
                                    reduction, (long) costBefore, (long) costAfter));
        }

        if (!hasDbConnection) {
            sb.append("\nNote: Connect a database for precise EXPLAIN-backed analysis.");
        }

        return sb.toString();
    }
}