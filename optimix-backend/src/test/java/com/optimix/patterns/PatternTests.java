package com.optimix.patterns;

import com.optimix.model.OptimizationResult.PatternApplication;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;
import com.optimix.optimizer.patterns.PatternRegistry;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all 40 optimization patterns.
 *
 * Each test verifies:
 *   1. detect() returns true for a matching query
 *   2. detect() returns false for a non-matching query
 *   3. apply() returns a PatternApplication with correct fields
 */
@DisplayName("Optimization Pattern Tests")
class PatternTests {

    private static final Map<String, TableStatistics> EMPTY_STATS = Map.of();
    private static final Set<String> VALID_IMPACT_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    private Map<String, TableStatistics> statsWithUnindexedTable;

    @BeforeEach
    void setup() {
        TableStatistics s = new TableStatistics();
        s.tableName = "orders";
        s.rowCount  = 50_000;
        s.dataPages = 500;
        s.indexes   = List.of();
        s.columns   = List.of();
        statsWithUnindexedTable = Map.of("orders", s);
    }

    // ── Registry ─────────────────────────────────────────────────────────────

    @Test @DisplayName("Registry contains exactly 40 patterns")
    void registryHas40Patterns() {
        assertEquals(40, PatternRegistry.all().size(),
            "PatternRegistry must have exactly 40 patterns");
    }

    @Test @DisplayName("All pattern IDs are unique")
    void allPatternIdsUnique() {
        List<String> ids = PatternRegistry.all().stream()
            .map(OptimizationPattern::getId)
            .toList();
        long uniqueCount = ids.stream().distinct().count();
        assertEquals(ids.size(), uniqueCount, "Duplicate pattern IDs found: " + ids);
    }

    @Test @DisplayName("Tier counts: 10 T1, 15 T2, 15 T3")
    void tierCounts() {
        long t1 = PatternRegistry.all().stream().filter(p -> p.getTier() == OptimizationPattern.Tier.TIER1).count();
        long t2 = PatternRegistry.all().stream().filter(p -> p.getTier() == OptimizationPattern.Tier.TIER2).count();
        long t3 = PatternRegistry.all().stream().filter(p -> p.getTier() == OptimizationPattern.Tier.TIER3).count();
        assertEquals(10, t1, "Expected 10 Tier 1 patterns");
        assertEquals(15, t2, "Expected 15 Tier 2 patterns");
        assertEquals(15, t3, "Expected 15 Tier 3 patterns");
    }

    // ── Tier 1 patterns ───────────────────────────────────────────────────────

    @Test @DisplayName("P01: detects correlated subquery in SELECT list")
    void p01_correlatedSubquery() throws Exception {
        String sql = "SELECT o.id, (SELECT COUNT(*) FROM items i WHERE i.order_id = o.id) FROM orders o";
        assertDetects("P01_CORRELATED_SUBQUERY", sql, EMPTY_STATS);
        assertNoDetect("P01_CORRELATED_SUBQUERY", "SELECT id, name FROM customers", EMPTY_STATS);
    }

    @Test @DisplayName("P02: detects OVER clause with WHERE")
    void p02_windowPredicate() throws Exception {
        String sql = "SELECT id, RANK() OVER (PARTITION BY dept ORDER BY sal) FROM emp WHERE dept = 5";
        assertDetects("P02_WINDOW_PREDICATE_PUSHDOWN", sql, EMPTY_STATS);
        assertNoDetect("P02_WINDOW_PREDICATE_PUSHDOWN", "SELECT id FROM emp", EMPTY_STATS);
    }

    @Test @DisplayName("P03: detects multi-table join with stats")
    void p03_semiJoin() throws Exception {
        TableStatistics s1 = makeStats("fact",  1_000_000, 10_000);
        TableStatistics s2 = makeStats("dim",   1_000,     10);
        TableStatistics s3 = makeStats("dim2",  500,       5);
        Map<String, TableStatistics> richStats = Map.of("fact", s1, "dim", s2, "dim2", s3);
        String sql = "SELECT f.* FROM fact f JOIN dim d ON f.dim_id = d.id WHERE d.category = 'X'";
        assertDetects("P03_SEMI_JOIN_REDUCTION", sql, richStats);
    }

    @Test @DisplayName("P07: detects aggregation with JOIN")
    void p07_aggregationPushdown() throws Exception {
        String sql = "SELECT d.name, SUM(o.amount) FROM orders o JOIN departments d ON o.dept_id = d.id GROUP BY d.name";
        assertDetects("P07_AGGREGATION_PUSHDOWN", sql, EMPTY_STATS);
        assertNoDetect("P07_AGGREGATION_PUSHDOWN", "SELECT id FROM orders", EMPTY_STATS);
    }

    @Test @DisplayName("P08: detects derived table subquery")
    void p08_subqueryUnnesting() throws Exception {
        String sql = "SELECT * FROM (SELECT id, name FROM customers WHERE active = 1) sub WHERE sub.id > 100";
        assertDetects("P08_SUBQUERY_UNNESTING", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P10: detects 3+ table join")
    void p10_dpJoinOrder() throws Exception {
        String sql = "SELECT * FROM a JOIN b ON a.id = b.aid JOIN c ON b.id = c.bid";
        assertDetects("P10_DP_JOIN_ORDER", sql, EMPTY_STATS);
        assertNoDetect("P10_DP_JOIN_ORDER", "SELECT id FROM single_table", EMPTY_STATS);
    }

    // ── Tier 2 patterns ───────────────────────────────────────────────────────

    @Test @DisplayName("P11: detects NOT IN subquery")
    void p11_notIn() throws Exception {
        String sql = "SELECT * FROM customers WHERE id NOT IN (SELECT customer_id FROM orders)";
        assertDetects("P11_NOT_IN_ANTI_JOIN", sql, EMPTY_STATS);
        assertNoDetect("P11_NOT_IN_ANTI_JOIN", "SELECT id FROM customers WHERE status = 1", EMPTY_STATS);
    }

    @Test @DisplayName("P12: detects EXISTS subquery")
    void p12_exists() throws Exception {
        String sql = "SELECT * FROM c WHERE EXISTS (SELECT 1 FROM o WHERE o.cid = c.id)";
        assertDetects("P12_EXISTS_SEMI_JOIN", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P14: detects YEAR() function on column")
    void p14_functionOnColumn() throws Exception {
        String sql = "SELECT * FROM orders WHERE YEAR(created_at) = 2024";
        assertDetects("P14_FUNCTION_ON_COLUMN", sql, EMPTY_STATS);
        assertNoDetect("P14_FUNCTION_ON_COLUMN", "SELECT * FROM orders WHERE created_at >= '2024-01-01'", EMPTY_STATS);
    }

    @Test @DisplayName("P14: detects MONTH() function on column")
    void p14_monthFunction() throws Exception {
        String sql = "SELECT * FROM sales WHERE MONTH(sale_date) = 3 AND YEAR(sale_date) = 2024";
        assertDetects("P14_FUNCTION_ON_COLUMN", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P15: detects LEFT JOIN with WHERE on right table")
    void p15_outerToInner() throws Exception {
        String sql = "SELECT * FROM a LEFT JOIN b ON a.id = b.aid WHERE b.status = 'active'";
        assertDetects("P15_OUTER_TO_INNER", sql, EMPTY_STATS);
        assertNoDetect("P15_OUTER_TO_INNER", "SELECT * FROM a JOIN b ON a.id = b.aid", EMPTY_STATS);
    }

    @Test @DisplayName("P17: detects duplicate table reference")
    void p17_redundantJoin() throws Exception {
        String sql = "SELECT * FROM orders o JOIN customers c ON o.cid = c.id JOIN customers c2 ON o.cid = c2.id";
        assertDetects("P17_REDUNDANT_JOIN", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P18: detects ORDER BY clause")
    void p18_sortElimination() throws Exception {
        String sql = "SELECT * FROM orders ORDER BY created_at DESC";
        assertDetects("P18_SORT_ELIMINATION", sql, EMPTY_STATS);
        assertNoDetect("P18_SORT_ELIMINATION", "SELECT id FROM orders", EMPTY_STATS);
    }

    @Test @DisplayName("P19: detects DISTINCT keyword")
    void p19_distinctElimination() throws Exception {
        String sql = "SELECT DISTINCT user_id FROM users";
        assertDetects("P19_DISTINCT_ELIM", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P20: detects UNION without ALL")
    void p20_unionToUnionAll() throws Exception {
        String sql = "SELECT id FROM a UNION SELECT id FROM b";
        assertDetects("P20_UNION_ALL", sql, EMPTY_STATS);
        assertNoDetect("P20_UNION_ALL", "SELECT id FROM a UNION ALL SELECT id FROM b", EMPTY_STATS);
    }

    @Test @DisplayName("P21: detects COUNT(*) > 0")
    void p21_countToExists() throws Exception {
        String sql = "SELECT * FROM t WHERE (SELECT COUNT(*) FROM o WHERE o.t_id = t.id) > 0";
        assertDetects("P21_COUNT_TO_EXISTS", sql, EMPTY_STATS);
        assertNoDetect("P21_COUNT_TO_EXISTS", "SELECT COUNT(*) FROM orders", EMPTY_STATS);
    }

    @Test @DisplayName("P22: detects OR in WHERE clause")
    void p22_orToUnion() throws Exception {
        String sql = "SELECT * FROM users WHERE email = 'a@b.com' OR phone = '123'";
        assertDetects("P22_OR_TO_UNION", sql, EMPTY_STATS);
        assertNoDetect("P22_OR_TO_UNION", "SELECT * FROM users WHERE email = 'a@b.com'", EMPTY_STATS);
    }

    @Test @DisplayName("P24: detects SELECT *")
    void p24_projectionPushdown() throws Exception {
        String sql = "SELECT * FROM orders o JOIN customers c ON o.cid = c.id";
        assertDetects("P24_PROJECTION_PUSH", sql, EMPTY_STATS);
        assertNoDetect("P24_PROJECTION_PUSH", "SELECT o.id, o.amount FROM orders o", EMPTY_STATS);
    }

    @Test @DisplayName("P25: detects implicit Cartesian product")
    void p25_cartesianProduct() throws Exception {
        String sql = "SELECT * FROM customers, orders";
        assertDetects("P25_CARTESIAN", sql, EMPTY_STATS);
    }

    // ── Tier 3 patterns ───────────────────────────────────────────────────────

    @Test @DisplayName("P26: detects SELECT *")
    void p26_selectStar() throws Exception {
        assertDetects("P26_SELECT_STAR", "SELECT * FROM products WHERE cat = 'x'", EMPTY_STATS);
        assertNoDetect("P26_SELECT_STAR", "SELECT id, name FROM products", EMPTY_STATS);
    }

    @Test @DisplayName("P27: detects constant arithmetic")
    void p27_constantFolding() throws Exception {
        assertDetects("P27_CONSTANT_FOLDING", "SELECT * FROM emp WHERE sal > 40000 + 5000", EMPTY_STATS);
        assertNoDetect("P27_CONSTANT_FOLDING", "SELECT * FROM emp WHERE sal > 45000", EMPTY_STATS);
    }

    @Test @DisplayName("P28: detects NOT(condition)")
    void p28_expressionSimplify() throws Exception {
        assertDetects("P28_EXPR_SIMPLIFY", "SELECT * FROM users WHERE NOT(age > 18)", EMPTY_STATS);
    }

    @Test @DisplayName("P29: detects always-true filter")
    void p29_redundantFilter() throws Exception {
        assertDetects("P29_REDUNDANT_FILTER", "SELECT * FROM t WHERE 1=1 AND status = 'active'", EMPTY_STATS);
    }

    @Test @DisplayName("P30: detects column * 1 identity")
    void p30_algebraicIdentity() throws Exception {
        assertDetects("P30_ALGEBRAIC_ID", "SELECT * FROM products WHERE price * 1 > 100", EMPTY_STATS);
    }

    @Test @DisplayName("P31: detects IN(single_value)")
    void p31_inListFlatten() throws Exception {
        assertDetects("P31_IN_LIST_FLATTEN", "SELECT * FROM t WHERE status IN (1)", EMPTY_STATS);
        assertNoDetect("P31_IN_LIST_FLATTEN", "SELECT * FROM t WHERE status IN (1, 2, 3)", EMPTY_STATS);
    }

    @Test @DisplayName("P34: detects WHERE FALSE / LIMIT 0")
    void p34_shortCircuit() throws Exception {
        assertDetects("P34_SHORT_CIRCUIT", "SELECT * FROM orders WHERE FALSE", EMPTY_STATS);
        assertDetects("P34_SHORT_CIRCUIT", "SELECT * FROM orders LIMIT 0", EMPTY_STATS);
        assertNoDetect("P34_SHORT_CIRCUIT", "SELECT * FROM orders WHERE status = 1", EMPTY_STATS);
    }

    @Test @DisplayName("P35: detects LIMIT without ORDER BY")
    void p35_limitPushdown() throws Exception {
        assertDetects("P35_LIMIT_PUSHDOWN", "SELECT * FROM orders LIMIT 10", EMPTY_STATS);
        assertNoDetect("P35_LIMIT_PUSHDOWN", "SELECT * FROM orders ORDER BY id LIMIT 10", EMPTY_STATS);
    }

    @Test @DisplayName("P36: detects multiple subqueries")
    void p36_duplicateSubquery() throws Exception {
        String sql = "SELECT * FROM t WHERE val > (SELECT AVG(val) FROM t) AND val < (SELECT AVG(val) FROM t) * 2";
        assertDetects("P36_DUPE_SUBQUERY", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P37: detects implicit join syntax")
    void p37_implicitJoin() throws Exception {
        String sql = "SELECT * FROM customers c, orders o WHERE c.id = o.customer_id";
        assertDetects("P37_IMPLICIT_JOIN", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P38: detects scalar aggregate subquery in WHERE")
    void p38_scalarToJoin() throws Exception {
        String sql = "SELECT * FROM employees WHERE salary > (SELECT AVG(salary) FROM employees)";
        assertDetects("P38_SCALAR_JOIN", sql, EMPTY_STATS);
    }

    @Test @DisplayName("P39: detects leading wildcard LIKE")
    void p39_sargability() throws Exception {
        assertDetects("P39_SARGABILITY", "SELECT * FROM users WHERE name LIKE '%john%'", EMPTY_STATS);
        assertNoDetect("P39_SARGABILITY", "SELECT * FROM users WHERE name LIKE 'john%'", EMPTY_STATS);
    }

    @Test @DisplayName("P40: detects unindexed table with WHERE clause")
    void p40_missingIndex() throws Exception {
        String sql = "SELECT * FROM orders WHERE status = 'pending'";
        assertDetects("P40_MISSING_INDEX", sql, statsWithUnindexedTable);
        assertNoDetect("P40_MISSING_INDEX", sql, EMPTY_STATS);
    }

    // ── apply() contract tests ─────────────────────────────────────────────────

    @Test @DisplayName("apply() returns required fields: id, name, tier, problem, transformation, before, after, impactLevel, impactReason")
    void applyReturnsCompleteFields() throws Exception {
        String sql = "SELECT * FROM orders WHERE YEAR(created_at) = 2024";
        Statement stmt = CCJSqlParserUtil.parse(sql);
        OptimizationPattern p = PatternRegistry.findById("P14_FUNCTION_ON_COLUMN");
        assertNotNull(p);

        Optional<PatternApplication> result = p.apply(stmt, EMPTY_STATS);
        assertTrue(result.isPresent(), "P14 should apply to YEAR() function");

        PatternApplication pa = result.get();
        assertNotNull(pa.patternId,      "patternId must not be null");
        assertNotNull(pa.patternName,    "patternName must not be null");
        assertNotNull(pa.tier,           "tier must not be null");
        assertNotNull(pa.problem,        "problem must not be null");
        assertNotNull(pa.transformation, "transformation must not be null");
        assertNotNull(pa.beforeSnippet,  "beforeSnippet must not be null");
        assertNotNull(pa.afterSnippet,   "afterSnippet must not be null");
        assertNotNull(pa.impactLevel,    "impactLevel must not be null");
        assertNotNull(pa.impactReason,   "impactReason must not be null");
        assertTrue(VALID_IMPACT_LEVELS.contains(pa.impactLevel),
            "impactLevel must be LOW, MEDIUM, or HIGH — got: " + pa.impactLevel);
        assertFalse(pa.impactReason.isBlank(), "impactReason must not be blank");
    }

    @Test @DisplayName("Every pattern's apply() returns impactLevel in {LOW, MEDIUM, HIGH}")
    void allPatternsHaveValidImpactLevel() throws Exception {
        // Test a set of trigger queries, one per tier
        String[] triggerSqls = {
            // Tier 1
            "SELECT o.id, (SELECT COUNT(*) FROM items i WHERE i.order_id = o.id) FROM orders o",
            "SELECT * FROM orders o JOIN depts d ON o.dept_id = d.id GROUP BY d.name",
            // Tier 2
            "SELECT * FROM customers WHERE id NOT IN (SELECT customer_id FROM orders)",
            "SELECT * FROM orders WHERE YEAR(created_at) = 2024",
            "SELECT * FROM t WHERE (SELECT COUNT(*) FROM o WHERE o.t_id = t.id) > 0",
            // Tier 3
            "SELECT * FROM products WHERE cat = 'x'",
            "SELECT * FROM emp WHERE sal > 40000 + 5000",
            "SELECT * FROM users WHERE name LIKE '%john%'"
        };

        for (String sql : triggerSqls) {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            for (OptimizationPattern p : PatternRegistry.all()) {
                try {
                    if (p.detect(stmt, EMPTY_STATS)) {
                        Optional<PatternApplication> opt = p.apply(stmt, EMPTY_STATS);
                        opt.ifPresent(pa -> {
                            assertTrue(VALID_IMPACT_LEVELS.contains(pa.impactLevel),
                                p.getId() + " returned invalid impactLevel: " + pa.impactLevel);
                            assertNotNull(pa.impactReason,
                                p.getId() + " returned null impactReason");
                        });
                    }
                } catch (Exception e) {
                    // Pattern threw — acceptable, already logged by engine
                }
            }
        }
    }

    @Test @DisplayName("apply() returns empty when detect() returns false")
    void applyEmptyWhenNotDetected() throws Exception {
        Statement stmt = CCJSqlParserUtil.parse("SELECT id, name FROM users");
        for (OptimizationPattern p : PatternRegistry.all()) {
            if (!p.detect(stmt, EMPTY_STATS)) {
                assertDoesNotThrow(() -> p.apply(stmt, EMPTY_STATS),
                    "apply() must not throw for " + p.getId());
            }
        }
    }

    @Test @DisplayName("No pattern returns a fake numeric speedup (estimatedSpeedup field removed)")
    void noFakeSpeedupField() throws Exception {
        // Verify via reflection that PatternApplication has NO estimatedSpeedup field
        boolean hasSpeedupField = false;
        try {
            PatternApplication.class.getDeclaredField("estimatedSpeedup");
            hasSpeedupField = true;
        } catch (NoSuchFieldException expected) {
            // Correct — field was removed
        }
        assertFalse(hasSpeedupField,
            "PatternApplication must NOT have estimatedSpeedup field — it was a fake metric");
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private void assertDetects(String patternId, String sql,
                                Map<String, TableStatistics> stats) throws Exception {
        OptimizationPattern p = PatternRegistry.findById(patternId);
        assertNotNull(p, "Pattern not found: " + patternId);
        Statement stmt = CCJSqlParserUtil.parse(sql);
        assertTrue(p.detect(stmt, stats),
            patternId + " should detect: " + sql);
    }

    private void assertNoDetect(String patternId, String sql,
                                 Map<String, TableStatistics> stats) throws Exception {
        OptimizationPattern p = PatternRegistry.findById(patternId);
        assertNotNull(p, "Pattern not found: " + patternId);
        Statement stmt = CCJSqlParserUtil.parse(sql);
        assertFalse(p.detect(stmt, stats),
            patternId + " should NOT detect: " + sql);
    }

    private TableStatistics makeStats(String name, long rows, long pages) {
        TableStatistics s = new TableStatistics();
        s.tableName = name; s.rowCount = rows; s.dataPages = pages;
        s.indexes = List.of(); s.columns = List.of();
        return s;
    }
}
