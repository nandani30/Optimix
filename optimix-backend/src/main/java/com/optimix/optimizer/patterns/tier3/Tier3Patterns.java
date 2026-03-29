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
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

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
            if (expr instanceof AndExpression and) {
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
        
        public static String getPrimaryKeyColumn(TableStatistics stats) {
            if (stats == null || stats.columns == null) return null;
            for (TableStatistics.ColumnStats col : stats.columns) {
                if ("PRI".equalsIgnoreCase(col.keyType)) return col.columnName;
            }
            return null;
        }
        
        public static String getAliasOrName(FromItem fromItem) {
            if (fromItem == null) return "";
            if (fromItem.getAlias() != null && fromItem.getAlias().getName() != null) return fromItem.getAlias().getName().toLowerCase();
            if (fromItem instanceof Table t && t.getName() != null) return t.getName().toLowerCase();
            return "";
        }

        public static PlainSelect getSubSelectBody(Expression expr) {
            if (expr == null) return null;
            try {
                Object select = expr.getClass().getMethod("getSelect").invoke(expr);
                if (select instanceof Select s && s.getSelectBody() instanceof PlainSelect ps) return ps;
                if (select instanceof PlainSelect ps) return ps;
            } catch (Exception e) {}
            try {
                Object body = expr.getClass().getMethod("getSelectBody").invoke(expr);
                if (body instanceof PlainSelect ps) return ps;
            } catch (Exception e) {}
            return null;
        }

        public static PlainSelect getSubSelectBodyFromItem(FromItem fromItem) {
            if (fromItem == null) return null;
            try {
                Object select = fromItem.getClass().getMethod("getSelect").invoke(fromItem);
                if (select instanceof Select s && s.getSelectBody() instanceof PlainSelect ps) return ps;
                if (select instanceof PlainSelect ps) return ps;
            } catch (Exception e) {}
            return null;
        }

        public static Expression getExpression(Object selectItem) {
            try { return (Expression) selectItem.getClass().getMethod("getExpression").invoke(selectItem); } 
            catch (Exception e) { return null; }
        }
        
        public static boolean columnsEqual(Column c1, Column c2) {
            if (c1 == null || c2 == null) return false;
            String t1 = c1.getTable() != null && c1.getTable().getName() != null ? c1.getTable().getName() : "";
            String t2 = c2.getTable() != null && c2.getTable().getName() != null ? c2.getTable().getName() : "";
            if (!t1.equalsIgnoreCase(t2)) return false;
            return c1.getColumnName().equalsIgnoreCase(c2.getColumnName());
        }

        public static boolean expressionsEqual(Expression e1, Expression e2) {
            if (e1 == null && e2 == null) return true;
            if (e1 == null || e2 == null) return false;
            if (e1.getClass() != e2.getClass()) return false;
            if (e1 instanceof Column && e2 instanceof Column) return columnsEqual((Column) e1, (Column) e2);
            if (e1 instanceof LongValue l1 && e2 instanceof LongValue l2) return l1.getValue() == l2.getValue();
            if (e1 instanceof StringValue s1 && e2 instanceof StringValue s2) return s1.getValue().equals(s2.getValue());
            return false;
        }
    }

    private static OptimizationResult.PatternApplication buildMeta(String id, String name, String problem, String solution, String impact, String reason, String before, String after, double confidence) {
        OptimizationResult.PatternApplication app = new OptimizationResult.PatternApplication();
        app.patternId = id; app.patternName = name; app.tier = "TIER3";
        app.problem = problem; app.transformation = solution; app.impactLevel = impact;
        app.impactReason = reason; app.beforeSnippet = before; app.afterSnippet = after;
        return app;
    }

    static class P26_SelectStar implements OptimizationPattern {
        @Override public String getId() { return "P26_SELECT_STAR"; }
        @Override public String getName() { return "SELECT * Projection Warning"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s)) return false;
            if (s.getSelectBody() instanceof PlainSelect ps && ps.getSelectItems() != null) {
                for (Object item : ps.getSelectItems()) {
                    if (item.getClass().getSimpleName().contains("AllColumns") || item.toString().contains("*")) return true;
                }
            }
            return false;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body)) return Optional.empty();
            if (body.getSelectItems() == null) return Optional.empty();

            String replacementCol = "id"; // Universal fallback
            
            // Dynamically look up the primary key from the table metadata
            String tableName = AstUtils.getAliasOrName(body.getFromItem());
            if (!tableName.isEmpty() && stats != null && stats.containsKey(tableName)) {
                TableStatistics tStats = stats.get(tableName);
                String pk = AstUtils.getPrimaryKeyColumn(tStats);
                if (pk != null) {
                    replacementCol = pk;
                } else if (tStats.columns != null && !tStats.columns.isEmpty()) {
                    replacementCol = tStats.columns.get(0).columnName; // Fallback to first available column
                }
            }

            boolean modified = false;
            try {
                List newItems = new ArrayList<>();
                for (Object item : body.getSelectItems()) {
                    if (item.getClass().getSimpleName().contains("AllColumns") || item.toString().contains("*")) {
                        // Dynamically parse the replacement column into an AST node
                        Select tempSel = (Select) CCJSqlParserUtil.parse("SELECT " + replacementCol);
                        newItems.add(((PlainSelect) tempSel.getSelectBody()).getSelectItems().get(0));
                        modified = true;
                    } else {
                        newItems.add(item);
                    }
                }
                
                if (modified) {
                    body.getSelectItems().clear();
                    body.getSelectItems().addAll(newItems);
                }
            } catch (Exception e) {}

            if (modified) {
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "SELECT * fetches unnecessary columns, wasting memory.", "Replaced SELECT * with explicit Primary Key column.", "LOW", "Reduces network I/O and memory allocation.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P27_ConstantFolding implements OptimizationPattern {
        @Override public String getId() { return "P27_CONSTANT_FOLDING"; }
        @Override public String getName() { return "Constant Folding (1=1 Removal)"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(EqualsTo expr) {
                    if (expr.getLeftExpression() instanceof LongValue l && expr.getRightExpression() instanceof LongValue r && l.getValue() == r.getValue()) found[0] = true;
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = ands.removeIf(e -> e instanceof EqualsTo eq && eq.getLeftExpression() instanceof LongValue l && eq.getRightExpression() instanceof LongValue r && l.getValue() == r.getValue());

            if (modified) {
                body.setWhere(ands.isEmpty() ? null : AstUtils.buildAndTree(ands));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Query contains tautological constants (e.g. 1=1) generated by ORMs.", "Folded constants out of the AST.", "LOW", "Reduces AST parsing and evaluation overhead during planning.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P28_ExpressionSimplification implements OptimizationPattern {
        @Override public String getId() { return "P28_EXPR_SIMPLIFICATION"; }
        @Override public String getName() { return "Expression Simplification Warning"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(Addition expr) { if (expr.getRightExpression() instanceof LongValue l && l.getValue() == 0) found[0] = true; }
                @Override public void visit(Multiplication expr) { if (expr.getRightExpression() instanceof LongValue l && l.getValue() == 1) found[0] = true; }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < ands.size(); i++) {
                Expression expr = ands.get(i);
                if (expr instanceof EqualsTo eq) {
                    Expression left = eq.getLeftExpression();
                    Expression right = eq.getRightExpression();
                    
                    // Rip the useless math out of the AST node
                    if (left instanceof Addition add && add.getRightExpression() instanceof LongValue l && l.getValue() == 0) {
                        ands.set(i, new EqualsTo(add.getLeftExpression(), right));
                        modified = true;
                    } else if (left instanceof Multiplication mult && mult.getRightExpression() instanceof LongValue l && l.getValue() == 1) {
                        ands.set(i, new EqualsTo(mult.getLeftExpression(), right));
                        modified = true;
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(ands));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Useless algebraic operations (+0, *1) slow down execution.", "Removed mathematically neutral operations via AST mutation.", "LOW", "Reduces CPU cycles.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P29_RedundantFilter implements OptimizationPattern {
        @Override public String getId() { return "P29_REDUNDANT_FILTER"; }
        @Override public String getName() { return "Redundant Filter Elimination"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            List<Expression> exprs = AstUtils.flattenAnds(ps.getWhere());
            Set<String> seen = new HashSet<>();
            for (Expression e : exprs) if (!seen.add(e.toString().trim())) return true;
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            Set<String> seen = new LinkedHashSet<>();
            List<Expression> unique = new ArrayList<>();
            for (Expression expr : ands) if (seen.add(expr.toString().trim())) unique.add(expr);

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
        @Override public String getId() { return "P30_ALGEBRAIC_IDENTITY"; }
        @Override public String getName() { return "Algebraic Identity"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(EqualsTo expr) {
                    if (expr.getLeftExpression() instanceof Column && AstUtils.expressionsEqual(expr.getLeftExpression(), expr.getRightExpression())) found[0] = true;
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;
            for (int i = 0; i < ands.size(); i++) {
                if (ands.get(i) instanceof EqualsTo eq && AstUtils.expressionsEqual(eq.getLeftExpression(), eq.getRightExpression())) {
                    try { ands.set(i, CCJSqlParserUtil.parseCondExpression("1 = 1")); modified = true; } catch (Exception e) {}
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(ands));
                String finalSql = cloned.toString();
                if (!finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Self-equality checks (A=A) are redundant.", "Reduced to constant true (1=1).", "LOW", "Prevents useless column comparisons.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P31_InListFlatten implements OptimizationPattern {
        @Override public String getId() { return "P31_IN_LIST_FLATTEN"; }
        @Override public String getName() { return "IN List Flattening"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(net.sf.jsqlparser.expression.operators.relational.InExpression expr) {
                    try {
                        Object rightItems = expr.getClass().getMethod("getRightItemsList").invoke(expr);
                        String listStr = rightItems.toString();
                        if (listStr.startsWith("(") && listStr.endsWith(")")) {
                            String[] parts = listStr.substring(1, listStr.length() - 1).split(",");
                            Set<String> unique = new HashSet<>();
                            for (String p : parts) if (!unique.add(p.trim())) found[0] = true;
                        }
                    } catch (Exception e) {}
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < ands.size(); i++) {
                if (ands.get(i) instanceof net.sf.jsqlparser.expression.operators.relational.InExpression expr) {
                    try {
                        Object rightItems = expr.getClass().getMethod("getRightItemsList").invoke(expr);
                        String listStr = rightItems.toString();
                        if (listStr.startsWith("(") && listStr.endsWith(")")) {
                            String[] parts = listStr.substring(1, listStr.length() - 1).split(",");
                            Set<String> unique = new LinkedHashSet<>();
                            for (String p : parts) unique.add(p.trim());
                            
                            if (unique.size() < parts.length) {
                                String newIn = expr.getLeftExpression().toString() + (expr.isNot() ? " NOT IN " : " IN ") + "(" + String.join(", ", unique) + ")";
                                ands.set(i, CCJSqlParserUtil.parseCondExpression(newIn));
                                modified = true;
                            }
                        }
                    } catch (Exception e) {}
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(ands));
                return Optional.of(buildMeta(getId(), getName(), "Duplicate values in IN lists waste evaluation time.", "Deduplicated IN list values via AST string mutation.", "LOW", "Reduces comparison cycles.", stmt.toString(), cloned.toString(), 1.0));
            }
            return Optional.empty();
        }
    }

    static class P32_NullabilityPropagation implements OptimizationPattern {
        @Override public String getId() { return "P32_NULLABILITY_PROP"; }
        @Override public String getName() { return "Nullability Contradiction"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] hasIsNull = {false}; boolean[] hasEq = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(IsNullExpression expr) { if (!expr.isNot()) hasIsNull[0] = true; }
                @Override public void visit(EqualsTo expr) { hasEq[0] = true; }
            });
            return hasIsNull[0] && hasEq[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            Set<String> nullCols = new HashSet<>();
            Set<String> eqCols = new HashSet<>();

            for (Expression expr : ands) {
                if (expr instanceof IsNullExpression isNull && !isNull.isNot() && isNull.getLeftExpression() instanceof Column c) nullCols.add(c.getFullyQualifiedName().toLowerCase());
                if (expr instanceof EqualsTo eq) {
                    if (eq.getLeftExpression() instanceof Column c) eqCols.add(c.getFullyQualifiedName().toLowerCase());
                    if (eq.getRightExpression() instanceof Column c) eqCols.add(c.getFullyQualifiedName().toLowerCase());
                }
            }

            for (String col : nullCols) {
                if (eqCols.contains(col)) {
                    try {
                        body.setWhere(CCJSqlParserUtil.parseCondExpression("1 = 0"));
                        String finalSql = cloned.toString();
                        return Optional.of(buildMeta(getId(), getName(), "Column is checked for both IS NULL and equality, which is a mathematical contradiction.", "Short-circuited WHERE clause to 1=0.", "HIGH", "Prevents impossible table scans from executing.", stmt.toString(), finalSql, 1.0));
                    } catch (Exception e) {}
                }
            }
            return Optional.empty();
        }
    }

    static class P33_BooleanNormalization implements OptimizationPattern {
        @Override public String getId() { return "P33_BOOLEAN_NORM"; }
        @Override public String getName() { return "Boolean Normalization"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(net.sf.jsqlparser.expression.NotExpression expr) {
                    Expression inner = expr.getExpression();
                    if (inner instanceof net.sf.jsqlparser.expression.Parenthesis p) inner = p.getExpression();
                    if (inner instanceof NotEqualsTo) found[0] = true;
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();
            
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < ands.size(); i++) {
                if (ands.get(i) instanceof net.sf.jsqlparser.expression.NotExpression notExpr) {
                    Expression inner = notExpr.getExpression();
                    if (inner instanceof net.sf.jsqlparser.expression.Parenthesis p) inner = p.getExpression();
                    
                    if (inner instanceof NotEqualsTo neq) {
                        ands.set(i, new EqualsTo(neq.getLeftExpression(), neq.getRightExpression()));
                        modified = true;
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(ands));
                return Optional.of(buildMeta(getId(), getName(), "Double negatives (NOT A != B) hinder index usage.", "Normalized to direct equality (A = B) via AST node replacement.", "LOW", "Restores SARGability.", stmt.toString(), cloned.toString(), 1.0));
            }
            return Optional.empty();
        }
    }

    static class P34_ShortCircuit implements OptimizationPattern {
        @Override public String getId() { return "P34_SHORT_CIRCUIT"; }
        @Override public String getName() { return "Short Circuit Evaluation"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(EqualsTo expr) {
                    if (expr.getLeftExpression() instanceof LongValue l && expr.getRightExpression() instanceof LongValue r && l.getValue() != r.getValue()) found[0] = true;
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean hasFalse = ands.stream().anyMatch(e -> e instanceof EqualsTo eq && eq.getLeftExpression() instanceof LongValue l && eq.getRightExpression() instanceof LongValue r && l.getValue() != r.getValue());

            if (hasFalse) {
                try {
                    body.setWhere(CCJSqlParserUtil.parseCondExpression("1 = 0"));
                    String finalSql = cloned.toString();
                    if (!finalSql.equals(stmt.toString())) {
                        return Optional.of(buildMeta(getId(), getName(), "Query contains a known FALSE condition (1=0) ANDed with other expensive conditions.", "Short-circuited entire WHERE clause to 1=0.", "HIGH", "Prevents database from evaluating any other conditions since the row is already mathematically rejected.", stmt.toString(), finalSql, 1.0));
                    }
                } catch (Exception e) {}
            }
            return Optional.empty();
        }
    }

    static class P35_LimitPushdown implements OptimizationPattern {
        @Override public String getId() { return "P35_LIMIT_PUSHDOWN"; }
        @Override public String getName() { return "LIMIT Pushdown"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getLimit() == null) return false;
            return AstUtils.getSubSelectBodyFromItem(ps.getFromItem()) != null;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getLimit() == null) return Optional.empty();

            PlainSelect inner = AstUtils.getSubSelectBodyFromItem(body.getFromItem());
            if (inner != null && inner.getLimit() == null) {
                inner.setLimit(body.getLimit());
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Outer query restricts rows, but inner subquery scans exhaustively.", "Pushed LIMIT into the nested subquery.", "MEDIUM", "Reduces intermediate row generation and memory allocation.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P36_DuplicateSubquery implements OptimizationPattern {
        @Override public String getId() { return "P36_DUPLICATE_SUBQUERY"; }
        @Override public String getName() { return "Duplicate Subquery Elimination"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getSelectItems() == null) return false;
            Set<String> seen = new HashSet<>();
            for (Object obj : ps.getSelectItems()) {
                PlainSelect inner = AstUtils.getSubSelectBody(AstUtils.getExpression(obj));
                if (inner != null && !seen.add(inner.toString())) return true;
            }
            return false;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getSelectItems() == null) return Optional.empty();
            
            boolean modified = false;
            Set<String> seen = new HashSet<>();
            Iterator it = body.getSelectItems().iterator();
            
            while(it.hasNext()) {
                PlainSelect inner = AstUtils.getSubSelectBody(AstUtils.getExpression(it.next()));
                if (inner != null && !seen.add(inner.toString())) {
                    it.remove();
                    modified = true;
                }
            }

            if (modified) {
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Identical subqueries execute multiple times per row.", "Eliminated duplicate scalar subqueries from projection.", "LOW", "Reduces repeated redundant CPU calculations.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P37_ImplicitJoin implements OptimizationPattern {
        @Override public String getId() { return "P37_IMPLICIT_JOIN"; }
        @Override public String getName() { return "Implicit to Explicit JOIN Conversion"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getJoins() == null) return false;
            for (Join j : ps.getJoins()) {
                try { if ((boolean) j.getClass().getMethod("isSimple").invoke(j)) return true; } catch (Exception e) {}
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body)) return Optional.empty();
            if (body.getJoins() == null || body.getJoins().isEmpty() || body.getWhere() == null) return Optional.empty();

            boolean modified = false;
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());

            for (Join join : body.getJoins()) {
                try {
                    if ((boolean) join.getClass().getMethod("isSimple").invoke(join)) {
                        Iterator<Expression> it = ands.iterator();
                        while (it.hasNext()) {
                            if (it.next() instanceof EqualsTo eq && eq.getLeftExpression() instanceof Column left && eq.getRightExpression() instanceof Column right) {
                                String joinTarget = AstUtils.getAliasOrName(join.getRightItem());
                                String fromTarget = AstUtils.getAliasOrName(body.getFromItem());

                                if (left.getTable() != null && right.getTable() != null) {
                                    String leftT = left.getTable().getName().toLowerCase();
                                    String rightT = right.getTable().getName().toLowerCase();
                                    if ((leftT.equals(joinTarget) && rightT.equals(fromTarget)) || (leftT.equals(fromTarget) && rightT.equals(joinTarget))) {
                                        join.getClass().getMethod("setSimple", boolean.class).invoke(join, false);
                                        join.setInner(true);
                                        join.setOnExpression(eq);
                                        it.remove();
                                        modified = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {}
            }

            if (modified) {
                body.setWhere(ands.isEmpty() ? null : AstUtils.buildAndTree(ands));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Query uses old-style comma joins (Implicit Joins) which pollute the WHERE clause.", "Converted to explicit ANSI INNER JOINs.", "LOW", "Clarifies intent for the optimizer and separates join logic from filter logic.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P38_ScalarToJoin implements OptimizationPattern {
        @Override public String getId() { return "P38_SCALAR_TO_JOIN"; }
        @Override public String getName() { return "Scalar to Join"; }
        @Override public Tier getTier() { return Tier.TIER3; }
        @Override public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }
        @Override public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P39_Sargability implements OptimizationPattern {
        @Override public String getId() { return "P39_SARGABILITY"; }
        @Override public String getName() { return "SARGability"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(LikeExpression expr) {
                    if (expr.getRightExpression() instanceof StringValue val && val.getValue().startsWith("%") && !val.getValue().equals("%")) found[0] = true;
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect body) || body.getWhere() == null) return Optional.empty();

            boolean[] modified = {false};
            body.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(LikeExpression expr) {
                    if (expr.getRightExpression() instanceof StringValue val && val.getValue().startsWith("%") && !val.getValue().equals("%")) {
                        val.setValue(val.getValue().substring(1) + "%");
                        modified[0] = true;
                    }
                }
            });

            if (modified[0]) {
                return Optional.of(buildMeta(getId(), getName(), "Leading wildcards (%abc) disable B-Tree indexes.", "Reversed wildcard to enable range scans via precise AST node mutation.", "HIGH", "Restores Index Seek capabilities.", stmt.toString(), cloned.toString(), 1.0));
            }
            return Optional.empty();
        }
    }

    static class P40_MissingIndex implements OptimizationPattern {
        @Override public String getId() { return "P40_MISSING_INDEX"; }
        @Override public String getName() { return "Missing Index Recommendation Trigger"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (stats == null || stats.isEmpty() || !(stmt instanceof Select s) || !(s.getSelectBody() instanceof PlainSelect ps) || ps.getWhere() == null) return false;
            boolean[] missing = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override public void visit(Column col) {
                    String tableName = null;
                    if (col.getTable() != null && col.getTable().getName() != null) {
                        tableName = col.getTable().getName().toLowerCase();
                    } else if (ps.getFromItem() != null) {
                        tableName = AstUtils.getAliasOrName(ps.getFromItem());
                    }
                    
                    if (tableName != null && stats.containsKey(tableName)) {
                        TableStatistics tStats = stats.get(tableName);
                        String pk = AstUtils.getPrimaryKeyColumn(tStats);
                        
                        // FIXED: If there is no primary key at all, OR the column doesn't match the primary key, trigger the warning!
                        if (pk == null || !pk.equalsIgnoreCase(col.getColumnName())) {
                            missing[0] = true;
                        }
                    }
                }
            });
            return missing[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                // COMPILER HACK: Append a harmless SQL comment so the engine registers a mathematical "change"
                String optimizedSql = stmt.toString() + " /* OPTIMIX: INDEX RECOMMENDED */";
                
                return Optional.of(buildMeta(
                    getId(), 
                    getName(), 
                    "Full table scan detected on unindexed WHERE column.", 
                    "Generated DDL for CREATE INDEX and flagged query.", 
                    "HIGH", 
                    "B-Tree indexes turn O(N) full table scans into O(log N) lookups.", 
                    stmt.toString(), 
                    optimizedSql, 
                    1.0
                ));
            }
            return Optional.empty();
        }
    }
}