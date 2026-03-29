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
                if (select instanceof Select && ((Select)select).getSelectBody() instanceof PlainSelect) return (PlainSelect) ((Select)select).getSelectBody();
                if (select instanceof PlainSelect) return (PlainSelect) select;
            } catch (Exception e) {}
            try {
                Object body = fromItem.getClass().getMethod("getSelectBody").invoke(fromItem);
                if (body instanceof PlainSelect) return (PlainSelect) body;
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

    private static OptimizationResult.PatternApplication buildMeta(String id, String name, String problem, 
                                                                  String solution, String impact, String reason,
                                                                  String before, String after, double confidence) {
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
                    if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof Column) {
                        Column left = (Column) eq.getLeftExpression(); Column right = (Column) eq.getRightExpression();
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
                            if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof Column) {
                                Column left = (Column) eq.getLeftExpression(); Column right = (Column) eq.getRightExpression();
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

                            // FIX 1: Set alias BEFORE creating the subselect item!
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
                                return Optional.of(buildMeta(getId(), getName(), "O(N*M) Row-by-row subquery", "Decorrelated to LEFT JOIN", "HIGH", "Executes subquery once", stmt.toString(), finalSql, 0.95));
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
        @Override public String getName() { return "Window Predicate Pushdown"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            
            if (ps.getHaving() != null || ps.getWhere() == null || ps.getSelectItems() == null) return false;
            
            boolean[] hasWindow = {false};
            for (Object itemObj : ps.getSelectItems()) {
                // FIX 2: Relaxed regex spacing for Window parsing
                if (itemObj != null && itemObj.toString().toUpperCase().contains("OVER")) {
                    hasWindow[0] = true;
                }
            }
            return hasWindow[0];
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect outerSelect)) return Optional.empty();
            
            if (outerSelect.getHaving() != null || outerSelect.getLimit() != null || outerSelect.getOrderByElements() != null) return Optional.empty();

            try {
                if (outerSelect.getWhere().toString().toUpperCase().contains("ROW_NUMBER") || 
                    outerSelect.getWhere().toString().toUpperCase().contains("RANK")) {
                    return Optional.empty();
                }

                PlainSelect innerSelect = new PlainSelect();
                innerSelect.setFromItem(outerSelect.getFromItem());
                innerSelect.setJoins(outerSelect.getJoins());
                innerSelect.setWhere(outerSelect.getWhere());
                
                Select tempAll = (Select) CCJSqlParserUtil.parse("SELECT *");
                ((List) innerSelect.getSelectItems()).add(((PlainSelect) tempAll.getSelectBody()).getSelectItems().get(0));

                String innerSql = "SELECT * FROM (" + innerSelect.toString() + ") AS win_sub";
                Statement tempStmt = CCJSqlParserUtil.parse(innerSql);
                FromItem innerSub = ((PlainSelect) ((Select) tempStmt).getSelectBody()).getFromItem();

                outerSelect.setFromItem(innerSub);
                outerSelect.setJoins(null);
                outerSelect.setWhere(null);

                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Filter applied after window function", "Predicate pushed into subquery", "HIGH", "Reduces rows prior to windowing", stmt.toString(), finalSql, 0.90));
                }
            } catch (Exception e) {}
            
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
                Column leftCol = eq.getLeftExpression() instanceof Column ? (Column) eq.getLeftExpression() : null;
                Column rightCol = eq.getRightExpression() instanceof Column ? (Column) eq.getRightExpression() : null;
                
                if (stats != null && rightCol != null && rightCol.getTable() != null) {
                    TableStatistics tStats = stats.get(rightCol.getTable().getName().toLowerCase());
                    if (tStats != null) {
                        if (tStats.rowCount < 100) return false;
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
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect body)) return Optional.empty();

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
                            String subQuerySql = "SELECT DISTINCT " + right.toString() + " FROM " + join.getRightItem().toString();
                            Expression inExpr = CCJSqlParserUtil.parseCondExpression(left.toString() + " IN (" + subQuerySql + ")");
                            body.setWhere(body.getWhere() == null ? inExpr : new AndExpression(body.getWhere(), inExpr));
                            it.remove();

                            String finalSql = cloned.toString();
                            if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                                return Optional.of(buildMeta(getId(), getName(), "Redundant join data fetching", "Converted to Semi-join (IN)", "HIGH", "Prevents row duplication", stmt.toString(), finalSql, 0.98));
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
                        Column leftCol = eq.getLeftExpression() instanceof Column ? (Column) eq.getLeftExpression() : null;
                        if (leftCol == null) continue;

                        for (Join join : body.getJoins()) {
                            if (join.getOnExpression() instanceof EqualsTo jEq) {
                                Column joinLeft = jEq.getLeftExpression() instanceof Column ? (Column) jEq.getLeftExpression() : null;
                                Column joinRight = jEq.getRightExpression() instanceof Column ? (Column) jEq.getRightExpression() : null;

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
                    return Optional.of(buildMeta(getId(), getName(), "Unbalanced join filters", "Predicate propagated across join", "MEDIUM", "Enables dual index usage", stmt.toString(), finalSql, 0.99));
                }
            }
            return Optional.empty();
        }
    }

    static class P05_MaterializedCTE implements OptimizationPattern {
        @Override public String getId() { return "P05_MATERIALIZED_CTE"; }
        @Override public String getName() { return "Materialized CTE"; }
        @Override public Tier getTier() { return Tier.TIER1; }
        @Override public boolean detect(Statement stmt, Map<String, TableStatistics> stats) { return false; }
        @Override public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) { return Optional.empty(); }
    }

    static class P06_GroupByKeyElimination implements OptimizationPattern {
        @Override public String getId() { return "P06_GROUP_BY_KEY_ELIM"; }
        @Override public String getName() { return "Group By Key Elimination"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (stats == null || stats.isEmpty()) return false;
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            if (ps.getJoins() != null && !ps.getJoins().isEmpty()) return false;
            
            List<Expression> exprs = AstUtils.getGroupByExpressions(ps);
            return exprs != null && exprs.size() > 1;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (stats == null) return Optional.empty();
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect body)) return Optional.empty();
            
            if (body.getJoins() != null && !body.getJoins().isEmpty()) return Optional.empty();

            List<Expression> gbExprs = AstUtils.getGroupByExpressions(body);
            if (gbExprs == null) return Optional.empty();

            boolean modified = false;
            Set<String> pkTablesPresent = new HashSet<>();

            for (Expression expr : gbExprs) {
                if (expr instanceof Column col) {
                    if (col.getTable() != null && col.getTable().getName() != null) {
                        String tableName = col.getTable().getName().toLowerCase();
                        if (stats.containsKey(tableName)) {
                            TableStatistics tStats = stats.get(tableName);
                            String pk = AstUtils.getPrimaryKeyColumn(tStats);
                            if (pk != null && pk.equalsIgnoreCase(col.getColumnName())) pkTablesPresent.add(tableName);
                        } else if (AstUtils.isLikelyPrimaryKey(col)) {
                            pkTablesPresent.add(tableName);
                        }
                    }
                }
            }

            // FIX 3: Safely reconstruct Group By string instead of mutating abstract list
            List<Expression> newGb = new ArrayList<>();
            if (!pkTablesPresent.isEmpty()) {
                for (Expression expr : gbExprs) {
                    if (expr instanceof Column col && col.getTable() != null && col.getTable().getName() != null) {
                        String tableName = col.getTable().getName().toLowerCase();
                        TableStatistics tStats = stats.get(tableName);
                        String pk = AstUtils.getPrimaryKeyColumn(tStats);
                        
                        if (pkTablesPresent.contains(tableName)) {
                            boolean isPk = (pk != null && pk.equalsIgnoreCase(col.getColumnName())) || AstUtils.isLikelyPrimaryKey(col);
                            if (isPk) newGb.add(expr);
                            else modified = true;
                        } else { newGb.add(expr); }
                    } else { newGb.add(expr); }
                }
            }

            if (modified && !newGb.isEmpty()) {
                String gbStr = newGb.stream().map(Expression::toString).reduce((a, b) -> a + ", " + b).orElse("");
                AstUtils.setGroupByByParsing(body, gbStr);
                String finalSql = cloned.toString();
                if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                    return Optional.of(buildMeta(getId(), getName(), "Redundant Group By keys", "Eliminated functionally dependent keys", "LOW", "Reduces hashing overhead", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P07_AggregationPushdown implements OptimizationPattern {
        @Override public String getId() { return "P07_AGG_PUSHDOWN"; }
        @Override public String getName() { return "Aggregation Pushdown"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        private boolean isSafeToApply(PlainSelect outer, String targetTable, Map<String, TableStatistics> stats) {
            if (outer.getJoins() == null || outer.getJoins().size() != 1) return false;
            if (outer.getWhere() != null) {
                Set<String> whereTables = AstUtils.extractColumnTablesAndAliases(outer.getWhere());
                if (whereTables.contains(targetTable.toLowerCase())) return false;
            }
            if (outer.getLimit() != null || outer.getOrderByElements() != null) return false;
            
            if (stats != null && stats.containsKey(targetTable.toLowerCase())) {
                TableStatistics tStats = stats.get(targetTable.toLowerCase());
                if (AstUtils.getPrimaryKeyColumn(tStats) == null) return false;
            } else return false;
            return true;
        }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            
            if (ps.getJoins() == null || ps.getJoins().size() != 1) return false;
            return AstUtils.getGroupBy(ps) != null && AstUtils.hasAggregates(ps);
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            Object cBody = ((Select) cloned).getSelectBody();
            if (!(cBody instanceof PlainSelect outer)) return Optional.empty();
            
            if (outer.getJoins() == null || outer.getJoins().size() != 1) return Optional.empty();
            if (outer.getSelectItems() == null) return Optional.empty();

            for (int i = 0; i < outer.getSelectItems().size(); i++) {
                Object itemObj = outer.getSelectItems().get(i);
                Expression expr = AstUtils.getExpression(itemObj);
                if (expr instanceof Function func) {
                    if (func.getName() != null && func.getName().equalsIgnoreCase("SUM") && func.getParameters() != null) {
                        try {
                            List<?> pList = (List<?>) func.getParameters().getClass().getMethod("getExpressions").invoke(func.getParameters());
                            if (pList != null && !pList.isEmpty() && pList.get(0) instanceof Column sumCol) {
                                if (sumCol.getTable() != null && sumCol.getTable().getName() != null) {
                                    
                                    String targetTable = sumCol.getTable().getName();
                                    if (!isSafeToApply(outer, targetTable, stats)) continue;
                                    
                                    Join join = outer.getJoins().get(0);
                                    if (join.getRightItem() instanceof Table tbl && targetTable.equalsIgnoreCase(tbl.getName()) && join.getOnExpression() instanceof EqualsTo eq) {
                                        Column joinCol = null; Column outerJoinCol = null;
                                        
                                        if (eq.getLeftExpression() instanceof Column lCol && lCol.getTable() != null && lCol.getTable().getName().equalsIgnoreCase(targetTable)) {
                                            joinCol = lCol; outerJoinCol = (Column) eq.getRightExpression();
                                        } else if (eq.getRightExpression() instanceof Column rCol && rCol.getTable() != null && rCol.getTable().getName().equalsIgnoreCase(targetTable)) {
                                            joinCol = rCol; outerJoinCol = (Column) eq.getLeftExpression();
                                        }

                                        if (joinCol != null && outerJoinCol != null && AstUtils.isLikelyPrimaryKey(joinCol)) {
                                            String safeTableName = tbl.getAlias() != null ? tbl.getAlias().getName() : tbl.getName();
                                            String subQuerySql = "SELECT " + joinCol.toString() + ", SUM(" + safeTableName + "." + sumCol.getColumnName() + ") AS pre_agg_sum FROM " + tbl.toString() + " GROUP BY " + joinCol.toString();
                                            
                                            Statement tempStmt = CCJSqlParserUtil.parse("SELECT * FROM (" + subQuerySql + ") AS " + safeTableName + "_agg");
                                            FromItem subFrom = ((PlainSelect) ((Select) tempStmt).getSelectBody()).getFromItem();
                                            
                                            join.setRightItem(subFrom);
                                            join.setOnExpression(CCJSqlParserUtil.parseCondExpression(outerJoinCol.toString() + " = " + safeTableName + "_agg." + joinCol.getColumnName()));
                                            
                                            Select tempFinalCol = (Select) CCJSqlParserUtil.parse("SELECT " + safeTableName + "_agg.pre_agg_sum");
                                            AstUtils.setExpression(itemObj, AstUtils.getExpression(((PlainSelect)tempFinalCol.getSelectBody()).getSelectItems().get(0)));
                                            
                                            String finalSql = cloned.toString();
                                            if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                                                return Optional.of(buildMeta(getId(), getName(), "Aggregation after massive join", "Pre-aggregation before join", "HIGH", "Reduces input rows to join", stmt.toString(), finalSql, 0.85));
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
            }
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
                            return Optional.of(buildMeta(getId(), getName(), "Opaque derived table blocks optimizer", "Flattened subquery", "MEDIUM", "Exposes base tables to global optimizer", stmt.toString(), finalSql, 0.95));
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
                    return Optional.of(buildMeta(getId(), getName(), "Unnecessary SELECT * in EXISTS", "Replaced with SELECT 1 and pushed LIMIT", "HIGH", "Avoids heap fetch and early-exits check", stmt.toString(), finalSql, 1.0));
                }
            }
            return Optional.empty();
        }
    }

    static class P10_DPJoinOrder implements OptimizationPattern {
        @Override public String getId() { return "P10_DP_JOIN_ORDER"; }
        @Override public String getName() { return "Dynamic Programming Join Order"; }
        @Override public Tier getTier() { return Tier.TIER1; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect ps)) return false;
            return ps.getJoins() != null && ps.getJoins().size() > 1;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            return Optional.empty();
        }
    }
}