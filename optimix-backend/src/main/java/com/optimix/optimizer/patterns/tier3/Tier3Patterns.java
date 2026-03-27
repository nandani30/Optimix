package com.optimix.optimizer.patterns.tier3;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.statement.Statement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// ════════════════════════════════════════════════════════════════════════════
//  P26 — SELECT * Expansion
// ════════════════════════════════════════════════════════════════════════════
class P26_SelectStar implements OptimizationPattern {
    public String getId()   { return "P26_SELECT_STAR"; }
    public String getName() { return "SELECT * → Specific Columns"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().matches("(?s).*SELECT\\s+\\*.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "SELECT * fetches every column — including ones the application never reads.",
            "Replace * with only the columns needed by the WHERE clause, ORDER BY, and application logic.",
            "LOW",
            "LOW — reduces data transfer; for wide tables (20+ columns or large TEXT/BLOB fields) " +
            "savings can be significant but the pattern itself is not algorithmically expensive.",
            "SELECT * FROM products WHERE category = 'electronics'",
            "SELECT id, name, price, stock FROM products WHERE category = 'electronics'"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P27 — Constant Folding
// ════════════════════════════════════════════════════════════════════════════
class P27_ConstantFolding implements OptimizationPattern {
    public String getId()   { return "P27_CONSTANT_FOLDING"; }
    public String getName() { return "Constant Folding"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().matches("(?s).*\\b\\d+\\s*[+\\-*]\\s*\\d+\\b.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Constant arithmetic expression in a WHERE clause is re-evaluated for every row.",
            "Pre-compute the constant expression and replace with the literal result.",
            "LOW",
            "LOW — eliminates per-row arithmetic; negligible for small tables but adds up " +
            "in high-QPS workloads processing millions of rows.",
            "WHERE salary > 40000 + 5000",
            "WHERE salary > 45000"
        ));
    }

    /** Try to evaluate a purely constant arithmetic expression. Returns null if not constant. */
    public static Double tryFold(Expression expr) {
        if (expr instanceof LongValue)   return (double)((LongValue)expr).getValue();
        if (expr instanceof DoubleValue) return ((DoubleValue)expr).getValue();
        if (expr instanceof Addition a) {
            Double l = tryFold(a.getLeftExpression()), r = tryFold(a.getRightExpression());
            return (l != null && r != null) ? l + r : null;
        }
        if (expr instanceof Subtraction s) {
            Double l = tryFold(s.getLeftExpression()), r = tryFold(s.getRightExpression());
            return (l != null && r != null) ? l - r : null;
        }
        if (expr instanceof Multiplication m) {
            Double l = tryFold(m.getLeftExpression()), r = tryFold(m.getRightExpression());
            return (l != null && r != null) ? l * r : null;
        }
        if (expr instanceof Division d) {
            Double l = tryFold(d.getLeftExpression()), r = tryFold(d.getRightExpression());
            return (l != null && r != null && r != 0) ? l / r : null;
        }
        return null;
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P28 — Expression Simplification (De Morgan's law)
// ════════════════════════════════════════════════════════════════════════════
class P28_ExpressionSimplification implements OptimizationPattern {
    public String getId()   { return "P28_EXPR_SIMPLIFY"; }
    public String getName() { return "Expression Simplification"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().matches("(?s).*NOT\\s*\\(.*[><!=].*\\).*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "NOT(condition) wraps a comparison operator — semantically clear but harder " +
            "for the optimizer to match against index range conditions.",
            "Apply De Morgan's law: NOT(a > b) → a <= b. Remove the NOT wrapper.",
            "LOW",
            "LOW — simpler predicate form; more likely to be recognized as an index range condition.",
            "WHERE NOT(age > 18)",
            "WHERE age <= 18"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P29 — Redundant Filter Removal
// ════════════════════════════════════════════════════════════════════════════
class P29_RedundantFilter implements OptimizationPattern {
    public String getId()   { return "P29_REDUNDANT_FILTER"; }
    public String getName() { return "Redundant Filter Removal"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().replaceAll("\\s+", " ");
        return sql.contains("1=1") || sql.contains("1 = 1") || sql.contains("TRUE");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Always-true predicates (WHERE 1=1, WHERE TRUE) add zero filtering but consume " +
            "parse and planning time.",
            "Remove tautological conditions. Consolidate overlapping ranges " +
            "(age > 5 AND age > 10 → age > 10).",
            "LOW",
            "LOW — each individual query saves only parse overhead; cumulative effect matters " +
            "at high query-per-second rates.",
            "WHERE 1=1 AND status = 'active' AND age > 5 AND age > 10",
            "WHERE status = 'active' AND age > 10"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P30 — Algebraic Identity Laws
// ════════════════════════════════════════════════════════════════════════════
class P30_AlgebraicIdentity implements OptimizationPattern {
    public String getId()   { return "P30_ALGEBRAIC_ID"; }
    public String getName() { return "Algebraic Identity Laws"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString();
        return sql.matches("(?s).*\\*\\s*1[^0-9].*") || sql.matches("(?s).*\\+\\s*0[^.].*")
            || sql.matches("(?s).*-\\s*0[^.].*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "No-op arithmetic (× 1, + 0, − 0) is evaluated on every row processed.",
            "Eliminate identity operations at optimization time so they never reach execution.",
            "LOW",
            "LOW — removes per-row no-ops; small improvement that is effectively free.",
            "WHERE price * 1 > 100",
            "WHERE price > 100"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P31 — IN-List Flattening
// ════════════════════════════════════════════════════════════════════════════
class P31_InListFlatten implements OptimizationPattern {
    public String getId()   { return "P31_IN_LIST_FLATTEN"; }
    public String getName() { return "IN-List Flattening"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        return stmt.toString().toUpperCase().matches("(?s).*\\bIN\\s*\\(\\s*\\d+\\s*\\).*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "IN list with a single value — set membership test is unnecessary overhead.",
            "Replace IN (x) with = x.",
            "LOW",
            "LOW — simpler equality comparison; same index usage, cleaner execution plan.",
            "WHERE status IN (1)",
            "WHERE status = 1"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P32 — Nullability Propagation
// ════════════════════════════════════════════════════════════════════════════
class P32_NullabilityPropagation implements OptimizationPattern {
    public String getId()   { return "P32_NULLABILITY"; }
    public String getName() { return "Nullability Propagation"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        if (stats.isEmpty()) return false;
        String sql = stmt.toString().toUpperCase();
        if (!sql.contains("IS NULL")) return false;
        // Only report if we have real column stats to check nullability
        return stats.values().stream().anyMatch(s -> !s.columns.isEmpty());
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "IS NULL check on a column — if the column is declared NOT NULL in the schema, " +
            "this condition can never be true.",
            "Connect a DB to verify nullability from information_schema. If the column is NOT NULL, " +
            "the condition is a contradiction and the query can return empty immediately.",
            "LOW",
            "LOW — when confirmed against real schema data, a WHERE FALSE short-circuit " +
            "eliminates all execution; requires DB connection to verify.",
            "WHERE email IS NULL  -- requires verification: is email NOT NULL?",
            "-- If NOT NULL confirmed: WHERE FALSE (empty result, zero cost)"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P33 — Boolean Normalization (CNF)
// ════════════════════════════════════════════════════════════════════════════
class P33_BooleanNormalization implements OptimizationPattern {
    public String getId()   { return "P33_BOOL_CNF"; }
    public String getName() { return "Boolean Normalization (CNF)"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains(" OR ") && sql.contains(" AND ");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Complex mixed AND/OR expression may not be in the most optimizer-friendly form.",
            "Convert to Conjunctive Normal Form (AND of ORs). Makes predicate structure " +
            "explicit and enables better selectivity estimation.",
            "LOW",
            "LOW — better selectivity estimates lead to better index and join decisions; " +
            "impact depends on how complex and nested the boolean expression is.",
            "WHERE (a = 1 OR b = 2) AND (c = 3 OR d = 4)",
            "-- Already CNF. If deeply nested: apply distribution law to flatten OR into AND."
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P34 — Short-Circuit Optimization
// ════════════════════════════════════════════════════════════════════════════
class P34_ShortCircuit implements OptimizationPattern {
    public String getId()   { return "P34_SHORT_CIRCUIT"; }
    public String getName() { return "Short-Circuit Optimization"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase().replaceAll("\\s+", " ");
        return sql.contains("LIMIT 0") || sql.contains("WHERE FALSE")
            || sql.contains("WHERE 0 = 1") || sql.contains("WHERE 0=1");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Query is provably guaranteed to return zero rows (LIMIT 0 / WHERE FALSE). " +
            "Executing it fully is wasteful.",
            "Return an empty result set immediately. MySQL handles LIMIT 0 automatically. " +
            "WHERE FALSE may still cause a partial table access depending on optimizer version.",
            "HIGH",
            "HIGH — a provably empty query with full execution is pure waste; " +
            "this is typically a generated-query bug that should be fixed at the application layer.",
            "SELECT * FROM orders WHERE FALSE",
            "-- Returns empty set immediately without any table access"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P35 — LIMIT Pushdown
// ════════════════════════════════════════════════════════════════════════════
class P35_LimitPushdown implements OptimizationPattern {
    public String getId()   { return "P35_LIMIT_PUSHDOWN"; }
    public String getName() { return "LIMIT Pushdown"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.contains("LIMIT") && !sql.contains("ORDER BY");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "LIMIT applied without ORDER BY — the table may be fully scanned before " +
            "discarding most rows.",
            "Without ORDER BY, LIMIT can be pushed to the scan operator so it stops early. " +
            "Verify EXPLAIN shows 'Using limit' or equivalent early-stop behavior.",
            "MEDIUM",
            "MEDIUM — scan stops after finding N rows instead of reading the whole table; " +
            "impact depends on how early qualifying rows appear in the scan order.",
            "SELECT * FROM logs LIMIT 10  -- may scan entire table",
            "-- Confirm with EXPLAIN: look for 'rows' estimate close to LIMIT value"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P36 — Duplicate Subquery Elimination
// ════════════════════════════════════════════════════════════════════════════
class P36_DuplicateSubquery implements OptimizationPattern {
    public String getId()   { return "P36_DUPE_SUBQUERY"; }
    public String getName() { return "Duplicate Subquery Elimination"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        int count = 0, idx = 0;
        while ((idx = sql.indexOf("(SELECT", idx)) >= 0) { count++; idx++; }
        return count >= 2;
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Same subquery appears multiple times in the query — each occurrence is executed independently.",
            "Extract the repeated subquery into a CTE (WITH clause). Reference the CTE name " +
            "each time instead of repeating the subquery.",
            "MEDIUM",
            "MEDIUM — the subquery executes once and the result is reused; benefit scales with " +
            "the cost of the subquery and the number of times it is referenced.",
            "SELECT * FROM t WHERE val > (SELECT AVG(val) FROM t) AND val < (SELECT AVG(val) FROM t) * 2",
            "WITH avg_val AS (SELECT AVG(val) AS a FROM t)\nSELECT * FROM t, avg_val WHERE val > a AND val < a * 2"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P37 — Implicit JOIN → Explicit JOIN
// ════════════════════════════════════════════════════════════════════════════
class P37_ImplicitJoin implements OptimizationPattern {
    public String getId()   { return "P37_IMPLICIT_JOIN"; }
    public String getName() { return "Implicit JOIN → Explicit JOIN Syntax"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.matches("(?s).*FROM\\s+\\w+\\s*,\\s*\\w+.*WHERE.*\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "SQL-89 implicit join syntax (FROM a, b WHERE a.id = b.fk). " +
            "Easy to accidentally omit the join condition, creating a Cartesian product.",
            "Rewrite using explicit SQL-92 JOIN ... ON syntax. The join condition is " +
            "collocated with the join, not buried in WHERE.",
            "LOW",
            "LOW — primarily a correctness and maintainability improvement; the optimizer " +
            "treats both forms identically when the join condition is correct.",
            "SELECT * FROM customers c, orders o WHERE c.id = o.customer_id AND o.status = 'active'",
            "SELECT * FROM customers c\nJOIN orders o ON c.id = o.customer_id\nWHERE o.status = 'active'"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P38 — Scalar Subquery → JOIN (WHERE clause version)
// ════════════════════════════════════════════════════════════════════════════
class P38_ScalarToJoin implements OptimizationPattern {
    public String getId()   { return "P38_SCALAR_JOIN"; }
    public String getName() { return "Scalar Subquery → JOIN"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        // P13 covers SELECT-list correlated subqueries.
        // P38 targets WHERE-clause scalar subqueries comparing to aggregates.
        String sql = stmt.toString().toUpperCase();
        return sql.contains("WHERE") && sql.matches("(?s).*WHERE.*\\(\\s*SELECT\\s+(MIN|MAX|AVG|SUM|COUNT).*\\).*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        return Optional.of(buildApplication(
            "Scalar aggregate subquery in WHERE clause runs as a separate query, then the result " +
            "is compared row by row in the outer query.",
            "Pre-compute the scalar value as a CTE or derived table join so it is computed once.",
            "MEDIUM",
            "MEDIUM — scalar aggregate executes once but its result is compared to every outer row; " +
            "CTE reuse avoids re-evaluation if the subquery is referenced multiple times.",
            "SELECT * FROM employees WHERE salary > (SELECT AVG(salary) FROM employees)",
            "WITH avg_s AS (SELECT AVG(salary) AS avg_salary FROM employees)\nSELECT e.* FROM employees e JOIN avg_s ON e.salary > avg_s.avg_salary"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P39 — Index SARGability Check
// ════════════════════════════════════════════════════════════════════════════
class P39_Sargability implements OptimizationPattern {
    public String getId()   { return "P39_SARGABILITY"; }
    public String getName() { return "Index SARGability Check"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        String sql = stmt.toString().toUpperCase();
        return sql.matches("(?s).*LIKE\\s+'%.*'.*")
            || sql.matches("(?s).*WHERE.*\\w+\\s*[+\\-*/].*=.*");
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        String sql = stmt.toString().toUpperCase();
        boolean leadingWildcard = sql.matches("(?s).*LIKE\\s+'%.*'.*");
        return Optional.of(buildApplication(
            leadingWildcard
                ? "Leading wildcard LIKE '%value%' — MySQL cannot use a B-tree index for prefix matching."
                : "Arithmetic on column in WHERE (e.g. WHERE col + 1 = 5) prevents index usage.",
            leadingWildcard
                ? "Consider a FULLTEXT index for text search, or redesign to LIKE 'prefix%' if prefix matching is acceptable."
                : "Move arithmetic to the literal side: WHERE col + 1 = 5 → WHERE col = 4.",
            "HIGH",
            "HIGH — non-SARGable predicates force full table scans regardless of existing indexes; " +
            "confirmed by EXPLAIN showing type=ALL on a table that has an index.",
            leadingWildcard ? "WHERE name LIKE '%john%'" : "WHERE age + 1 = 26",
            leadingWildcard ? "-- FULLTEXT: MATCH(name) AGAINST('john')" : "WHERE age = 25"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P40 — Missing Index Detection
// ════════════════════════════════════════════════════════════════════════════
class P40_MissingIndex implements OptimizationPattern {
    public String getId()   { return "P40_MISSING_INDEX"; }
    public String getName() { return "Missing Index Detection"; }
    public Tier   getTier() { return Tier.TIER3; }

    public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
        if (stats.isEmpty()) return false;
        String sql = stmt.toString().toUpperCase();
        if (!sql.contains("WHERE") && !sql.contains("JOIN")) return false;
        // Only report when we have real stats confirming no indexes exist
        return stats.values().stream().anyMatch(s -> s.indexes.isEmpty() && s.rowCount > 1000);
    }

    public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
        if (!detect(stmt, stats)) return Optional.empty();
        TableStatistics worst = stats.values().stream()
            .filter(s -> s.indexes.isEmpty() && s.rowCount > 1000)
            .max((a, b) -> Long.compare(a.rowCount, b.rowCount))
            .orElse(null);
        if (worst == null) return Optional.empty();

        return Optional.of(buildApplication(
            "Table '" + worst.tableName + "' (" + worst.rowCount + " rows confirmed via " +
            "information_schema) has no indexes. All queries against this table require full scans. " +
            "This was confirmed by SHOW INDEX returning no results.",
            "Analyze which columns appear in WHERE, JOIN ON, and ORDER BY clauses. " +
            "Create indexes on the highest-selectivity columns first. " +
            "See the Index Recommendations section for specific CREATE INDEX statements.",
            "HIGH",
            "HIGH — every query against '" + worst.tableName + "' does a full table scan " +
            "regardless of WHERE selectivity; a primary key or selective index is essential.",
            "-- '" + worst.tableName + "' has no indexes — SHOW INDEX returned empty",
            "CREATE INDEX idx_" + worst.tableName + "_id ON " + worst.tableName + "(id);\n" +
            "-- Then add indexes on columns used in WHERE / JOIN based on your query patterns"
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Public factory
// ════════════════════════════════════════════════════════════════════════════
public class Tier3Patterns {
    public static List<OptimizationPattern> all() {
        return List.of(
            new P26_SelectStar(),
            new P27_ConstantFolding(),
            new P28_ExpressionSimplification(),
            new P29_RedundantFilter(),
            new P30_AlgebraicIdentity(),
            new P31_InListFlatten(),
            new P32_NullabilityPropagation(),
            new P33_BooleanNormalization(),
            new P34_ShortCircuit(),
            new P35_LimitPushdown(),
            new P36_DuplicateSubquery(),
            new P37_ImplicitJoin(),
            new P38_ScalarToJoin(),
            new P39_Sargability(),
            new P40_MissingIndex()
        );
    }
}
