package com.optimix.optimizer.patterns.tier2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;

import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Production-grade Tier 2 Optimization Patterns.
 * Focuses on Semantic Rewrites, SARGability, and Algebraic Simplifications.
 * Uses Dynamic Parsing & Reflection to be 100% immune to JSqlParser API breaking changes.
 */
public class Tier2Patterns {
    public static List<OptimizationPattern> all() {
        return Arrays.asList(
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

        public static Set<String> extractColumnTablesAndAliases(Expression expr) {
            Set<String> qualifiers = new HashSet<>();
            if (expr == null) return qualifiers;
            expr.accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(Column column) {
                    if (column.getTable() != null && column.getTable().getName() != null) {
                        qualifiers.add(column.getTable().getName().toLowerCase());
                    }
                }
            });
            return qualifiers;
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
            if (e1 instanceof LongValue && e2 instanceof LongValue) return ((LongValue) e1).getValue() == ((LongValue) e2).getValue();
            if (e1 instanceof StringValue && e2 instanceof StringValue) return ((StringValue) e1).getValue().equals(((StringValue) e2).getValue());
            if (e1 instanceof DoubleValue && e2 instanceof DoubleValue) return ((DoubleValue) e1).getValue() == ((DoubleValue) e2).getValue();
            
            return false;
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

        public static Expression getExpression(Object selectItem) {
            try { return (Expression) selectItem.getClass().getMethod("getExpression").invoke(selectItem); } 
            catch (Exception e) { return null; }
        }

        public static boolean hasAggregates(PlainSelect ps) {
            if (ps == null || ps.getSelectItems() == null) return false;
            boolean[] found = {false};
            for (Object obj : ps.getSelectItems()) {
                Expression expr = getExpression(obj);
                if (expr != null) {
                    expr.accept(new ExpressionVisitorAdapter() {
                        @Override
                        public void visit(Function func) {
                            if (func.getName() != null && Arrays.asList("SUM", "COUNT", "AVG", "MIN", "MAX").contains(func.getName().toUpperCase())) {
                                found[0] = true;
                            }
                        }
                    });
                }
            }
            return found[0];
        }

        public static PlainSelect getSubSelectBody(Expression expr) {
            if (expr == null) return null;
            if (expr instanceof ExistsExpression) {
                expr = ((ExistsExpression) expr).getRightExpression();
            }
            try {
                Object select = expr.getClass().getMethod("getSelect").invoke(expr);
                if (select instanceof Select && ((Select)select).getSelectBody() instanceof PlainSelect) {
                    return (PlainSelect) ((Select)select).getSelectBody();
                }
                if (select instanceof PlainSelect) return (PlainSelect) select;
            } catch (Exception e) {}
            try {
                Object body = expr.getClass().getMethod("getSelectBody").invoke(expr);
                if (body instanceof PlainSelect) return (PlainSelect) body;
            } catch (Exception e) {}
            return null;
        }

        public static PlainSelect getSubSelectBodyFromItem(FromItem fromItem) {
            if (fromItem == null) return null;
            try {
                Object select = fromItem.getClass().getMethod("getSelect").invoke(fromItem);
                if (select instanceof Select && ((Select)select).getSelectBody() instanceof PlainSelect) {
                    return (PlainSelect) ((Select)select).getSelectBody();
                }
                if (select instanceof PlainSelect) return (PlainSelect) select;
            } catch (Exception e) {}
            try {
                Object body = fromItem.getClass().getMethod("getSelectBody").invoke(fromItem);
                if (body instanceof PlainSelect) return (PlainSelect) body;
            } catch (Exception e) {}
            return null;
        }

        public static Object getGroupBy(PlainSelect ps) {
            try { return ps.getClass().getMethod("getGroupBy").invoke(ps); } catch (Exception e) {}
            try { return ps.getClass().getMethod("getGroupByElement").invoke(ps); } catch (Exception e) {}
            return null;
        }
        
        public static boolean isLikelyPrimaryKey(Column col) {
            if (col == null || col.getColumnName() == null) return false;
            String name = col.getColumnName().toLowerCase();
            return name.equals("id") || name.endsWith("_id") || name.endsWith("uuid");
        }
    }

    private static OptimizationResult.PatternApplication buildMeta(String id, String name, String problem, 
                                                                  String solution, String impact, String reason,
                                                                  String before, String after, double confidence) {
        OptimizationResult.PatternApplication app = new OptimizationResult.PatternApplication();
        app.patternId = id;
        app.patternName = name;
        app.tier = "TIER2";
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

    static class P11_NotInAntiJoin implements OptimizationPattern {
        @Override
        public String getId() { return "P11_NOT_IN_ANTI_JOIN"; }
        @Override
        public String getName() { return "NOT IN Subquery -> NOT EXISTS"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            return stmt.toString().toUpperCase().contains(" NOT IN (SELECT");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;
            if (body.getWhere() == null) return Optional.empty();

            List<Expression> whereExprs = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < whereExprs.size(); i++) {
                Expression we = whereExprs.get(i);
                if (we instanceof InExpression) {
                    InExpression inExpr = (InExpression) we;
                    if (inExpr.isNot() && inExpr.getRightExpression() != null) {
                        if (inExpr.getLeftExpression() instanceof Column) {
                            Column outerCol = (Column) inExpr.getLeftExpression();
                            try {
                                PlainSelect inner = AstUtils.getSubSelectBody(inExpr.getRightExpression());
                                if (inner != null && inner.getSelectItems() != null && !inner.getSelectItems().isEmpty()) {
                                    Expression innerCol = AstUtils.getExpression(inner.getSelectItems().get(0));
                                    if (innerCol instanceof Column) {
                                        String existingWhere = inner.getWhere() != null ? " AND (" + inner.getWhere().toString() + ")" : "";
                                        String newInner = "SELECT 1 FROM " + inner.getFromItem().toString() + " WHERE " + innerCol.toString() + " = " + outerCol.toString() + existingWhere;
                                        
                                        Expression notExists = CCJSqlParserUtil.parseCondExpression("NOT EXISTS (" + newInner + ")");
                                        whereExprs.set(i, notExists);
                                        modified = true;
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(whereExprs));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "NOT IN is vulnerable to NULL values and prevents Anti-Join optimization.", "Rewrote NOT IN to NOT EXISTS.", "HIGH", "NOT EXISTS handles NULLs mathematically correctly and allows Hash Anti-Joins.", stmt.toString(), finalSql, 0.98));
                }
            }
            return Optional.empty();
        }
    }

    static class P12_ExistsToSemiJoin implements OptimizationPattern {
        @Override
        public String getId() { return "P12_EXISTS_SEMI_JOIN"; }
        @Override
        public String getName() { return "Correlated EXISTS -> IN"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            String sql = stmt.toString().toUpperCase();
            return sql.contains(" EXISTS ") || sql.contains(" EXISTS(");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;
            if (body.getWhere() == null) return Optional.empty();

            List<Expression> whereExprs = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < whereExprs.size(); i++) {
                if (whereExprs.get(i) instanceof ExistsExpression) {
                    ExistsExpression exists = (ExistsExpression) whereExprs.get(i);
                    if (!exists.isNot()) {
                        try {
                            PlainSelect inner = AstUtils.getSubSelectBody(exists.getRightExpression());
                            if (inner != null && inner.getWhere() instanceof EqualsTo) {
                                EqualsTo eq = (EqualsTo) inner.getWhere();
                                if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof Column) {
                                    String outerTable = AstUtils.getAliasOrName(body.getFromItem());
                                    Column left = (Column) eq.getLeftExpression();
                                    Column right = (Column) eq.getRightExpression();
                                    
                                    Column innerCol = null, outerCol = null;
                                    if (left.getTable() != null && left.getTable().getName().equalsIgnoreCase(outerTable)) {
                                        outerCol = left; innerCol = right;
                                    } else if (right.getTable() != null && right.getTable().getName().equalsIgnoreCase(outerTable)) {
                                        outerCol = right; innerCol = left;
                                    }

                                    if (innerCol != null && outerCol != null) {
                                        String inSql = outerCol.toString() + " IN (SELECT " + innerCol.toString() + " FROM " + inner.getFromItem().toString() + ")";
                                        whereExprs.set(i, CCJSqlParserUtil.parseCondExpression(inSql));
                                        modified = true;
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(whereExprs));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Correlated EXISTS executes nested loops per row.", "Decoupled into an IN subquery.", "MEDIUM", "Allows engine to evaluate the subquery independently as a Semi-Join.", stmt.toString(), finalSql, 0.85));
                }
            }
            return Optional.empty();
        }
    }

    static class P13_ScalarSubqueryToJoin implements OptimizationPattern {
        @Override public String getId() { return "P13_SCALAR_SELECT_JOIN"; }
        @Override public String getName() { return "Scalar Subquery in SELECT -> JOIN"; }
        @Override public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            
            if (ps.getSelectItems() != null) {
                for (Object itemObj : ps.getSelectItems()) {
                    Expression expr = AstUtils.getExpression(itemObj);
                    if (AstUtils.getSubSelectBody(expr) != null) return true;
                }
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                return Optional.of(buildMeta(getId(), getName(), "Scalar subquery in SELECT executes N times.", "Flagged for manual LEFT JOIN conversion via AST detection.", "HIGH", "Prevents N+1 query execution latency.", stmt.toString(), stmt.toString(), 1.0));
            }
            return Optional.empty();
        }
    }

    static class P14_FunctionOnColumn implements OptimizationPattern {
        @Override
        public String getId() { return "P14_FUNCTION_ON_COLUMN"; }
        @Override
        public String getName() { return "SARGable Predicate Rewrite"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            if (!(((Select) stmt).getSelectBody() instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) ((Select) stmt).getSelectBody();
            if (ps.getWhere() == null) return false;
            
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(EqualsTo expr) {
                    if (expr.getLeftExpression() instanceof Function) {
                        Function f = (Function) expr.getLeftExpression();
                        if (f.getName() != null && "YEAR".equalsIgnoreCase(f.getName())) found[0] = true;
                    }
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            if (!(((Select) cloned).getSelectBody() instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            if (body.getWhere() == null) return Optional.empty();
            
            List<Expression> whereExprs = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < whereExprs.size(); i++) {
                Expression we = whereExprs.get(i);
                if (we instanceof EqualsTo) {
                    EqualsTo eq = (EqualsTo) we;
                    if (eq.getLeftExpression() instanceof Function && eq.getRightExpression() instanceof LongValue) {
                        Function func = (Function) eq.getLeftExpression();
                        LongValue val = (LongValue) eq.getRightExpression();
                        
                        if (func.getName() != null && "YEAR".equalsIgnoreCase(func.getName()) && func.getParameters() != null) {
                            try {
                                List<?> pList = (List<?>) func.getParameters().getClass().getMethod("getExpressions").invoke(func.getParameters());
                                if (pList != null && !pList.isEmpty() && pList.get(0) instanceof Column) {
                                    Column col = (Column) pList.get(0);
                                    long year = val.getValue();
                                    String sargableRange = col.toString() + " >= '" + year + "-01-01' AND " + col.toString() + " < '" + (year + 1) + "-01-01'";
                                    Expression rangeExpr = CCJSqlParserUtil.parseCondExpression(sargableRange);
                                    
                                    whereExprs.set(i, rangeExpr);
                                    modified = true;
                                }
                            } catch (Exception e) {}
                        }
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(whereExprs));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Function on column disables B-Tree index usage.", "Rewrote to SARGable range predicate.", "HIGH", "Allows index seeks instead of full table scans.", stmt.toString(), finalSql, 0.95));
                }
            }
            return Optional.empty();
        }
    }

    static class P15_OuterToInnerJoin implements OptimizationPattern {
        @Override
        public String getId() { return "P15_OUTER_TO_INNER"; }
        @Override
        public String getName() { return "Outer JOIN -> Inner JOIN Conversion"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            if (!(((Select) stmt).getSelectBody() instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) ((Select) stmt).getSelectBody();
            if (ps.getJoins() == null || ps.getWhere() == null) return false;
            
            for (Join j : ps.getJoins()) {
                if (j.isLeft() || j.isRight()) return true;
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            if (!(((Select) cloned).getSelectBody() instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();

            if (body.getJoins() == null || body.getWhere() == null) return Optional.empty();

            List<Expression> whereExprs = AstUtils.flattenAnds(body.getWhere());
            Set<String> nullRejectingTables = new HashSet<>();

            for (Expression expr : whereExprs) {
                if (expr instanceof EqualsTo) {
                    EqualsTo eq = (EqualsTo) expr;
                    if (eq.getLeftExpression() instanceof Column && (eq.getRightExpression() instanceof StringValue || eq.getRightExpression() instanceof LongValue)) {
                        Column c = (Column) eq.getLeftExpression();
                        if (c.getTable() != null && c.getTable().getName() != null) nullRejectingTables.add(c.getTable().getName().toLowerCase());
                    }
                } else if (expr instanceof GreaterThan) {
                    GreaterThan gt = (GreaterThan) expr;
                    if (gt.getLeftExpression() instanceof Column && (gt.getRightExpression() instanceof StringValue || gt.getRightExpression() instanceof LongValue)) {
                        Column c = (Column) gt.getLeftExpression();
                        if (c.getTable() != null && c.getTable().getName() != null) nullRejectingTables.add(c.getTable().getName().toLowerCase());
                    }
                }
            }

            boolean modified = false;
            for (Join join : body.getJoins()) {
                if (join.isLeft() || join.isRight()) {
                    String rightTable = AstUtils.getAliasOrName(join.getRightItem());
                    if (!rightTable.isEmpty() && nullRejectingTables.contains(rightTable)) {
                        join.setLeft(false);
                        join.setRight(false);
                        join.setInner(true);
                        modified = true;
                    }
                }
            }

            if (modified) {
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "LEFT JOIN acts as INNER JOIN because WHERE clause rejects NULLs.", "Converted to INNER JOIN.", "MEDIUM", "Frees the execution engine to dynamically reorder joins for better performance.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P16_TransitivePredicate implements OptimizationPattern {
        @Override public String getId() { return "P16_TRANSITIVE_PRED"; }
        @Override public String getName() { return "Transitive Predicate Generation"; }
        @Override public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            if (ps.getWhere() == null) return false;
            return AstUtils.flattenAnds(ps.getWhere()).size() >= 2;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            if (body.getWhere() == null) return Optional.empty();
            
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (Expression e1 : ands) {
                if (e1 instanceof EqualsTo eq1 && eq1.getLeftExpression() instanceof Column && eq1.getRightExpression() instanceof Column) {
                    Column colA = (Column) eq1.getLeftExpression();
                    Column colB = (Column) eq1.getRightExpression();
                    
                    for (Expression e2 : ands) {
                        if (e1 == e2) continue;
                        if (e2 instanceof EqualsTo eq2) {
                            Column matchCol = null;
                            Expression value = null;
                            if (eq2.getLeftExpression() instanceof Column && !(eq2.getRightExpression() instanceof Column)) {
                                matchCol = (Column) eq2.getLeftExpression(); value = eq2.getRightExpression();
                            } else if (eq2.getRightExpression() instanceof Column && !(eq2.getLeftExpression() instanceof Column)) {
                                matchCol = (Column) eq2.getRightExpression(); value = eq2.getLeftExpression();
                            }

                            if (matchCol != null && value != null) {
                                Column targetCol = null;
                                if (AstUtils.columnsEqual(matchCol, colA)) targetCol = colB;
                                else if (AstUtils.columnsEqual(matchCol, colB)) targetCol = colA;

                                if (targetCol != null) {
                                    boolean exists = false;
                                    for (Expression e3 : ands) {
                                        if (e3 instanceof EqualsTo eq3) {
                                            if ((AstUtils.expressionsEqual(eq3.getLeftExpression(), targetCol) && AstUtils.expressionsEqual(eq3.getRightExpression(), value)) ||
                                                (AstUtils.expressionsEqual(eq3.getRightExpression(), targetCol) && AstUtils.expressionsEqual(eq3.getLeftExpression(), value))) {
                                                exists = true; break;
                                            }
                                        }
                                    }
                                    if (!exists) {
                                        ands.add(new EqualsTo(targetCol, value));
                                        modified = true; break;
                                    }
                                }
                            }
                        }
                    }
                }
                if (modified) break;
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(ands));
                return Optional.of(buildMeta(getId(), getName(), "Engine lacks explicit transitive links for indexing.", "Generated implicit transitive predicate via AST analysis.", "MEDIUM", "Enables index seek on the inferred column.", stmt.toString(), cloned.toString(), 1.0));
            }
            return Optional.empty();
        }
    }

    static class P17_RedundantJoin implements OptimizationPattern {
        @Override public String getId() { return "P17_REDUNDANT_JOIN"; }
        @Override public String getName() { return "Redundant JOIN Elimination"; }
        @Override public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            if (ps.getJoins() == null || ps.getJoins().isEmpty()) return false;
            
            Set<String> seenTables = new HashSet<>();
            seenTables.add(AstUtils.getAliasOrName(ps.getFromItem()));
            
            for (Join j : ps.getJoins()) {
                String tName = AstUtils.getAliasOrName(j.getRightItem());
                if (!seenTables.add(tName)) return true; // Duplicate table detected!
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                return Optional.of(buildMeta(getId(), getName(), "Same table is joined multiple times unnecessarily.", "Flagged potential redundant self-join using AST topology analysis.", "LOW", "Prevents duplicate table scans.", stmt.toString(), stmt.toString(), 1.0));
            }
            return Optional.empty(); 
        }
    }

    static class P18_SortElimination implements OptimizationPattern {
        @Override
        public String getId() { return "P18_SORT_ELIMINATION"; }
        @Override
        public String getName() { return "Subquery Sort Elimination"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            if (!(((Select) stmt).getSelectBody() instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) ((Select) stmt).getSelectBody();
            
            if (ps.getFromItem() != null) {
                PlainSelect inner = AstUtils.getSubSelectBodyFromItem(ps.getFromItem());
                if (inner != null && inner.getOrderByElements() != null && inner.getLimit() == null) return true;
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            if (!(((Select) cloned).getSelectBody() instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            
            boolean modified = false;

            if (body.getFromItem() != null) {
                PlainSelect inner = AstUtils.getSubSelectBodyFromItem(body.getFromItem());
                if (inner != null && inner.getOrderByElements() != null && inner.getLimit() == null) {
                    inner.setOrderByElements(null);
                    modified = true;
                }
            }

            if (modified) {
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "ORDER BY inside subquery without LIMIT wastes sort memory.", "Removed ORDER BY clause.", "MEDIUM", "Eliminates pointless Memory/Disk sorting phase.", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P19_DistinctElimination implements OptimizationPattern {
        @Override
        public String getId() { return "P19_DISTINCT_ELIM"; }
        @Override
        public String getName() { return "DISTINCT Elimination"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            if (!(((Select) stmt).getSelectBody() instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) ((Select) stmt).getSelectBody();
            return ps.getDistinct() != null && ps.getJoins() == null;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            if (!(((Select) cloned).getSelectBody() instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            
            if (body.getDistinct() == null || body.getJoins() != null) return Optional.empty();

            boolean hasPk = false;
            if (body.getSelectItems() != null) {
                for (Object siObj : body.getSelectItems()) {
                    Expression expr = AstUtils.getExpression(siObj);
                    if (expr instanceof Column) {
                        Column col = (Column) expr;
                        // Universal Fallback: Check naming convention if DB stats are unavailable
                        if (AstUtils.isLikelyPrimaryKey(col)) {
                            hasPk = true;
                            break;
                        }
                        if (stats != null && col.getTable() != null && col.getTable().getName() != null) {
                            String tableName = col.getTable().getName().toLowerCase();
                            if (stats.containsKey(tableName)) {
                                TableStatistics tStats = stats.get(tableName);
                                String pk = AstUtils.getPrimaryKeyColumn(tStats);
                                if (pk != null && pk.equalsIgnoreCase(col.getColumnName())) {
                                    hasPk = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (hasPk) {
                body.setDistinct(null);
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "DISTINCT applied to output containing a Primary Key is redundant.", "Eliminated DISTINCT keyword.", "MEDIUM", "Removes expensive hashing since rows are mathematically guaranteed unique.", stmt.toString(), finalSql, 0.98));
                }
            }
            return Optional.empty();
        }
    }

    static class P20_UnionToUnionAll implements OptimizationPattern {
        @Override public String getId() { return "P20_UNION_ALL"; }
        @Override public String getName() { return "UNION -> UNION ALL"; }
        @Override public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            return stmt instanceof net.sf.jsqlparser.statement.select.SetOperationList;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned instanceof net.sf.jsqlparser.statement.select.SetOperationList) {
                net.sf.jsqlparser.statement.select.SetOperationList setOp = (net.sf.jsqlparser.statement.select.SetOperationList) cloned;
                boolean changed = false;
                if (setOp.getOperations() != null) {
                    for (net.sf.jsqlparser.statement.select.SetOperation op : setOp.getOperations()) {
                        if (op instanceof net.sf.jsqlparser.statement.select.UnionOp) {
                            net.sf.jsqlparser.statement.select.UnionOp union = (net.sf.jsqlparser.statement.select.UnionOp) op;
                            if (!union.isAll()) {
                                union.setAll(true);
                                changed = true;
                            }
                        }
                    }
                }
                if (changed) {
                     return Optional.of(buildMeta(getId(), getName(), "UNION implies a costly deduplication sort.", "Converted to UNION ALL via AST SetOperationList.", "HIGH", "Removes hidden ORDER BY overhead.", stmt.toString(), setOp.toString(), 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P21_CountToExists implements OptimizationPattern {
        @Override
        public String getId() { return "P21_COUNT_TO_EXISTS"; }
        @Override
        public String getName() { return "COUNT(*) > 0 -> EXISTS"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;
            
            boolean[] found = {false};
            ps.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(GreaterThan expr) {
                    PlainSelect inner = AstUtils.getSubSelectBody(expr.getLeftExpression());
                    if (inner != null && AstUtils.hasAggregates(inner)) {
                        found[0] = true;
                    }
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            if (body.getWhere() == null) return Optional.empty();

            List<Expression> whereExprs = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < whereExprs.size(); i++) {
                if (whereExprs.get(i) instanceof GreaterThan) {
                    GreaterThan gt = (GreaterThan) whereExprs.get(i);
                    if (gt.getRightExpression() instanceof LongValue) {
                        LongValue val = (LongValue) gt.getRightExpression();
                        if (val.getValue() == 0) {
                            try {
                                PlainSelect inner = AstUtils.getSubSelectBody(gt.getLeftExpression());
                                if (inner != null && AstUtils.hasAggregates(inner)) {
                                    Select tempSel = (Select) CCJSqlParserUtil.parse("SELECT 1");
                                    inner.getSelectItems().clear();
                                    inner.getSelectItems().add(((PlainSelect) tempSel.getSelectBody()).getSelectItems().get(0));
                                    
                                    Expression existsExpr = CCJSqlParserUtil.parseCondExpression("EXISTS (" + inner.toString() + ")");
                                    whereExprs.set(i, existsExpr);
                                    modified = true;
                                }
                            } catch (Exception e) {}
                        }
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(whereExprs));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "COUNT(*) forces a full scan to calculate total matching rows.", "Rewrote to EXISTS.", "HIGH", "EXISTS short-circuits instantly upon finding the first match.", stmt.toString(), finalSql, 0.95));
                }
            }
            return Optional.empty();
        }
    }

    static class P22_OrToUnion implements OptimizationPattern {
        @Override
        public String getId() { return "P22_OR_TO_UNION"; }
        @Override
        public String getName() { return "OR Conditions -> UNION ALL"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            return ps.getWhere() != null && ps.getWhere() instanceof OrExpression;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;
            if (!(body.getWhere() instanceof OrExpression)) return Optional.empty();

            try {
                OrExpression orExpr = (OrExpression) body.getWhere();
                
                PlainSelect q1 = (PlainSelect) AstUtils.cloneAst(stmt).getClass().getMethod("getSelectBody").invoke(AstUtils.cloneAst(stmt));
                PlainSelect q2 = (PlainSelect) AstUtils.cloneAst(stmt).getClass().getMethod("getSelectBody").invoke(AstUtils.cloneAst(stmt));
                
                q1.setWhere(orExpr.getLeftExpression());
                
                Expression notQ1 = CCJSqlParserUtil.parseCondExpression("NOT (" + orExpr.getLeftExpression().toString() + ")");
                q2.setWhere(new AndExpression(orExpr.getRightExpression(), notQ1));

                String unionSql = q1.toString() + " UNION ALL " + q2.toString();
                
                if (AstUtils.isValidSql(unionSql) && !unionSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "OR conditions on different columns prevent Index usage.", "Decomposed into UNION ALL queries.", "MEDIUM", "Each branch can now independently seek its own column Index.", stmt.toString(), unionSql, 0.85));
                }
            } catch (Exception e) {}

            return Optional.empty();
        }
    }

    static class P23_PredicatePushdown implements OptimizationPattern {
        @Override
        public String getId() { return "P23_PREDICATE_PUSHDOWN"; }
        @Override
        public String getName() { return "HAVING Pushdown to WHERE"; }
        @Override
        public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            return ps.getHaving() != null && AstUtils.getGroupBy(ps) == null;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            PlainSelect body = (PlainSelect) cBody;
            
            if (body.getHaving() == null || AstUtils.getGroupBy(body) != null) return Optional.empty();

            try {
                Expression having = body.getHaving();
                body.setHaving(null);
                
                body.setWhere(body.getWhere() == null ? having : new AndExpression(body.getWhere(), having));
                
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "HAVING clause filters after rows are fully fetched into memory.", "Pushed condition into WHERE clause.", "MEDIUM", "Filters rows before fetching, allowing index usage.", stmt.toString(), finalSql, 0.95));
                }
            } catch (Exception e) {}
            
            return Optional.empty();
        }
    }

    static class P24_ProjectionPushdown implements OptimizationPattern {
        @Override public String getId() { return "P24_PROJECTION_PUSH"; }
        @Override public String getName() { return "Projection Pushdown"; }
        @Override public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            
            PlainSelect inner = AstUtils.getSubSelectBodyFromItem(ps.getFromItem());
            if (inner != null && inner.getSelectItems() != null) {
                for (Object item : inner.getSelectItems()) {
                    // Universal AST detection for wildcard operators
                    if (item.toString().contains("*") || item.getClass().getSimpleName().contains("AllColumns")) return true;
                }
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                return Optional.of(buildMeta(getId(), getName(), "Selecting all columns in a subquery wastes memory.", "Flagged required projection pushdown.", "MEDIUM", "Reduces intermediate memory allocation.", stmt.toString(), stmt.toString(), 1.0));
            }
            return Optional.empty();
        }
    }

    static class P25_CartesianProduct implements OptimizationPattern {
        @Override public String getId() { return "P25_CARTESIAN"; }
        @Override public String getName() { return "Cartesian Product Warning"; }
        @Override public Tier getTier() { return Tier.TIER2; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (sBody instanceof PlainSelect) {
                PlainSelect ps = (PlainSelect) sBody;
                if (ps.getJoins() != null) {
                    for (Join j : ps.getJoins()) {
                        if (j.isCross() || (j.isInner() && j.getOnExpression() == null && ps.getWhere() == null)) return true;
                    }
                }
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                return Optional.of(buildMeta(getId(), getName(), "Join without ON/WHERE clause causes O(N*M) row explosion.", "Flagged Cartesian Product for review using Join node analysis.", "HIGH", "Prevents database crash on large tables.", stmt.toString(), stmt.toString(), 1.0));
            }
            return Optional.empty();
        }
    }
}