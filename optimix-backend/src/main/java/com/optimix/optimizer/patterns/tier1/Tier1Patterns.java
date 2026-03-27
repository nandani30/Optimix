package com.optimix.optimizer.patterns.tier1;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// ════════════════════════════════════════════════════════════════════════════
//  P01 — Correlated Subquery Decorrelation (Kim's Algorithm)
// ════════════════════════════════════════════════════════════════════════════
class P01_CorrelatedSubquery implements OptimizationPattern {
    public String getId()   { return "P01_CORRELATED_SUBQUERY"; }
    public String getName() { return "Correlated Subquery Decorrelation"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        if (!(stmt instanceof Select)) return false;
        String sql = stmt.toString().toUpperCase();
        return sql.matches("(?s).*SELECT\\s*\\([^)]*SELECT[^)]*\\).*")
            || sql.matches("(?s).*WHERE.*\\(\\s*SELECT.*\\).*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Correlated subquery detected in SELECT list or WHERE clause. " +
            "The inner SELECT is re-executed once for every row of the outer query, " +
            "producing O(N×M) complexity.",
            "Rewrite as LEFT JOIN with a pre-aggregated subquery (Kim's algorithm). " +
            "The inner query executes once; results are joined on the correlation key. " +
            "COALESCE handles the NULL semantics of the original scalar result.",
            "HIGH",
            "HIGH — correlated subqueries execute once per outer row; with large outer tables " +
            "this causes the inner query to run thousands or millions of times.",
            "SELECT o.id, (SELECT COUNT(*) FROM items i WHERE i.order_id = o.id) FROM orders o",
            "SELECT o.id, COALESCE(agg.cnt, 0)\nFROM orders o\n" +
            "LEFT JOIN (SELECT order_id, COUNT(*) cnt FROM items GROUP BY order_id) agg\n" +
            "  ON agg.order_id = o.id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P02 — Predicate Pushdown Through Window Functions
// ════════════════════════════════════════════════════════════════════════════
class P02_WindowPredicatePushdown implements OptimizationPattern {
    public String getId()   { return "P02_WINDOW_PREDICATE_PUSHDOWN"; }
    public String getName() { return "Predicate Pushdown Through Window Functions"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains(" OVER ") && sql.contains("WHERE");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "WHERE filter is applied after the window function processes all rows. " +
            "Every row passes through the expensive window computation before filtering.",
            "If the predicate references only PARTITION BY columns, push it inside the " +
            "subquery so fewer rows reach the OVER clause.",
            "HIGH",
            "HIGH — the window function processes all rows unnecessarily; pushing the predicate " +
            "inside reduces the working set before the O(N log N) window operation.",
            "SELECT *, RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk\n" +
            "FROM employees WHERE dept_id = 5",
            "SELECT * FROM (\n  SELECT *, RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk\n" +
            "  FROM employees WHERE dept_id = 5\n) sub"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P03 — Semi-Join Reduction (Star Schema Optimization)
// ════════════════════════════════════════════════════════════════════════════
class P03_SemiJoinReduction implements OptimizationPattern {
    public String getId()   { return "P03_SEMI_JOIN_REDUCTION"; }
    public String getName() { return "Semi-Join Reduction (Star Schema)"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("JOIN") && stats.size() > 2;
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Fact table joined to dimension tables where filters on dimensions could " +
            "pre-reduce the fact table before the main join.",
            "Apply dimension filters to the fact table's foreign keys first using a semi-join " +
            "(IN subquery or EXISTS). This reduces the fact table rows before the expensive join.",
            "HIGH",
            "HIGH — dimension filters applied after joining a large fact table read many more " +
            "rows than necessary; semi-join pushes the filter to the earliest possible point.",
            "SELECT f.* FROM fact f JOIN dim d ON f.dim_id = d.id WHERE d.category = 'X'",
            "SELECT f.* FROM fact f\nWHERE f.dim_id IN (SELECT id FROM dim WHERE category = 'X')"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P04 — Join-Predicate Move-Around (JPMA)
// ════════════════════════════════════════════════════════════════════════════
class P04_JoinPredicateMoveAround implements OptimizationPattern {
    public String getId()   { return "P04_JPMA"; }
    public String getName() { return "Join-Predicate Move-Around (JPMA)"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("JOIN") && sql.contains("WHERE")
            && sql.matches("(?s).*\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "A filter predicate on one side of a join could be propagated to the other " +
            "side via the join condition equivalence class, reducing both sides before joining.",
            "If A.x = B.x AND A.x = 5, infer B.x = 5 so both tables can use their indexes " +
            "independently before the join.",
            "MEDIUM",
            "MEDIUM — both tables can now filter independently using indexes, reducing the " +
            "number of rows entering the join operator.",
            "SELECT * FROM A JOIN B ON A.x = B.x WHERE A.x = 5",
            "SELECT * FROM A JOIN B ON A.x = B.x WHERE A.x = 5 AND B.x = 5"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P05 — Materialized CTE Optimization
// ════════════════════════════════════════════════════════════════════════════
class P05_MaterializedCTE implements OptimizationPattern {
    public String getId()   { return "P05_CTE_MATERIALIZATION"; }
    public String getName() { return "Materialized CTE Optimization"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase().trim();
        return sql.startsWith("WITH ") || sql.contains("\nWITH ");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "CTE referenced multiple times may be re-evaluated each time in MySQL 5.x. " +
            "MySQL 8.0+ materializes CTEs automatically, but complex cases may still " +
            "benefit from explicit temporary table materialization.",
            "Ensure the CTE is referenced only once, or use a temporary table for " +
            "guaranteed single execution. In MySQL 8.0+, SQL_MATERIALIZED hint forces materialization.",
            "MEDIUM",
            "MEDIUM — impact depends on CTE complexity and reference count; re-evaluation " +
            "of an expensive CTE on each reference multiplies its cost.",
            "WITH expensive AS (SELECT ...) SELECT a.*, b.* FROM expensive a, expensive b",
            "CREATE TEMPORARY TABLE expensive_tmp SELECT ...;\nSELECT a.*, b.* FROM expensive_tmp a, expensive_tmp b;"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P06 — GROUP BY Key Elimination
// ════════════════════════════════════════════════════════════════════════════
class P06_GroupByKeyElimination implements OptimizationPattern {
    public String getId()   { return "P06_GROUP_BY_KEY_ELIM"; }
    public String getName() { return "GROUP BY Key Elimination"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().contains("GROUP BY");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        String sql = stmt.toString().toUpperCase();
        // Only flag if there appear to be multiple GROUP BY keys
        if (!sql.matches("(?s).*GROUP BY.*,.*")) return Optional.empty();
        return Optional.of(buildApplication(
            "GROUP BY contains columns that may be functionally determined by other GROUP BY " +
            "columns (e.g., grouping by both a primary key and a column that depends on it).",
            "Remove functionally redundant GROUP BY keys. If id is the primary key, " +
            "GROUP BY id, name can be simplified to GROUP BY id because name is determined by id.",
            "LOW",
            "LOW — removes unnecessary grouping overhead; actual benefit depends on whether " +
            "the redundant key is truly functionally dependent.",
            "SELECT id, name, SUM(val) FROM t GROUP BY id, name  -- name is determined by id",
            "SELECT id, name, SUM(val) FROM t GROUP BY id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P07 — Aggregation Pushdown
// ════════════════════════════════════════════════════════════════════════════
class P07_AggregationPushdown implements OptimizationPattern {
    public String getId()   { return "P07_AGGREGATION_PUSHDOWN"; }
    public String getName() { return "Aggregation Pushdown"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("JOIN")
            && (sql.contains("SUM(") || sql.contains("COUNT(") || sql.contains("AVG("));
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Aggregation is computed on the full join result. Joining first then aggregating " +
            "processes the Cartesian product of rows before reducing them.",
            "Push the aggregate below the join: pre-aggregate the inner table, then join " +
            "the smaller aggregated result to the outer table.",
            "HIGH",
            "HIGH — pre-aggregation dramatically reduces the number of rows entering the join; " +
            "impact grows with the size of the inner table.",
            "SELECT d.name, SUM(o.amount)\nFROM orders o JOIN depts d ON o.dept_id = d.id\nGROUP BY d.name",
            "SELECT d.name, agg.total\nFROM depts d\nJOIN (SELECT dept_id, SUM(amount) AS total FROM orders GROUP BY dept_id) agg\n  ON agg.dept_id = d.id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P08 — Subquery Unnesting
// ════════════════════════════════════════════════════════════════════════════
class P08_SubqueryUnnesting implements OptimizationPattern {
    public String getId()   { return "P08_SUBQUERY_UNNESTING"; }
    public String getName() { return "Subquery Unnesting"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().matches("(?s).*FROM\\s*\\(\\s*SELECT.*\\).*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Derived table (subquery in FROM clause) creates a temporary result set. " +
            "The optimizer may not push outer predicates through it.",
            "Flatten the derived table into the outer query by merging WHERE clauses. " +
            "This exposes base tables to the join optimizer for predicate pushdown.",
            "MEDIUM",
            "MEDIUM — flattening lets the optimizer push predicates to base tables and " +
            "potentially use indexes that the derived-table form blocks.",
            "SELECT * FROM (SELECT id, name FROM customers WHERE active = 1) sub WHERE sub.id > 100",
            "SELECT id, name FROM customers WHERE active = 1 AND id > 100"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P09 — Index-Only Scan Projection
// ════════════════════════════════════════════════════════════════════════════
class P09_IndexOnlyScan implements OptimizationPattern {
    public String getId()   { return "P09_INDEX_ONLY_SCAN"; }
    public String getName() { return "Index-Only Scan Projection"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        if (stats.isEmpty()) return false;
        String sql = stmt.toString().toUpperCase();
        return sql.contains("SELECT") && !sql.contains("SELECT *")
            && sql.contains("WHERE");
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Query fetches columns from heap data pages even though a covering index " +
            "could supply all required columns (SELECT list + WHERE + ORDER BY).",
            "Create a covering index that includes all columns the query needs. " +
            "MySQL can then satisfy the query entirely from the B-tree without " +
            "random I/O to the heap.",
            "HIGH",
            "HIGH — eliminating random heap fetches is particularly impactful on large " +
            "tables with wide rows where the index covers a small fraction of each page.",
            "SELECT email FROM users WHERE status = 'active'  -- no covering index",
            "CREATE INDEX idx_users_status_email ON users(status, email);\n-- Now: index-only scan, no heap access"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P10 — Optimal Join Order (Dynamic Programming)
// ════════════════════════════════════════════════════════════════════════════
class P10_DPJoinOrder implements OptimizationPattern {
    public String getId()   { return "P10_DP_JOIN_ORDER"; }
    public String getName() { return "Optimal Join Order (Selinger DP)"; }
    public Tier   getTier() { return Tier.TIER1; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        try {
            List<String> tables = new TablesNamesFinder().getTableList(stmt); //NOSONAR
            return tables.size() >= 3;
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<OptimizationResult.PatternApplication> apply(
            Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Multi-table query with 3+ tables. Suboptimal join order produces large " +
            "intermediate results — joining two large tables before filtering with a smaller one.",
            "Selinger Dynamic Programming evaluates join-order subsets and selects the plan " +
            "with smallest intermediate rows. Join the smallest tables first. " +
            "See the Join Order section for the computed optimal order.",
            "MEDIUM",
            "MEDIUM — benefit depends on the size difference between tables; when one table " +
            "is much smaller, joining it first significantly reduces intermediate cardinality.",
            "SELECT * FROM big_table b JOIN medium m ON b.id=m.bid JOIN tiny t ON m.id=t.mid",
            "-- Optimal: (tiny ⋈ medium) ⋈ big_table\n-- Small tables joined first reduces intermediate rows"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Public factory — used by PatternRegistry
// ════════════════════════════════════════════════════════════════════════════
public class Tier1Patterns {
    public static List<OptimizationPattern> all() {
        return List.of(
            new P01_CorrelatedSubquery(),
            new P02_WindowPredicatePushdown(),
            new P03_SemiJoinReduction(),
            new P04_JoinPredicateMoveAround(),
            new P05_MaterializedCTE(),
            new P06_GroupByKeyElimination(),
            new P07_AggregationPushdown(),
            new P08_SubqueryUnnesting(),
            new P09_IndexOnlyScan(),
            new P10_DPJoinOrder()
        );
    }
}
