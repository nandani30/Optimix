package com.optimix.optimizer.patterns.tier2;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;
import net.sf.jsqlparser.statement.Statement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// ════════════════════════════════════════════════════════════════════════════
//  P11 — NOT IN Subquery → Anti-Join
// ════════════════════════════════════════════════════════════════════════════
class P11_NotInAntiJoin implements OptimizationPattern {
    public String getId()   { return "P11_NOT_IN_ANTI_JOIN"; }
    public String getName() { return "NOT IN Subquery → Anti-Join"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().contains("NOT IN");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "NOT IN (SELECT ...) forces nested-loop execution and returns no rows at all " +
            "if the subquery contains any NULL value — a subtle correctness bug.",
            "Rewrite to LEFT JOIN ... WHERE joined_col IS NULL (anti-join pattern). " +
            "The optimizer can execute this with a hash anti-join and handles NULLs correctly.",
            "HIGH",
            "HIGH — NOT IN with a subquery prevents hash anti-join; also the NULL-safety " +
            "issue means the current query may silently return wrong results.",
            "SELECT * FROM customers c WHERE c.id NOT IN (SELECT customer_id FROM orders)",
            "SELECT c.* FROM customers c\nLEFT JOIN orders o ON c.id = o.customer_id\nWHERE o.customer_id IS NULL"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P12 — EXISTS / NOT EXISTS → Semi-Join / Anti-Join
// ════════════════════════════════════════════════════════════════════════════
class P12_ExistsToSemiJoin implements OptimizationPattern {
    public String getId()   { return "P12_EXISTS_SEMI_JOIN"; }
    public String getName() { return "EXISTS/NOT EXISTS → Semi-Join/Anti-Join"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains(" EXISTS ") || sql.contains(" EXISTS(");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "EXISTS subquery may execute as a correlated nested loop in older MySQL versions.",
            "Convert to a dedicated semi-join using INNER JOIN + DISTINCT, enabling the " +
            "optimizer to choose a hash-based set membership test.",
            "MEDIUM",
            "MEDIUM — modern MySQL 8.0 already converts many EXISTS to semi-joins internally; " +
            "explicit rewrite helps older versions and makes the intent clearer.",
            "WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)",
            "INNER JOIN (SELECT DISTINCT customer_id FROM orders) o ON o.customer_id = c.id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P13 — Scalar Subquery in SELECT → JOIN
// ════════════════════════════════════════════════════════════════════════════
class P13_ScalarSubqueryToJoin implements OptimizationPattern {
    public String getId()   { return "P13_SCALAR_SELECT_JOIN"; }
    public String getName() { return "Scalar Subquery in SELECT → JOIN"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.matches("(?s).*SELECT\\s+[^F]*\\(\\s*SELECT[^)]+\\)[^F]*FROM.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Scalar subquery in SELECT list is correlated — it runs once per outer row.",
            "Extract to LEFT JOIN with a pre-aggregated derived table. The inner query " +
            "runs once; the result is joined back on the correlation key.",
            "HIGH",
            "HIGH — each outer row triggers a separate execution of the inner query; " +
            "with large outer tables this creates a multiplicative execution pattern.",
            "SELECT c.name, (SELECT COUNT(*) FROM orders o WHERE o.cust_id = c.id) FROM customers c",
            "SELECT c.name, COALESCE(agg.cnt, 0)\nFROM customers c\nLEFT JOIN (SELECT cust_id, COUNT(*) cnt FROM orders GROUP BY cust_id) agg\n  ON agg.cust_id = c.id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P14 — Function Removal from Indexed Columns (SARGability)
// ════════════════════════════════════════════════════════════════════════════
class P14_FunctionOnColumn implements OptimizationPattern {
    public String getId()   { return "P14_FUNCTION_ON_COLUMN"; }
    public String getName() { return "Function Removal from Indexed Columns"; }
    public Tier   getTier() { return Tier.TIER2; }

    private static final List<String> DATE_FNS   = List.of("YEAR(", "MONTH(", "DAY(", "DATE(", "QUARTER(", "WEEK(");
    private static final List<String> STRING_FNS = List.of("LOWER(", "UPPER(", "TRIM(", "LTRIM(", "RTRIM(");

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        if (!sql.contains("WHERE")) return false;
        for (String fn : DATE_FNS)   { if (sql.contains(fn)) return true; }
        for (String fn : STRING_FNS) { if (sql.contains(fn)) return true; }
        return false;
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        String sql = stmt.toString().toUpperCase();
        String before, after;
        if (sql.contains("YEAR(")) {
            before = "WHERE YEAR(created_at) = 2024";
            after  = "WHERE created_at >= '2024-01-01' AND created_at < '2025-01-01'";
        } else if (sql.contains("MONTH(")) {
            before = "WHERE MONTH(order_date) = 3";
            after  = "WHERE order_date >= '2024-03-01' AND order_date < '2024-04-01'";
        } else if (sql.contains("LOWER(") || sql.contains("UPPER(")) {
            before = "WHERE LOWER(email) = 'user@example.com'";
            after  = "WHERE email = 'user@example.com'  -- use case-insensitive collation";
        } else {
            before = "WHERE DATE(event_time) = '2024-01-15'";
            after  = "WHERE event_time >= '2024-01-15' AND event_time < '2024-01-16'";
        }
        return Optional.of(buildApplication(
            "Wrapping a column in a function (YEAR(), LOWER(), etc.) in a WHERE clause " +
            "prevents index usage — MySQL cannot use a B-tree index on a derived value.",
            "Rewrite the condition as an equivalent range predicate on the raw column. " +
            "The column becomes SARGable (Search ARGument able) and the index can be used.",
            "HIGH",
            "HIGH — this transforms a full table scan into an index range scan; " +
            "impact scales directly with table size.",
            before, after
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P15 — Outer JOIN → Inner JOIN
// ════════════════════════════════════════════════════════════════════════════
class P15_OuterToInnerJoin implements OptimizationPattern {
    public String getId()   { return "P15_OUTER_TO_INNER"; }
    public String getName() { return "Outer JOIN → Inner JOIN Conversion"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return (sql.contains("LEFT JOIN") || sql.contains("RIGHT JOIN")) && sql.contains("WHERE");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "LEFT JOIN used, but the WHERE clause filters on the right table's non-key columns. " +
            "Any NULL-extended rows from the outer join are immediately rejected by WHERE, " +
            "making the LEFT JOIN semantically equivalent to INNER JOIN.",
            "Replace LEFT JOIN with INNER JOIN. Query result is identical because the WHERE " +
            "clause already excludes NULL-extended rows.",
            "LOW",
            "LOW — INNER JOIN enables more join algorithm choices and slightly better " +
            "cardinality estimation; correctness is the primary motivation.",
            "SELECT * FROM a LEFT JOIN b ON a.id = b.a_id WHERE b.status = 'active'",
            "SELECT * FROM a INNER JOIN b ON a.id = b.a_id WHERE b.status = 'active'"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P16 — Transitive Predicate Generation
// ════════════════════════════════════════════════════════════════════════════
class P16_TransitivePredicate implements OptimizationPattern {
    public String getId()   { return "P16_TRANSITIVE_PRED"; }
    public String getName() { return "Transitive Predicate Generation"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("JOIN") && sql.contains("WHERE")
            && sql.matches("(?s).*\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+.*=.*\\d+.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Filter on one side of a join, but the join predicate creates equivalence " +
            "classes that could propagate the filter to the other side.",
            "Infer B.x = 5 from A.x = B.x AND A.x = 5. Both tables can now filter " +
            "independently using their own indexes before joining.",
            "MEDIUM",
            "MEDIUM — both sides independently filtered before joining reduces join input " +
            "cardinality; particularly effective when the filtered column is indexed on both sides.",
            "SELECT * FROM A JOIN B ON A.x = B.x WHERE A.x = 5",
            "SELECT * FROM A JOIN B ON A.x = B.x WHERE A.x = 5 AND B.x = 5  -- inferred"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P17 — Redundant JOIN Elimination
// ════════════════════════════════════════════════════════════════════════════
class P17_RedundantJoin implements OptimizationPattern {
    public String getId()   { return "P17_REDUNDANT_JOIN"; }
    public String getName() { return "Redundant JOIN Elimination"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        try {
            List<String> tables = new net.sf.jsqlparser.util.TablesNamesFinder().getTableList(stmt); //NOSONAR
            long distinct = tables.stream().map(String::toLowerCase).distinct().count();
            return tables.size() > distinct;
        } catch (Exception e) { return false; }
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "The same table appears to be joined more than once with identical or redundant conditions.",
            "Deduplicate the join. Reference the single join alias everywhere the duplicate was used.",
            "MEDIUM",
            "MEDIUM — eliminates one full table scan and join operation per redundant reference.",
            "FROM orders o\nJOIN customers c ON o.cust_id = c.id\nJOIN customers c2 ON o.cust_id = c2.id  -- duplicate",
            "FROM orders o\nJOIN customers c ON o.cust_id = c.id  -- single join, use c everywhere"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P18 — Sort Elimination via Index
// ════════════════════════════════════════════════════════════════════════════
class P18_SortElimination implements OptimizationPattern {
    public String getId()   { return "P18_SORT_ELIMINATION"; }
    public String getName() { return "Sort Elimination via Index"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().contains("ORDER BY");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "ORDER BY requires an explicit sort operation unless rows are already retrieved " +
            "in the required order via an index. EXPLAIN shows 'Using filesort' when this occurs.",
            "Create an index on the ORDER BY column(s) in the same direction (ASC/DESC). " +
            "MySQL can then read rows in sorted order directly from the index, eliminating the sort.",
            "HIGH",
            "HIGH — sorting all rows before returning results is O(N log N); an index makes " +
            "retrieval in order O(N). Check EXPLAIN 'Extra' for 'Using filesort' to confirm.",
            "SELECT * FROM orders ORDER BY created_at DESC  -- Using filesort",
            "CREATE INDEX idx_orders_created ON orders(created_at DESC);\n-- EXPLAIN will now show: Using index"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P19 — DISTINCT Elimination on Unique Columns
// ════════════════════════════════════════════════════════════════════════════
class P19_DistinctElimination implements OptimizationPattern {
    public String getId()   { return "P19_DISTINCT_ELIM"; }
    public String getName() { return "DISTINCT Elimination on UNIQUE Columns"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().contains("DISTINCT");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "SELECT DISTINCT applied to columns that may already be unique by constraint. " +
            "Deduplication on an already-unique column wastes sort/hash resources.",
            "Verify via SHOW INDEX / information_schema whether the selected columns " +
            "are covered by a UNIQUE or PRIMARY KEY constraint, then remove DISTINCT.",
            "LOW",
            "LOW — removes a deduplication step that was already a no-op; " +
            "must be verified against the actual schema before applying.",
            "SELECT DISTINCT user_id FROM users  -- user_id is PRIMARY KEY",
            "SELECT user_id FROM users  -- DISTINCT is redundant on a PK"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P20 — UNION → UNION ALL
// ════════════════════════════════════════════════════════════════════════════
class P20_UnionToUnionAll implements OptimizationPattern {
    public String getId()   { return "P20_UNION_ALL"; }
    public String getName() { return "UNION → UNION ALL Conversion"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains(" UNION ") && !sql.contains("UNION ALL");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "UNION performs an expensive deduplication pass on the combined result set. " +
            "This is unnecessary when the sub-queries are guaranteed to return disjoint rows.",
            "If rows cannot overlap (e.g., disjoint WHERE conditions, different partitions), " +
            "replace UNION with UNION ALL to skip deduplication.",
            "MEDIUM",
            "MEDIUM — deduplication requires a sort or hash over the full combined result set; " +
            "UNION ALL avoids this entirely when disjointness can be proven.",
            "SELECT id FROM archived_orders WHERE year = 2023\nUNION\nSELECT id FROM archived_orders WHERE year = 2024",
            "SELECT id FROM archived_orders WHERE year = 2023\nUNION ALL\nSELECT id FROM archived_orders WHERE year = 2024  -- years are disjoint"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P21 — COUNT(*) > 0 → EXISTS
// ════════════════════════════════════════════════════════════════════════════
class P21_CountToExists implements OptimizationPattern {
    public String getId()   { return "P21_COUNT_TO_EXISTS"; }
    public String getName() { return "COUNT(*) > 0 → EXISTS"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase().replaceAll("\\s+", " ");
        return sql.contains("COUNT(*)") && (sql.contains("> 0") || sql.contains(">0"));
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "COUNT(*) > 0 scans and counts ALL matching rows before comparing. " +
            "Even 1 million matching rows are all counted before the result is used.",
            "Replace with EXISTS, which stops scanning at the very first matching row.",
            "HIGH",
            "HIGH — COUNT(*) must process every matching row; EXISTS short-circuits at the " +
            "first match, so impact grows with the number of matches in the inner query.",
            "SELECT * FROM orders WHERE (SELECT COUNT(*) FROM items WHERE order_id = orders.id) > 0",
            "SELECT * FROM orders WHERE EXISTS (SELECT 1 FROM items WHERE order_id = orders.id)"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P22 — OR Conditions → UNION Decomposition
// ════════════════════════════════════════════════════════════════════════════
class P22_OrToUnion implements OptimizationPattern {
    public String getId()   { return "P22_OR_TO_UNION"; }
    public String getName() { return "OR Conditions → UNION Decomposition"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("WHERE") && sql.contains(" OR ") && !sql.contains("UNION");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "WHERE col1 = x OR col2 = y on different columns prevents MySQL from using " +
            "indexes on both columns simultaneously, often degrading to a full table scan.",
            "Split into two queries joined with UNION ALL. Each branch has its own WHERE " +
            "clause and can independently use its column's index.",
            "MEDIUM",
            "MEDIUM — each branch uses its own index; combined cost is two index scans " +
            "vs one full table scan; effective when both indexed columns have high selectivity.",
            "SELECT * FROM users WHERE email = 'a@b.com' OR phone = '123-456'",
            "SELECT * FROM users WHERE email = 'a@b.com'\nUNION ALL\nSELECT * FROM users WHERE phone = '123-456' AND email != 'a@b.com'"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P23 — Standard Predicate Pushdown
// ════════════════════════════════════════════════════════════════════════════
class P23_PredicatePushdown implements OptimizationPattern {
    public String getId()   { return "P23_PREDICATE_PUSHDOWN"; }
    public String getName() { return "Standard Predicate Pushdown"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("HAVING") && !sql.contains("GROUP BY");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "HAVING clause used without GROUP BY acts as a WHERE but filters after all " +
            "rows are already fetched rather than before.",
            "Move non-aggregate HAVING conditions to the WHERE clause. " +
            "WHERE filters before row fetching; HAVING filters after aggregation.",
            "MEDIUM",
            "MEDIUM — WHERE allows the optimizer to use indexes and reduces rows flowing " +
            "into subsequent operations; HAVING operates on the fully-scanned result.",
            "SELECT * FROM orders HAVING amount > 100  -- no GROUP BY",
            "SELECT * FROM orders WHERE amount > 100   -- pushed to WHERE"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P24 — Projection Pushdown (SELECT *)
// ════════════════════════════════════════════════════════════════════════════
class P24_ProjectionPushdown implements OptimizationPattern {
    public String getId()   { return "P24_PROJECTION_PUSH"; }
    public String getName() { return "Projection Pushdown"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString();
        return sql.contains("SELECT *") || sql.contains("SELECT\n*") || sql.contains("SELECT \n*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "SELECT * fetches all columns. Wide tables with many columns waste I/O " +
            "fetching data the application never uses.",
            "Replace * with only the columns the application actually reads. " +
            "This also enables covering index opportunities.",
            "MEDIUM",
            "MEDIUM — reduces data transfer and enables covering index scans; " +
            "impact is higher for tables with many wide columns (BLOBs, long VARCHAR).",
            "SELECT * FROM orders o JOIN customers c ON o.cust_id = c.id",
            "SELECT o.id, o.amount, o.status, c.name, c.email\nFROM orders o JOIN customers c ON o.cust_id = c.id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P25 — Cartesian Product Detection
// ════════════════════════════════════════════════════════════════════════════
class P25_CartesianProduct implements OptimizationPattern {
    public String getId()   { return "P25_CARTESIAN"; }
    public String getName() { return "Cartesian Product Detection"; }
    public Tier   getTier() { return Tier.TIER2; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        try {
            List<String> tables = new net.sf.jsqlparser.util.TablesNamesFinder().getTableList(stmt); //NOSONAR
            if (tables.size() < 2) return false;
            String sql = stmt.toString().toUpperCase();
            return !sql.contains("JOIN") && sql.contains(",")
                && sql.matches("(?s).*FROM\\s+\\w+\\s*,\\s*\\w+.*");
        } catch (Exception e) { return false; }
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "⚠️ CARTESIAN PRODUCT: Multiple tables in FROM with no join condition. " +
            "Produces N × M rows — e.g., 1,000 customers × 50,000 orders = 50,000,000 rows. " +
            "This is almost always a bug.",
            "Add an explicit JOIN condition between the tables. " +
            "If a cross join is intentional, use CROSS JOIN keyword to be explicit.",
            "HIGH",
            "HIGH — a missing join condition causes catastrophic row explosion; " +
            "this is usually a correctness bug, not just a performance issue.",
            "SELECT * FROM customers, orders  -- no join condition!",
            "SELECT * FROM customers c JOIN orders o ON c.id = o.customer_id"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Public factory
// ════════════════════════════════════════════════════════════════════════════
public class Tier2Patterns {
    public static List<OptimizationPattern> all() {
        return List.of(
            new P11_NotInAntiJoin(),
            new P12_ExistsToSemiJoin(),
            new P13_ScalarSubqueryToJoin(),
            new P14_FunctionOnColumn(),
            new P15_OuterToInnerJoin(),
            new P16_TransitivePredicate(),
            new P17_RedundantJoin(),
            new P18_SortElimination(),
            new P19_DistinctElimination(),
            new P20_UnionToUnionAll(),
            new P21_CountToExists(),
            new P22_OrToUnion(),
            new P23_PredicatePushdown(),
            new P24_ProjectionPushdown(),
            new P25_CartesianProduct()
        );
    }
}
