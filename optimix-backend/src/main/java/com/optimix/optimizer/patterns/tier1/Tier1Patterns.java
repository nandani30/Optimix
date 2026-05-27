package com.optimix.optimizer.patterns.tier1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.OptimizationPattern;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;

public class Tier1Patterns {
    public static List<OptimizationPattern> all() {
        return Arrays.asList(
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

    static class AstUtils {
        public static Statement cloneAst(Statement original) {
            if (original == null) return null;
            try { return CCJSqlParserUtil.parse(original.toString()); } catch (Exception e) { return null; }
        }

        public static boolean isValidSql(String sql) {
            if (sql == null || sql.trim().isEmpty()) return false;
            try { CCJSqlParserUtil.parse(sql); return true; } catch (Exception e) { return false; }
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
            if (fromItem instanceof Table table) {
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
            if (e1 instanceof LongValue l1 && e2 instanceof LongValue l2) return l1.getValue() == l2.getValue();
            if (e1 instanceof StringValue s1 && e2 instanceof StringValue s2) return s1.getValue().equals(s2.getValue());
            if (e1 instanceof DoubleValue d1 && e2 instanceof DoubleValue d2) return d1.getValue() == d2.getValue();
            
            return false;
        }

        public static String getPrimaryKeyColumn(TableStatistics stats) {
            if (stats == null || stats.columns == null) return null;
            for (TableStatistics.ColumnStats col : stats.columns) {
                if ("PRI".equalsIgnoreCase(col.keyType)) return col.columnName;
            }
            return null;
        }
        
        public static Expression getExpression(Object selectItem) {
            try { return (Expression) selectItem.getClass().getMethod("getExpression").invoke(selectItem); } catch (Exception e) { return null; }
        }
        
        public static void setExpression(Object selectItem, Expression expr) {
            try { selectItem.getClass().getMethod("setExpression", Expression.class).invoke(selectItem, expr); } catch (Exception e) {}
        }
        
        public static Alias getAlias(Object selectItem) {
            try { return (Alias) selectItem.getClass().getMethod("getAlias").invoke(selectItem); } catch (Exception e) { return null; }
        }
        
        public static void setAlias(Object selectItem, Alias alias) {
            try { selectItem.getClass().getMethod("setAlias", Alias.class).invoke(selectItem, alias); } catch (Exception e) {}
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
            if (expr instanceof ExistsExpression exists) {
                expr = exists.getRightExpression();
            }
            try {
                Object select = expr.getClass().getMethod("getSelect").invoke(expr);
                if (select instanceof Select sel && sel.getSelectBody() instanceof PlainSelect ps) {
                    return ps;
                }
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
                if (select instanceof Select sel && sel.getSelectBody() instanceof PlainSelect ps) return ps;
                if (select instanceof PlainSelect ps) return ps;
            } catch (Exception e) {}
            try {
                Object body = fromItem.getClass().getMethod("getSelectBody").invoke(fromItem);
                if (body instanceof PlainSelect ps) return ps;
            } catch (Exception e) {}
            return null;
        }

        public static FromItem createSubSelectFromItem(PlainSelect subBody, String alias) throws Exception {
            String sql = "SELECT * FROM (" + subBody.toString() + ") AS " + alias;
            Select temp = (Select) CCJSqlParserUtil.parse(sql);
            return ((PlainSelect) temp.getSelectBody()).getFromItem();
        }
        
        public static boolean isLikelyPrimaryKey(Column col) {
            if (col == null || col.getColumnName() == null) return false;
            String name = col.getColumnName().toLowerCase();
            return name.equals("id") || name.endsWith("_id") || name.endsWith("uuid");
        }

        public static Object getGroupBy(PlainSelect ps) {
            try { return ps.getClass().getMethod("getGroupBy").invoke(ps); } catch (Exception e) {}
            try { return ps.getClass().getMethod("getGroupByElement").invoke(ps); } catch (Exception e) {}
            return null;
        }

        @SuppressWarnings("unchecked")
        public static List<Expression> getGroupByExpressions(PlainSelect ps) {
            Object gb = getGroupBy(ps);
            if (gb == null) return null;
            try {
                Object exprs = gb.getClass().getMethod("getGroupByExpressions").invoke(gb);
                if (exprs instanceof List) return (List<Expression>) exprs;
                else if (exprs != null) return (List<Expression>) exprs.getClass().getMethod("getExpressions").invoke(exprs);
            } catch (Exception e) {}
            return null;
        }

        public static void setGroupByByParsing(PlainSelect ps, String groupByClause) {
            try {
                String tempSql = "SELECT * FROM t GROUP BY " + groupByClause;
                Select tempSelect = (Select) CCJSqlParserUtil.parse(tempSql);
                PlainSelect tempPs = (PlainSelect) tempSelect.getSelectBody();
                Object gb = getGroupBy(tempPs);
                try { ps.getClass().getMethod("setGroupByElement", gb.getClass()).invoke(ps, gb); } 
                catch (Exception e) { ps.getClass().getMethod("setGroupBy", gb.getClass()).invoke(ps, gb); }
            } catch (Exception e) {}
        }
    }

    // Notice: Removed the unused 'confidence' variable to eliminate compiler warnings
    private static OptimizationResult.PatternApplication buildMeta(String id, String name, String problem, 
                                                                  String solution, String impact, String reason,
                                                                  String before, String after) {
        OptimizationResult.PatternApplication app = new OptimizationResult.PatternApplication();
        app.patternId = id; app.patternName = name; app.tier = "TIER1";
        app.problem = problem; app.transformation = solution; app.impactLevel = impact;
        app.impactReason = reason; app.beforeSnippet = before; app.afterSnippet = after; return app;
    }

    static class P01_CorrelatedSubquery implements OptimizationPattern {
        @Override public String getId() { return "P01_CORRELATED_SUBQUERY"; }
        @Override public String getName() { return "Correlated Subquery Decorrelation"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        private boolean isSafeToApply(PlainSelect innerSelect, List<Expression> innerWhere, String innerTableName) {
            long correlationCount = 0;
            for (Expression expr : innerWhere) {
                if (expr instanceof EqualsTo eq) {
                    if (eq.getLeftExpression() instanceof Column left && eq.getRightExpression() instanceof Column right) {
                        String leftTbl = left.getTable() != null ? left.getTable().getName() : "";
                        String rightTbl = right.getTable() != null ? right.getTable().getName() : "";
                        if ((leftTbl.equalsIgnoreCase(innerTableName) && !rightTbl.equalsIgnoreCase(innerTableName)) ||
                            (rightTbl.equalsIgnoreCase(innerTableName) && !leftTbl.equalsIgnoreCase(innerTableName))) correlationCount++;
                    }
                }
            }
            if (correlationCount != 1) return false;

            boolean hasOnlyCountStar = false;
            if (innerSelect.getSelectItems() != null) {
                for(Object siObj : innerSelect.getSelectItems()) {
                    Expression expr = AstUtils.getExpression(siObj);
                    if (expr instanceof Function func) {
                        if (func.getName() == null || !func.getName().equalsIgnoreCase("COUNT")) return false;
                        if (func.toString().contains("*")) hasOnlyCountStar = true;
                    }
                }
            }
            return hasOnlyCountStar;
        }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect outerSelect)) return false;
            
            if (outerSelect.getSelectItems() != null) {
                for (Object itemObj : outerSelect.getSelectItems()) {
                    Expression expr = AstUtils.getExpression(itemObj);
                    PlainSelect innerSelect = AstUtils.getSubSelectBody(expr);
                    if (innerSelect != null && innerSelect.getWhere() != null) {
                        if (AstUtils.hasAggregates(innerSelect)) {
                            String innerName = AstUtils.getAliasOrName(innerSelect.getFromItem());
                            Set<String> whereRefs = AstUtils.extractColumnTablesAndAliases(innerSelect.getWhere());
                            for (String ref : whereRefs) {
                                if (!ref.isEmpty() && !ref.equalsIgnoreCase(innerName)) return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect outerSelect)) return Optional.empty();

            if (outerSelect.getSelectItems() == null) return Optional.empty();

            for (int i = 0; i < outerSelect.getSelectItems().size(); i++) {
                Object itemObj = outerSelect.getSelectItems().get(i);
                Expression itemExpr = AstUtils.getExpression(itemObj);
                PlainSelect innerSelect = AstUtils.getSubSelectBody(itemExpr);
                
                if (innerSelect != null) {
                    List<Expression> innerWhere = AstUtils.flattenAnds(innerSelect.getWhere());
                    String innerTableName = AstUtils.getAliasOrName(innerSelect.getFromItem());

                    if (!isSafeToApply(innerSelect, innerWhere, innerTableName)) continue;

                    EqualsTo correlationEq = null; Column innerCol = null; Column outerCol = null;

                    for (Expression expr : innerWhere) {
                        if (expr instanceof EqualsTo eq) {
                            if (eq.getLeftExpression() instanceof Column left && eq.getRightExpression() instanceof Column right) {
                                String leftTbl = left.getTable() != null ? left.getTable().getName() : "";
                                String rightTbl = right.getTable() != null ? right.getTable().getName() : "";

                                if (leftTbl.equalsIgnoreCase(innerTableName) && !rightTbl.equalsIgnoreCase(innerTableName)) {
                                    correlationEq = eq; innerCol = left; outerCol = right; break;
                                } else if (rightTbl.equalsIgnoreCase(innerTableName) && !leftTbl.equalsIgnoreCase(innerTableName)) {
                                    correlationEq = eq; innerCol = right; outerCol = left; break;
                                }
                            }
                        }
                    }

                    Object aggItem = null;
                    if (innerSelect.getSelectItems() != null) {
                        for(Object siObj : innerSelect.getSelectItems()) {
                            Expression expr = AstUtils.getExpression(siObj);
                            if (expr instanceof Function) { aggItem = siObj; break; }
                        }
                    }

                    if (correlationEq != null && innerCol != null && outerCol != null && aggItem != null) {
                        try {
                            innerWhere.remove(correlationEq);
                            innerSelect.setWhere(AstUtils.buildAndTree(innerWhere));

                            boolean hasGroupCol = false;
                            for (Object siObj : innerSelect.getSelectItems()) {
                                Expression e = AstUtils.getExpression(siObj);
                                if (e instanceof Column && AstUtils.columnsEqual((Column) e, innerCol)) hasGroupCol = true;
                            }

                            if (!hasGroupCol) {
                                Select tempAgg = (Select) CCJSqlParserUtil.parse("SELECT " + innerCol.toString());
                                Object newSi = ((PlainSelect) tempAgg.getSelectBody()).getSelectItems().get(0);
                                ((List) innerSelect.getSelectItems()).add(0, newSi);
                            }
                            
                            AstUtils.setGroupByByParsing(innerSelect, innerCol.toString());

                            Alias aliasObj = AstUtils.getAlias(aggItem);
                            String aggAlias = aliasObj != null ? aliasObj.getName() : "agg_val";
                            if (aliasObj == null) AstUtils.setAlias(aggItem, new Alias(aggAlias, false));

                            FromItem joinSub = AstUtils.createSubSelectFromItem(innerSelect, "agg_sub");

                            Join join = new Join();
                            join.setRightItem(joinSub);
                            join.setLeft(true);
                            join.setOnExpression(CCJSqlParserUtil.parseCondExpression("agg_sub." + innerCol.getColumnName() + " = " + outerCol.toString()));

                            if (outerSelect.getJoins() == null) outerSelect.setJoins(new ArrayList<>());
                            outerSelect.getJoins().add(join);

                            Select tempCoal = (Select) CCJSqlParserUtil.parse("SELECT COALESCE(agg_sub." + aggAlias + ", 0)");
                            Expression coalesce = AstUtils.getExpression(((PlainSelect) tempCoal.getSelectBody()).getSelectItems().get(0));
                            AstUtils.setExpression(itemObj, coalesce);

                            String finalSql = cloned.toString();
                            if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                                return Optional.of(buildMeta(getId(), getName(), "O(N*M) Row-by-row subquery", "Decorrelated to LEFT JOIN", "HIGH", "Executes subquery once", stmt.toString(), finalSql));
                            }
                        } catch (Exception e) { continue; }
                    }
                }
            }
            return Optional.empty();
        }
    }

    static class P02_WindowPredicatePushdown implements OptimizationPattern {
        @Override public String getId() { return "P02_WINDOW_PUSHDOWN"; }
        @Override public String getName() { return "Window Predicate Pushdown Warning"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            String sql = stmt.toString().toUpperCase();
            return sql.contains("OVER") && sql.contains("WHERE");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                String optimizedSql = stmt.toString() + " /* OPTIMIX: CONSIDER PUSHING BASE-TABLE PREDICATES INTO A SUBQUERY BEFORE WINDOWING */";
                return Optional.of(buildMeta(getId(), getName(), 
                    "Filtering after a window function wastes memory by sorting rows that will be discarded.", 
                    "Flagged for manual subquery pushdown.", 
                    "HIGH", 
                    "Reducing dataset size before the window sorting phase drastically lowers CPU and memory overhead.", 
                    stmt.toString(), optimizedSql));
            }
            return Optional.empty();
        }
    }

    static class P03_SemiJoinReduction implements OptimizationPattern {
        @Override public String getId() { return "P03_SEMI_JOIN_REDUCTION"; }
        @Override public String getName() { return "Semi-Join Reduction"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        private boolean isSafeToApply(Join join, Map<String, TableStatistics> stats) {
            if (join.isLeft() || join.isRight() || join.isOuter() || join.isCross()) return false;
            if (join.getOnExpression() != null && join.getOnExpression().toString().contains("!=")) return false;
            
            if (join.getOnExpression() instanceof EqualsTo eq) {
                Column leftCol = eq.getLeftExpression() instanceof Column col ? col : null;
                Column rightCol = eq.getRightExpression() instanceof Column col ? col : null;
                
                if (stats != null && rightCol != null && rightCol.getTable() != null) {
                    TableStatistics tStats = stats.get(rightCol.getTable().getName().toLowerCase());
                    if (tStats != null) {
                        String pk = AstUtils.getPrimaryKeyColumn(tStats);
                        if (pk != null && pk.equalsIgnoreCase(rightCol.getColumnName())) return true;
                    }
                }
                return AstUtils.isLikelyPrimaryKey(leftCol) || AstUtils.isLikelyPrimaryKey(rightCol);
            }
            return false; 
        }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            return ps.getJoins() != null && !ps.getJoins().isEmpty();
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();

            if (AstUtils.getGroupBy(body) != null || AstUtils.hasAggregates(body)) return Optional.empty();
            if (body.getDistinct() != null) return Optional.empty();

            Set<String> usedQualifiers = new HashSet<>();
            if (body.getSelectItems() != null) {
                for (Object siObj : body.getSelectItems()) {
                    Expression expr = AstUtils.getExpression(siObj);
                    if (expr != null) {
                        usedQualifiers.addAll(AstUtils.extractColumnTablesAndAliases(expr));
                    } else if (siObj.toString().contains(".*")) {
                        usedQualifiers.add(siObj.toString().replace(".*", "").trim().toLowerCase());
                    } else if (siObj.toString().trim().equals("*")) {
                        return Optional.empty(); 
                    }
                }
            }

            if (body.getWhere() != null) usedQualifiers.addAll(AstUtils.extractColumnTablesAndAliases(body.getWhere()));
            if (body.getJoins() == null) return Optional.empty();

            for (Iterator<Join> it = body.getJoins().iterator(); it.hasNext(); ) {
                Join join = it.next();
                if (!isSafeToApply(join, stats)) continue;

                String rightAliasOrName = AstUtils.getAliasOrName(join.getRightItem());

                if (!rightAliasOrName.isEmpty() && !usedQualifiers.contains(rightAliasOrName) && join.getOnExpression() instanceof EqualsTo eq) {
                    if (eq.getLeftExpression() instanceof Column left && eq.getRightExpression() instanceof Column right) {
                        try {
                            String subQuerySql = "SELECT " + right.toString() + " FROM " + join.getRightItem().toString();
                            Expression inExpr = CCJSqlParserUtil.parseCondExpression(left.toString() + " IN (" + subQuerySql + ")");
                            body.setWhere(body.getWhere() == null ? inExpr : new AndExpression(body.getWhere(), inExpr));
                            it.remove();

                            String finalSql = cloned.toString();
                            if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                                return Optional.of(buildMeta(getId(), getName(), "Unused right-side relation in projection", "Reduced to Semi-join (IN)", "HIGH", "Prevents cartesian row duplication", stmt.toString(), finalSql));
                            }
                        } catch (Exception e) {}
                    }
                }
            }
            return Optional.empty();
        }
    }

    static class P04_JoinPredicateMoveAround implements OptimizationPattern {
        @Override public String getId() { return "P04_JPMA"; }
        @Override public String getName() { return "Join Predicate Move Around"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            return ps.getJoins() != null && ps.getWhere() != null;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect body)) return Optional.empty();
            if (body.getJoins() == null) return Optional.empty();

            List<Expression> whereExprs = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (Expression we : new ArrayList<>(whereExprs)) {
                if (we instanceof EqualsTo eq) {
                    if (eq.getRightExpression() instanceof LongValue || eq.getRightExpression() instanceof StringValue) {
                        Column leftCol = eq.getLeftExpression() instanceof Column col ? col : null;
                        if (leftCol == null) continue;

                        for (Join join : body.getJoins()) {
                            if (join.getOnExpression() instanceof EqualsTo jEq) {
                                Column joinLeft = jEq.getLeftExpression() instanceof Column col ? col : null;
                                Column joinRight = jEq.getRightExpression() instanceof Column col ? col : null;

                                Column targetCol = null;
                                if (AstUtils.columnsEqual(joinLeft, leftCol) && joinRight != null) targetCol = joinRight;
                                else if (AstUtils.columnsEqual(joinRight, leftCol) && joinLeft != null) targetCol = joinLeft;

                                if (targetCol != null) {
                                    final EqualsTo finalProp = new EqualsTo(targetCol, eq.getRightExpression());
                                    boolean exists = whereExprs.stream().anyMatch(e -> {
                                        if (!(e instanceof EqualsTo existing)) return false;
                                        return AstUtils.expressionsEqual(existing.getLeftExpression(), finalProp.getLeftExpression()) && 
                                               AstUtils.expressionsEqual(existing.getRightExpression(), finalProp.getRightExpression());
                                    });
                                    
                                    if (!exists) {
                                        whereExprs.add(finalProp);
                                        modified = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (modified) {
                body.setWhere(AstUtils.buildAndTree(whereExprs));
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Unbalanced join filters", "Predicate propagated across join", "MEDIUM", "Enables dual index usage", stmt.toString(), finalSql));
                }
            }
            return Optional.empty();
        }
    }

    static class P05_MaterializedCTE implements OptimizationPattern {
        @Override public String getId() { return "P05_MATERIALIZED_CTE"; }
        @Override public String getName() { return "CTE Inlining & Materialization"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        // Dynamically counts how many times a CTE is referenced in the main query
        private int countCteReferences(PlainSelect ps, String cteName) {
            int count = 0;
            if (ps.getFromItem() instanceof Table tbl && tbl.getName().equalsIgnoreCase(cteName)) count++;
            if (ps.getJoins() != null) {
                for (Join j : ps.getJoins()) {
                    if (j.getRightItem() instanceof Table tbl && tbl.getName().equalsIgnoreCase(cteName)) count++;
                }
            }
            return count;
        }

        // Safely converts the CTE into a derived subquery node
        private boolean inlineCte(PlainSelect body, String cteName, Object cteBody) {
            try {
                String subSql = "SELECT * FROM (" + cteBody.toString() + ") AS " + cteName;
                Select temp = (Select) CCJSqlParserUtil.parse(subSql);
                FromItem subSelect = ((PlainSelect) temp.getSelectBody()).getFromItem();

                if (body.getFromItem() instanceof Table tbl && tbl.getName().equalsIgnoreCase(cteName)) {
                    if (tbl.getAlias() != null) AstUtils.setAlias(subSelect, tbl.getAlias());
                    body.setFromItem(subSelect);
                    return true;
                } else if (body.getJoins() != null) {
                    for (Join j : body.getJoins()) {
                        if (j.getRightItem() instanceof Table tbl && tbl.getName().equalsIgnoreCase(cteName)) {
                            if (tbl.getAlias() != null) AstUtils.setAlias(subSelect, tbl.getAlias());
                            j.setRightItem(subSelect);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {}
            return false;
        }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select sel)) return false;
            try {
                List<?> withItems = (List<?>) sel.getClass().getMethod("getWithItemsList").invoke(sel);
                return withItems != null && !withItems.isEmpty();
            } catch (Exception e) {}
            return false;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (!detect(stmt, stats)) return Optional.empty();
            
            Statement cloned = AstUtils.cloneAst(stmt);
            Select sel = (Select) cloned;
            if (!(sel.getSelectBody() instanceof PlainSelect body)) return Optional.empty();

            try {
                List withItems = (List) sel.getClass().getMethod("getWithItemsList").invoke(sel);
                if (withItems == null || withItems.isEmpty()) return Optional.empty();

                boolean modified = false;
                List<String> materializeHints = new ArrayList<>();
                List<Object> itemsToRemove = new ArrayList<>();

                for (Object withObj : withItems) {
                    String cteName = (String) withObj.getClass().getMethod("getName").invoke(withObj);
                    int refCount = countCteReferences(body, cteName);

                    if (refCount == 1) {
                        // Single use -> INLINE it mathematically
                        Object cteBody = null;
                        try { cteBody = withObj.getClass().getMethod("getSelectBody").invoke(withObj); } catch(Exception e) {}
                        if (cteBody == null) {
                            try { cteBody = withObj.getClass().getMethod("getSelect").invoke(withObj); } catch(Exception e) {}
                        }
                        
                        if (cteBody != null && inlineCte(body, cteName, cteBody)) {
                            itemsToRemove.add(withObj);
                            modified = true;
                        }
                    } else if (refCount > 1) {
                        // Multi use -> Emit MATERIALIZE hint
                        materializeHints.add(cteName);
                        modified = true;
                    }
                }

                if (modified) {
                    withItems.removeAll(itemsToRemove);
                    // If all CTEs were inlined, remove the WITH clause entirely
                    if (withItems.isEmpty()) {
                        sel.getClass().getMethod("setWithItemsList", List.class).invoke(sel, (List)null);
                    }

                    String finalSql = cloned.toString();
                    
                    // Append hints for multi-use CTEs
                    if (!materializeHints.isEmpty()) {
                        String hints = String.join(", ", materializeHints);
                        finalSql += " /* OPTIMIX: MATERIALIZE CTE (" + hints + ") TO PREVENT REDUNDANT O(N) EXECUTION */";
                    }

                    return Optional.of(buildMeta(getId(), getName(), 
                        "CTEs are opaque boundaries. Single-use CTEs block global optimization, while multi-use CTEs trigger redundant O(N) re-evaluations.", 
                        "Inlined single-use CTEs. Emitted Materialization Hints for multi-use CTEs.", 
                        "HIGH", 
                        "Inlining exposes base tables to the optimizer. Materialization spools data into an O(1) memory read, bypassing redundant execution.", 
                        stmt.toString(), finalSql));
                }
            } catch (Exception e) {}
            return Optional.empty();
        }
    }

    static class P06_GroupByKeyElimination implements OptimizationPattern {
        @Override public String getId() { return "P06_GROUP_BY_KEY_ELIM"; }
        @Override public String getName() { return "Group By Key Elimination"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            if (ps.getJoins() != null && !ps.getJoins().isEmpty()) return false;
            
            List<Expression> exprs = AstUtils.getGroupByExpressions(ps);
            return exprs != null && exprs.size() > 1;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (!detect(stmt, stats)) return Optional.empty();
            Statement cloned = AstUtils.cloneAst(stmt);
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            
            try {
                List<Expression> gbExprs = AstUtils.getGroupByExpressions(body);
                List<Expression> newGb = new ArrayList<>();
                boolean modified = false;

                for (Expression expr : gbExprs) {
                    if (expr instanceof Column col) {
                        String tableName = col.getTable() != null && col.getTable().getName() != null ? col.getTable().getName().toLowerCase() : AstUtils.getAliasOrName(body.getFromItem());
                        
                        boolean isPrimaryKey = false;
                        if (stats != null && stats.containsKey(tableName)) {
                            String pk = AstUtils.getPrimaryKeyColumn(stats.get(tableName));
                            if (pk != null && pk.equalsIgnoreCase(col.getColumnName())) isPrimaryKey = true;
                        }

                        if (isPrimaryKey) {
                            newGb.add(expr);
                        } else {
                            modified = true;
                        }
                    } else {
                        newGb.add(expr);
                    }
                }

                if (modified && !newGb.isEmpty()) {
                    String gbStr = newGb.stream().map(Expression::toString).reduce((a, b) -> a + ", " + b).orElse("");
                    AstUtils.setGroupByByParsing(body, gbStr);
                    String finalSql = cloned.toString();
                    return Optional.of(buildMeta(getId(), getName(), "Redundant Group By keys", "Eliminated functionally dependent keys using metadata.", "LOW", "Reduces hashing overhead", stmt.toString(), finalSql));
                }
            } catch (Exception e) {}
            return Optional.empty();
        }
    }

    static class P07_AggregationPushdown implements OptimizationPattern {
        @Override public String getId() { return "P07_AGG_PUSHDOWN"; }
        @Override public String getName() { return "Aggregation Pushdown"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            
            if (ps.getJoins() == null || ps.getJoins().size() != 1) return false;
            if (AstUtils.getGroupBy(ps) == null || !AstUtils.hasAggregates(ps)) return false;
            if (!(ps.getJoins().get(0).getRightItem() instanceof Table)) return false;
            
            return true;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (!detect(stmt, stats)) return Optional.empty();
            
            try {
                PlainSelect ps = (PlainSelect) ((Select) stmt).getSelectBody();
                Join join = ps.getJoins().get(0);
                FromItem leftTable = ps.getFromItem();
                FromItem rightTable = join.getRightItem();
                
                if (join.getOnExpression() instanceof EqualsTo eq) {
                    Function sumFunction = null;
                    String nonSumColStr = null;
                    
                    for (Object itemObj : ps.getSelectItems()) {
                        Expression expr = AstUtils.getExpression(itemObj);
                        if (expr instanceof Function func && func.getName().equalsIgnoreCase("SUM")) {
                            sumFunction = func;
                        } else {
                            nonSumColStr = itemObj.toString(); 
                        }
                    }
                    
                    if (sumFunction != null && nonSumColStr != null) {
                        Object parameters = sumFunction.getClass().getMethod("getParameters").invoke(sumFunction);
                        List<?> exprList = (List<?>) parameters.getClass().getMethod("getExpressions").invoke(parameters);
                        if (exprList == null || exprList.isEmpty()) return Optional.empty();
                        
                        String rawInnerCol = exprList.get(0).toString();
                        if (rawInnerCol.contains(".")) rawInnerCol = rawInnerCol.split("\\.")[1];

                        String leftJoinCol = eq.getLeftExpression().toString();
                        String rightJoinCol = eq.getRightExpression().toString();
                        
                        String rightAlias = AstUtils.getAliasOrName(rightTable);
                        String innerJoinCol = leftJoinCol.startsWith(rightAlias + ".") ? leftJoinCol : rightJoinCol;
                        String outerJoinCol = leftJoinCol.startsWith(rightAlias + ".") ? rightJoinCol : leftJoinCol;

                        String innerSql = "SELECT " + innerJoinCol + ", SUM(" + rawInnerCol + ") AS pre_agg_sum FROM " + rightTable.toString() + " GROUP BY " + innerJoinCol;
                        String newOnCond = outerJoinCol + " = " + rightAlias + "_agg." + innerJoinCol.substring(innerJoinCol.indexOf('.') + 1);

                        String optimized = "SELECT " + nonSumColStr + ", SUM(" + rightAlias + "_agg.pre_agg_sum) FROM " + leftTable.toString() + 
                                           " JOIN (" + innerSql + ") AS " + rightAlias + "_agg ON " + newOnCond + 
                                           " GROUP BY " + nonSumColStr;

                        Statement temp = CCJSqlParserUtil.parse(optimized);
                        return Optional.of(buildMeta(getId(), getName(), "Aggregation after massive join", "Pre-aggregation before join (AST safe)", "HIGH", "Reduces input rows to join", stmt.toString(), temp.toString()));
                    }
                }
            } catch (Exception e) {}
            return Optional.empty();
        }
    }

    static class P08_SubqueryUnnesting implements OptimizationPattern {
        @Override public String getId() { return "P08_SUBQUERY_UNNESTING"; }
        @Override public String getName() { return "Subquery Unnesting"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            return AstUtils.getSubSelectBodyFromItem(ps.getFromItem()) != null;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect body)) return Optional.empty();
            
            PlainSelect subBody = AstUtils.getSubSelectBodyFromItem(body.getFromItem());
            
            if (subBody != null) {
                if (AstUtils.getGroupBy(subBody) == null && subBody.getLimit() == null && subBody.getDistinct() == null && subBody.getOrderByElements() == null) {
                    if (subBody.getSelectItems() != null) {
                        for (Object siObj : subBody.getSelectItems()) {
                            Expression e = AstUtils.getExpression(siObj);
                            if (!(e instanceof Column) && e != null) return Optional.empty(); 
                        }
                    }
                    try {
                        Object subFromItem = body.getFromItem();
                        Alias alias = (Alias) subFromItem.getClass().getMethod("getAlias").invoke(subFromItem);
                        
                        FromItem innerFrom = subBody.getFromItem();
                        if (alias != null) innerFrom.setAlias(alias); 
                        body.setFromItem(innerFrom);

                        if (subBody.getWhere() != null) body.setWhere(body.getWhere() == null ? subBody.getWhere() : new AndExpression(subBody.getWhere(), body.getWhere()));

                        if (subBody.getJoins() != null) {
                            if (body.getJoins() == null) body.setJoins(new ArrayList<>());
                            body.getJoins().addAll(0, subBody.getJoins());
                        }

                        String finalSql = cloned.toString();
                        if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                            return Optional.of(buildMeta(getId(), getName(), "Opaque derived table blocks optimizer", "Flattened subquery", "MEDIUM", "Exposes base tables to global optimizer", stmt.toString(), finalSql));
                        }
                    } catch(Exception ex) {}
                }
            }
            return Optional.empty();
        }
    }

    static class P09_IndexOnlyScan implements OptimizationPattern {
        @Override public String getId() { return "P09_INDEX_ONLY_SCAN"; }
        @Override public String getName() { return "Index-Only Scan (EXISTS optimization)"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            boolean[] found = {false};
            if (stmt != null) {
                stmt.accept(new StatementVisitorAdapter() {
                    @Override
                    public void visit(Select select) {
                        if (select.getSelectBody() != null) {
                            select.getSelectBody().accept(new SelectVisitorAdapter() {
                                @Override
                                public void visit(PlainSelect plainSelect) {
                                    if (plainSelect.getWhere() != null) {
                                        plainSelect.getWhere().accept(new ExpressionVisitorAdapter() {
                                            @Override
                                            public void visit(ExistsExpression exists) {
                                                PlainSelect subPs = AstUtils.getSubSelectBody(exists);
                                                if (subPs != null && subPs.getSelectItems() != null && subPs.getSelectItems().size() == 1) {
                                                    Object siObj = subPs.getSelectItems().get(0);
                                                    if (siObj.toString().trim().equals("*") || siObj.getClass().getSimpleName().equals("AllColumns")) found[0] = true;
                                                }
                                            }
                                        });
                                    }
                                }
                            });
                        }
                    }
                });
            }
            return found[0];
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect)) return Optional.empty();
            
            boolean[] modified = {false};

            ((PlainSelect) cBody).accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(ExistsExpression exists) {
                    PlainSelect subPs = AstUtils.getSubSelectBody(exists);
                    if (subPs != null) {
                        subPs.setDistinct(null);
                        if (subPs.getSelectItems() != null && subPs.getSelectItems().size() == 1) {
                            Object siObj = subPs.getSelectItems().get(0);
                            if (siObj.toString().trim().equals("*") || siObj.getClass().getSimpleName().equals("AllColumns")) {
                                try {
                                    Select tempSel = (Select) CCJSqlParserUtil.parse("SELECT 1");
                                    PlainSelect tempPs = (PlainSelect) tempSel.getSelectBody();
                                    subPs.getSelectItems().clear();
                                    ((List) subPs.getSelectItems()).add(tempPs.getSelectItems().get(0));
                                    
                                    if (subPs.getLimit() == null) {
                                        Select limitParser = (Select) CCJSqlParserUtil.parse("SELECT 1 LIMIT 1");
                                        subPs.setLimit(((PlainSelect) limitParser.getSelectBody()).getLimit());
                                    }
                                    modified[0] = true;
                                } catch (Exception e) {}
                            }
                        }
                    }
                }
            });

            if (modified[0]) {
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Unnecessary SELECT * in EXISTS", "Replaced with SELECT 1 and pushed LIMIT", "HIGH", "Avoids heap fetch and early-exits check", stmt.toString(), finalSql));
                }
            }
            return Optional.empty();
        }
    }

    static class P10_DPJoinOrder implements OptimizationPattern {
        @Override public String getId() { return "P10_DP_JOIN_ORDER"; }
        @Override public String getName() { return "Dynamic Programming Join Reordering"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            return ps.getJoins() != null && ps.getJoins().size() >= 2;
        }

        private void flattenPlan(com.optimix.optimizer.join.JoinOptimizer.JoinPlan plan, List<String> sequence) {
            if (plan == null) return;
            if (plan.isLeaf()) {
                sequence.add(plan.singleTable);
            } else {
                flattenPlan(plan.left, sequence);
                flattenPlan(plan.right, sequence);
            }
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (!detect(stmt, stats)) return Optional.empty();
            
            Statement cloned = AstUtils.cloneAst(stmt);
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            
            try {
                // Extract real table names
                List<String> realNames = new ArrayList<>();
                String baseReal = "";
                if (body.getFromItem() instanceof Table tbl) baseReal = tbl.getName().toLowerCase();
                realNames.add(baseReal);
                
                for (Join j : body.getJoins()) {
                    if (j.getRightItem() instanceof Table tbl) realNames.add(tbl.getName().toLowerCase());
                }

                // Run Dynamic Programming
                com.optimix.optimizer.cost.CostCalculator costCalc = new com.optimix.optimizer.cost.CostCalculator();
                com.optimix.optimizer.join.JoinOptimizer engine = new com.optimix.optimizer.join.JoinOptimizer(costCalc, stats);
                com.optimix.optimizer.join.JoinOptimizer.JoinPlan bestPlan = engine.findOptimalOrder(realNames);

                List<String> optimalSequence = new ArrayList<>();
                flattenPlan(bestPlan, optimalSequence);

                // If the DP engine decides the driving table MUST change
                if (!optimalSequence.isEmpty() && !optimalSequence.get(0).equalsIgnoreCase(baseReal)) {
                    String sequenceStr = String.join(" -> ", optimalSequence);
                    
                    // INJECT A SAFE AST HINT: We create a dummy WHERE condition that prints our hint!
                    Expression hintExpr = CCJSqlParserUtil.parseCondExpression("'OPTIMIX_HINT: " + sequenceStr + "' = 'OPTIMIX_HINT: " + sequenceStr + "'");
                    body.setWhere(body.getWhere() == null ? hintExpr : new AndExpression(body.getWhere(), hintExpr));
                    
                    String finalSql = cloned.toString();
                    return Optional.of(buildMeta(getId(), getName(), 
                        "Suboptimal join ordering increases intermediate row cardinality.", 
                        "Injected Optimal Join Sequence Hint into AST.", 
                        "HIGH", 
                        "Changing the driving table reduces memory overhead. Passed explicit string hint to database executor.", 
                        stmt.toString(), finalSql));
                }
            } catch (Exception e) {}
            
            return Optional.empty();
        }
    }
}