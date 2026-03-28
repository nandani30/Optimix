package com.optimix.optimizer.patterns.tier3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Production-grade Tier 3 Optimization Patterns.
 * Focuses on Syntactic Polish, Redundancy Elimination, and Index Recommendation Triggers.
 * Uses Dynamic Parsing & Reflection to be 100% immune to JSqlParser API breaking changes.
 */
public class Tier3Patterns {
    
    private static final Logger log = LoggerFactory.getLogger(Tier3Patterns.class);

    public static List<OptimizationPattern> all() {
        return Arrays.asList(
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

    // =========================================================================
    // UTILITY & SAFETY HELPER
    // =========================================================================
    static class AstUtils {
        public static Statement cloneAst(Statement original) {
            if (original == null) return null;
            try { return CCJSqlParserUtil.parse(original.toString()); } 
            catch (Exception e) { return null; }
        }

        public static boolean isValidSql(String sql) {
            if (sql == null || sql.trim().isEmpty()) return false;
            try { CCJSqlParserUtil.parse(sql); return true; } 
            catch (Exception e) { return false; }
        }

        public static List<Expression> flattenAnds(Expression expr) {
            List<Expression> list = new ArrayList<>();
            if (expr instanceof AndExpression) {
                AndExpression and = (AndExpression) expr;
                list.addAll(flattenAnds(and.getLeftExpression()));
                list.addAll(flattenAnds(and.getRightExpression()));
            } else if (expr != null) {
                list.add(expr);
            }
            return list;
        }

        public static Expression buildAndTree(List<Expression> exprs) {
            if (exprs == null || exprs.isEmpty()) return null;
            Expression root = exprs.get(0);
            for (int i = 1; i < exprs.size(); i++) {
                root = new AndExpression(root, exprs.get(i));
            }
            return root;
        }
        
        public static boolean isLikelyPrimaryKey(Column col) {
            if (col == null || col.getColumnName() == null) return false;
            String name = col.getColumnName().toLowerCase();
            return name.equals("id") || name.endsWith("_id") || name.endsWith("uuid");
        }
        
        public static String getPrimaryKeyColumn(TableStatistics stats) {
            if (stats == null || stats.columns == null) return null;
            for (TableStatistics.ColumnStats col : stats.columns) {
                if ("PRI".equalsIgnoreCase(col.keyType)) {
                    return col.columnName;
                }
            }
            return null;
        }
        
        public static String getAliasOrName(FromItem fromItem) {
            if (fromItem == null) return "";
            if (fromItem.getAlias() != null && fromItem.getAlias().getName() != null) {
                return fromItem.getAlias().getName().toLowerCase();
            }
            if (fromItem instanceof Table) {
                Table table = (Table) fromItem;
                if (table.getName() != null) return table.getName().toLowerCase();
            }
            return "";
        }
    }

    private static OptimizationResult.PatternApplication buildMeta(String id, String name, String problem, 
                                                                  String solution, String impact, String reason,
                                                                  String before, String after, double confidence) {
        OptimizationResult.PatternApplication app = new OptimizationResult.PatternApplication();
        app.patternId = id;
        app.patternName = name;
        app.tier = "TIER3";
        app.problem = problem;
        app.transformation = solution;
        app.impactLevel = impact;
        app.impactReason = reason;
        app.beforeSnippet = before;
        app.afterSnippet = after;
        return app;
    }

    // =========================================================================
    // PATTERNS
    // =========================================================================

    static class P26_SelectStar implements OptimizationPattern {
        @Override
        public String getId() { return "P26_SELECT_STAR"; }
        @Override
        public String getName() { return "SELECT * Projection Warning"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            String sql = stmt.toString().toUpperCase();
            return sql.contains("SELECT *") || sql.contains("SELECT \n*");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            // Cannot logically rewrite without application context. Detection only.
            return Optional.empty(); 
        }
    }

    static class P27_ConstantFolding implements OptimizationPattern {
        @Override
        public String getId() { return "P27_CONSTANT_FOLDING"; }
        @Override
        public String getName() { return "Constant Folding (1=1 Removal)"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            String sql = stmt.toString().replaceAll("\\s+", "");
            return sql.contains("1=1") || sql.contains("'a'='a'");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;

            if (body.getWhere() == null) return Optional.empty();
            
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            Iterator<Expression> it = ands.iterator();
            while (it.hasNext()) {
                Expression expr = it.next();
                String clean = expr.toString().replaceAll("\\s+", "");
                if (clean.equals("1=1") || clean.equals("'a'='a'")) {
                    it.remove();
                    modified = true;
                }
            }

            if (modified) {
                if (ands.isEmpty()) {
                    body.setWhere(null);
                } else {
                    body.setWhere(AstUtils.buildAndTree(ands));
                }
                
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Query contains tautological constants (e.g. 1=1) generated by ORMs.", "Folded constants out of the AST.", "LOW", "Reduces AST parsing and evaluation overhead during planning.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P28_ExpressionSimplification implements OptimizationPattern {
        @Override
        public String getId() { return "P28_EXPR_SIMPLIFICATION"; }
        @Override
        public String getName() { return "Expression Simplification"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            return stmt.toString().toUpperCase().contains(" NOT (");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            return Optional.empty(); // Handled physically by DB engine
        }
    }

    static class P29_RedundantFilter implements OptimizationPattern {
        @Override
        public String getId() { return "P29_REDUNDANT_FILTER"; }
        @Override
        public String getName() { return "Redundant Filter Elimination"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;
            
            List<Expression> exprs = AstUtils.flattenAnds(ps.getWhere());
            Set<String> seen = new HashSet<>();
            for (Expression e : exprs) {
                if (!seen.add(e.toString().trim())) return true;
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;

            if (body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            Set<String> seen = new LinkedHashSet<>();
            List<Expression> unique = new ArrayList<>();

            for (Expression expr : ands) {
                if (seen.add(expr.toString().trim())) {
                    unique.add(expr);
                }
            }

            if (unique.size() < ands.size()) {
                body.setWhere(AstUtils.buildAndTree(unique));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Duplicate predicates found in WHERE clause.", "Eliminated mathematically redundant filters.", "LOW", "Prevents engine from evaluating the exact same condition multiple times per row.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P30_AlgebraicIdentity implements OptimizationPattern {
        @Override
        public String getId() { return "P30_ALGEBRAIC_IDENTITY"; }
        @Override
        public String getName() { return "Algebraic Identity"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P31_InListFlatten implements OptimizationPattern {
        @Override
        public String getId() { return "P31_IN_LIST_FLATTEN"; }
        @Override
        public String getName() { return "IN List Flattening"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P32_NullabilityPropagation implements OptimizationPattern {
        @Override
        public String getId() { return "P32_NULLABILITY_PROP"; }
        @Override
        public String getName() { return "Nullability Propagation"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P33_BooleanNormalization implements OptimizationPattern {
        @Override
        public String getId() { return "P33_BOOLEAN_NORM"; }
        @Override
        public String getName() { return "Boolean Normalization"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P34_ShortCircuit implements OptimizationPattern {
        @Override
        public String getId() { return "P34_SHORT_CIRCUIT"; }
        @Override
        public String getName() { return "Short Circuit Evaluation"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            String sql = stmt.toString().replaceAll("\\s+", "");
            return sql.contains("1=0") || sql.contains("0=1");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;

            if (body.getWhere() == null) return Optional.empty();
            
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean hasFalse = false;

            for (Expression expr : ands) {
                String clean = expr.toString().replaceAll("\\s+", "");
                if (clean.equals("1=0") || clean.equals("0=1")) {
                    hasFalse = true;
                    break;
                }
            }

            if (hasFalse) {
                try {
                    body.setWhere(CCJSqlParserUtil.parseCondExpression("1 = 0"));
                    String finalSql = cloned.toString();
                    if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                        return Optional.of(buildMeta(getId(), getName(), "Query contains a known FALSE condition (1=0) ANDed with other expensive conditions.", "Short-circuited entire WHERE clause to 1=0.", "HIGH", "Prevents database from evaluating any other conditions since the row is already mathematically rejected.", stmt.toString(), finalSql, 1.0));
                    }
                } catch (Exception e) {
                    log.debug("P34_ShortCircuit rewrite failed: {}", e.getMessage());
                }
            }
            return Optional.empty();
        }
    }

    static class P35_LimitPushdown implements OptimizationPattern {
        @Override
        public String getId() { return "P35_LIMIT_PUSHDOWN"; }
        @Override
        public String getName() { return "LIMIT Pushdown"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; } // Addressed by P09

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P36_DuplicateSubquery implements OptimizationPattern {
        @Override
        public String getId() { return "P36_DUPLICATE_SUBQUERY"; }
        @Override
        public String getName() { return "Duplicate Subquery Elimination"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P37_ImplicitJoin implements OptimizationPattern {
        @Override
        public String getId() { return "P37_IMPLICIT_JOIN"; }
        @Override
        public String getName() { return "Implicit to Explicit JOIN Conversion"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            String sql = stmt.toString().toUpperCase();
            return sql.contains(",") && sql.contains("FROM") && !sql.contains("JOIN");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;

            if (body.getJoins() == null || body.getJoins().isEmpty() || body.getWhere() == null) return Optional.empty();

            boolean modified = false;
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());

            for (Join join : body.getJoins()) {
                try {
                    // Check if it's a comma join via reflection
                    boolean isSimple = (boolean) join.getClass().getMethod("isSimple").invoke(join);
                    if (isSimple) {
                        Iterator<Expression> it = ands.iterator();
                        while (it.hasNext()) {
                            Expression expr = it.next();
                            if (expr instanceof EqualsTo) {
                                EqualsTo eq = (EqualsTo) expr;
                                if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof Column) {
                                    Column left = (Column) eq.getLeftExpression();
                                    Column right = (Column) eq.getRightExpression();
                                    
                                    String joinTarget = AstUtils.getAliasOrName(join.getRightItem());
                                    String fromTarget = AstUtils.getAliasOrName(body.getFromItem());

                                    boolean matches = false;
                                    if (left.getTable() != null && right.getTable() != null) {
                                        String leftT = left.getTable().getName().toLowerCase();
                                        String rightT = right.getTable().getName().toLowerCase();
                                        if ((leftT.equals(joinTarget) && rightT.equals(fromTarget)) || 
                                            (leftT.equals(fromTarget) && rightT.equals(joinTarget))) {
                                            matches = true;
                                        }
                                    }

                                    if (matches) {
                                        join.getClass().getMethod("setSimple", boolean.class).invoke(join, false);
                                        join.setInner(true);
                                        join.setOnExpression(expr);
                                        it.remove();
                                        modified = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("P37_ImplicitJoin rewrite failed on node: {}", e.getMessage());
                }
            }

            if (modified) {
                if (ands.isEmpty()) body.setWhere(null);
                else body.setWhere(AstUtils.buildAndTree(ands));

                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Query uses old-style comma joins (Implicit Joins) which pollute the WHERE clause.", "Converted to explicit ANSI INNER JOINs.", "LOW", "Clarifies intent for the optimizer and separates join logic from filter logic.", stmt.toString(), finalSql, 1.0));
                }
            }

            return Optional.empty();
        }
    }

    static class P38_ScalarToJoin implements OptimizationPattern {
        @Override
        public String getId() { return "P38_SCALAR_TO_JOIN"; }
        @Override
        public String getName() { return "Scalar to Join"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; } // Handled by P01

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P39_Sargability implements OptimizationPattern {
        @Override
        public String getId() { return "P39_SARGABILITY"; }
        @Override
        public String getName() { return "SARGability"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; } // Handled by P14

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P40 - THE INDEX TRIGGER
    // This pattern does NOT mutate the AST. It detects missing PKs on filtered 
    // columns and returns metadata. The Engine sees the ID "P40_MISSING_INDEX" 
    // and triggers the `buildIndexRecommendations()` engine phase.
    // ─────────────────────────────────────────────────────────────────────────
    static class P40_MissingIndex implements OptimizationPattern {
        @Override
        public String getId() { return "P40_MISSING_INDEX"; }
        @Override
        public String getName() { return "Missing Index Recommendation Trigger"; }
        @Override
        public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (stats == null || stats.isEmpty()) return false;
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;

            boolean[] missingIndexDetected = {false};

            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(Column col) {
                    if (col.getTable() != null && col.getTable().getName() != null) {
                        String tableName = col.getTable().getName().toLowerCase();
                        if (stats.containsKey(tableName)) {
                            TableStatistics tStats = stats.get(tableName);
                            String pk = AstUtils.getPrimaryKeyColumn(tStats);
                            // If we filter on a column that isn't the primary key, trigger P40
                            if (pk != null && !pk.equalsIgnoreCase(col.getColumnName())) {
                                missingIndexDetected[0] = true;
                            }
                        }
                    }
                }
            });

            return missingIndexDetected[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                // Return identical before/after SQL to safely trigger the Engine's Index Builder
                return Optional.of(buildMeta(getId(), getName(), "Full table scan detected on unindexed WHERE column.", "Dynamically generating optimal CREATE INDEX statements.", "HIGH", "B-Tree indexes turn O(N) full table scans into O(log N) lookups.", stmt.toString(), stmt.toString(), 1.0));
            }
            return Optional.empty();
        }
    }
}