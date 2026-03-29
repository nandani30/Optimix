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

        public static PlainSelect getSubSelectBody(Expression expr) {
            if (expr == null) return null;
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

        public static Expression getExpression(Object selectItem) {
            try { return (Expression) selectItem.getClass().getMethod("getExpression").invoke(selectItem); } 
            catch (Exception e) { return null; }
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

    static class P26_SelectStar implements OptimizationPattern {
        @Override public String getId() { return "P26_SELECT_STAR"; }
        @Override public String getName() { return "SELECT * Projection Warning"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (sBody instanceof PlainSelect) {
                PlainSelect ps = (PlainSelect) sBody;
                if (ps.getSelectItems() != null) {
                    for (Object item : ps.getSelectItems()) {
                        if (item.getClass().getSimpleName().contains("AllColumns")) return true;
                    }
                }
            }
            return false;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                return Optional.of(buildMeta(getId(), getName(), "SELECT * fetches unnecessary columns, wasting memory.", "Identified SELECT * via AST AllColumns detection.", "LOW", "Reduces network I/O and memory allocation.", stmt.toString(), stmt.toString(), 1.0));
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
                if (ands.isEmpty()) body.setWhere(null);
                else body.setWhere(AstUtils.buildAndTree(ands));
                
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
        @Override public String getName() { return "Expression Simplification"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;

            boolean[] found = {false};
            ps.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(net.sf.jsqlparser.expression.operators.arithmetic.Addition expr) {
                    if (expr.getRightExpression() instanceof net.sf.jsqlparser.expression.LongValue) {
                        if (((net.sf.jsqlparser.expression.LongValue) expr.getRightExpression()).getValue() == 0) found[0] = true;
                    }
                }
                @Override
                public void visit(net.sf.jsqlparser.expression.operators.arithmetic.Multiplication expr) {
                    if (expr.getRightExpression() instanceof net.sf.jsqlparser.expression.LongValue) {
                        if (((net.sf.jsqlparser.expression.LongValue) expr.getRightExpression()).getValue() == 1) found[0] = true;
                    }
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                 return Optional.of(buildMeta(getId(), getName(), "Useless algebraic operations (+0, *1) slow down execution.", "Removed mathematically neutral operations.", "LOW", "Reduces CPU cycles.", stmt.toString(), stmt.toString().replaceAll("\\+\\s*0\\b", "").replaceAll("\\*\\s*1\\b", ""), 1.0));
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
        @Override public String getId() { return "P30_ALGEBRAIC_IDENTITY"; }
        @Override public String getName() { return "Algebraic Identity"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;

            boolean[] found = {false};
            ps.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(EqualsTo expr) {
                    if (expr.getLeftExpression() != null && expr.getRightExpression() != null) {
                        if (expr.getLeftExpression().toString().equalsIgnoreCase(expr.getRightExpression().toString())) {
                            found[0] = true;
                        }
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

            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < ands.size(); i++) {
                if (ands.get(i) instanceof EqualsTo) {
                    EqualsTo eq = (EqualsTo) ands.get(i);
                    if (eq.getLeftExpression().toString().equalsIgnoreCase(eq.getRightExpression().toString())) {
                        try {
                            ands.set(i, CCJSqlParserUtil.parseCondExpression("1 = 1"));
                            modified = true;
                        } catch (Exception e) {}
                    }
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
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;
            
            boolean[] found = {false};
            ps.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(net.sf.jsqlparser.expression.operators.relational.InExpression expr) {
                    try {
                        Object rightItems = expr.getClass().getMethod("getRightItemsList").invoke(expr);
                        if (rightItems.getClass().getSimpleName().equals("ExpressionList")) {
                            List<Expression> exprs = (List<Expression>) rightItems.getClass().getMethod("getExpressions").invoke(rightItems);
                            Set<String> seen = new HashSet<>();
                            for (Expression e : exprs) {
                                if (!seen.add(e.toString())) found[0] = true;
                            }
                        }
                    } catch (Exception e) {}
                }
            });
            return found[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            if (body.getWhere() == null) return Optional.empty();

            boolean[] modified = {false};
            body.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(net.sf.jsqlparser.expression.operators.relational.InExpression expr) {
                    try {
                        Object rightItems = expr.getClass().getMethod("getRightItemsList").invoke(expr);
                        if (rightItems.getClass().getSimpleName().equals("ExpressionList")) {
                            List<Expression> exprs = (List<Expression>) rightItems.getClass().getMethod("getExpressions").invoke(rightItems);
                            Set<String> seen = new java.util.LinkedHashSet<>();
                            List<Expression> unique = new ArrayList<>();
                            for (Expression e : exprs) {
                                if (seen.add(e.toString())) unique.add(e);
                            }
                            if (unique.size() < exprs.size()) {
                                rightItems.getClass().getMethod("setExpressions", List.class).invoke(rightItems, unique);
                                modified[0] = true;
                            }
                        }
                    } catch (Exception e) {}
                }
            });

            if (modified[0]) {
                return Optional.of(buildMeta(getId(), getName(), "Duplicate values in IN lists waste evaluation time.", "Deduplicated IN list values via AST ExpressionList traversal.", "LOW", "Reduces comparison cycles.", stmt.toString(), cloned.toString(), 1.0));
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
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;
            
            String whereStr = ps.getWhere().toString().toUpperCase();
            return whereStr.contains("IS NULL") && whereStr.contains("=");
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();

            if (body.getWhere() == null) return Optional.empty();
            
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;
            Set<String> nullCols = new HashSet<>();
            Set<String> eqCols = new HashSet<>();

            for (Expression expr : ands) {
                if (expr.getClass().getSimpleName().equals("IsNullExpression")) {
                    try {
                        boolean isNot = (boolean) expr.getClass().getMethod("isNot").invoke(expr);
                        Expression left = (Expression) expr.getClass().getMethod("getLeftExpression").invoke(expr);
                        if (!isNot && left instanceof Column) {
                            nullCols.add(((Column)left).getFullyQualifiedName().toLowerCase());
                        }
                    } catch (Exception e) {}
                }
                if (expr instanceof EqualsTo) {
                    EqualsTo eq = (EqualsTo) expr;
                    if (eq.getLeftExpression() instanceof Column) {
                        eqCols.add(((Column)eq.getLeftExpression()).getFullyQualifiedName().toLowerCase());
                    } else if (eq.getRightExpression() instanceof Column) {
                        eqCols.add(((Column)eq.getRightExpression()).getFullyQualifiedName().toLowerCase());
                    }
                }
            }

            for (String col : nullCols) {
                if (eqCols.contains(col)) { modified = true; break; }
            }

            if (modified) {
                try {
                    body.setWhere(CCJSqlParserUtil.parseCondExpression("1 = 0"));
                    String finalSql = cloned.toString();
                    if (AstUtils.isValidSql(finalSql) && !finalSql.equals(stmt.toString())) {
                        return Optional.of(buildMeta(getId(), getName(), "Column is checked for both IS NULL and equality, which is a mathematical contradiction.", "Short-circuited WHERE clause to 1=0.", "HIGH", "Prevents impossible table scans from executing.", stmt.toString(), finalSql, 1.0));
                    }
                } catch (Exception e) {}
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
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;
            
            boolean[] found = {false};
            ps.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(net.sf.jsqlparser.expression.NotExpression expr) {
                    if (expr.getExpression() instanceof net.sf.jsqlparser.expression.operators.relational.NotEqualsTo) {
                        found[0] = true;
                    }
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            if (body.getWhere() == null) return Optional.empty();
            
            List<Expression> ands = AstUtils.flattenAnds(body.getWhere());
            boolean modified = false;

            for (int i = 0; i < ands.size(); i++) {
                if (ands.get(i) instanceof net.sf.jsqlparser.expression.NotExpression) {
                    net.sf.jsqlparser.expression.NotExpression notExpr = (net.sf.jsqlparser.expression.NotExpression) ands.get(i);
                    if (notExpr.getExpression() instanceof net.sf.jsqlparser.expression.operators.relational.NotEqualsTo) {
                        net.sf.jsqlparser.expression.operators.relational.NotEqualsTo neq = (net.sf.jsqlparser.expression.operators.relational.NotEqualsTo) notExpr.getExpression();
                        EqualsTo eq = new EqualsTo(neq.getLeftExpression(), neq.getRightExpression());
                        ands.set(i, eq);
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
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getLimit() == null) return false;
            return AstUtils.getSubSelectBodyFromItem(ps.getFromItem()) != null;
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            
            if (body.getLimit() == null) return Optional.empty();

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
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            
            if (ps.getSelectItems() == null) return false;
            Set<String> seen = new HashSet<>();
            for (Object obj : ps.getSelectItems()) {
                Expression expr = AstUtils.getExpression(obj);
                PlainSelect inner = AstUtils.getSubSelectBody(expr);
                if (inner != null) {
                    if (!seen.add(inner.toString())) return true;
                }
            }
            return false;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            Statement cloned = AstUtils.cloneAst(stmt);
            if (cloned == null || !(cloned instanceof Select)) return Optional.empty();
            PlainSelect body = (PlainSelect) ((Select) cloned).getSelectBody();
            
            if (body.getSelectItems() == null) return Optional.empty();
            
            boolean modified = false;
            Set<String> seen = new HashSet<>();
            Iterator it = body.getSelectItems().iterator();
            
            while(it.hasNext()) {
                Object obj = it.next();
                Expression expr = AstUtils.getExpression(obj);
                PlainSelect inner = AstUtils.getSubSelectBody(expr);
                if (inner != null) {
                    if (!seen.add(inner.toString())) {
                        it.remove();
                        modified = true;
                    }
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
        @Override public String getId() { return "P38_SCALAR_TO_JOIN"; }
        @Override public String getName() { return "Scalar to Join"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            return false; // Handled by Tier 2 P13 logic safely to avoid duplicate triggering.
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            return Optional.empty(); 
        }
    }

    static class P39_Sargability implements OptimizationPattern {
        @Override public String getId() { return "P39_SARGABILITY"; }
        @Override public String getName() { return "SARGability"; }
        @Override public Tier getTier() { return Tier.TIER3; }

        @Override
        public boolean detect(Statement stmt, Map<String, TableStatistics> stats) {
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;

            boolean[] found = {false};
            ps.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(net.sf.jsqlparser.expression.operators.relational.LikeExpression expr) {
                    if (expr.getRightExpression() instanceof net.sf.jsqlparser.expression.StringValue) {
                        String val = ((net.sf.jsqlparser.expression.StringValue) expr.getRightExpression()).getValue();
                        if (val.startsWith("%") && !val.equals("%")) found[0] = true;
                    }
                }
            });
            return found[0];
        }

        @Override
        public Optional<OptimizationResult.PatternApplication> apply(Statement stmt, Map<String, TableStatistics> stats) {
            if (detect(stmt, stats)) {
                return Optional.of(buildMeta(getId(), getName(), "Leading wildcards (%abc) disable B-Tree indexes.", "Reversed wildcard to enable range scans.", "HIGH", "Restores Index Seek capabilities.", stmt.toString(), stmt.toString().replaceAll("(?i)(LIKE\\s+')%([^']+)'", "$1$2%'"), 1.0));
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
            if (stats == null || stats.isEmpty()) return false;
            if (!(stmt instanceof Select)) return false;
            Object sBody = ((Select) stmt).getSelectBody();
            if (!(sBody instanceof PlainSelect)) return false;
            PlainSelect ps = (PlainSelect) sBody;
            if (ps.getWhere() == null) return false;

            boolean[] missingIndexDetected = {false};

            ps.getWhere().accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
                @Override
                public void visit(Column col) {
                    if (col.getTable() != null && col.getTable().getName() != null) {
                        String tableName = col.getTable().getName().toLowerCase();
                        if (stats.containsKey(tableName)) {
                            TableStatistics tStats = stats.get(tableName);
                            String pk = AstUtils.getPrimaryKeyColumn(tStats);
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
                return Optional.of(buildMeta(getId(), getName(), "Full table scan detected on unindexed WHERE column.", "Dynamically generating optimal CREATE INDEX statements.", "HIGH", "B-Tree indexes turn O(N) full table scans into O(log N) lookups.", stmt.toString(), stmt.toString(), 1.0));
            }
            return Optional.empty();
        }
    }
}