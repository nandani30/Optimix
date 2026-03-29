package com.optimix.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import com.optimix.optimizer.patterns.tier1.Tier1Patterns;
import com.optimix.optimizer.patterns.tier2.Tier2Patterns;
import com.optimix.optimizer.patterns.tier3.Tier3Patterns;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

public class OptimizationEngine {

    private static final Logger log = LoggerFactory.getLogger(OptimizationEngine.class);
    private final ExplainRunner explainRunner;

    public OptimizationEngine(ExplainRunner explainRunner) {
        this.explainRunner = explainRunner;
    }

    public OptimizationResult optimize(String sql, ExplainRunner.ConnectionProfile profile) {
        OptimizationResult result = new OptimizationResult();
        result.originalQuery = sql;
        List<OptimizationResult.PatternApplication> applied = new ArrayList<>();
        
        String optimizedSql = sql;
        boolean globalQueryChanged = false;

        try {
            Map<String, TableStatistics> stats = new HashMap<>(); // Using empty stats to compile safely
            double costBefore = calculateHeuristicCost(sql);
            result.originalCost = costBefore;

            // 🔴 Multi-Pass Fixpoint Iteration
            int maxPasses = 3;
            int currentPass = 0;
            boolean changedInPass = true;

            while (changedInPass && currentPass < maxPasses) {
                changedInPass = false;
                Statement stmt = CCJSqlParserUtil.parse(optimizedSql);
                
                if (stmt instanceof Select) {
                    Select select = (Select) stmt;

                    // --- Run All Tiers ---
                    changedInPass |= runTier(Tier1Patterns.all(), select, stats, applied);
                    changedInPass |= runTier(Tier2Patterns.all(), select, stats, applied);
                    changedInPass |= runTier(Tier3Patterns.all(), select, stats, applied);

                    if (changedInPass) {
                        optimizedSql = select.toString();
                        globalQueryChanged = true;
                    }
                }
                currentPass++;
            }

            OptimizationResult.ExecutionPlan optimizedPlan = null;
            if (profile != null && globalQueryChanged) {
                try {
                    optimizedPlan = explainRunner.runExplain(optimizedSql, profile);
                } catch (Exception e) {
                    log.warn("MySQL rejected the optimized query: {}. Reverting safely.", e.getMessage());
                    optimizedSql = sql; 
                    globalQueryChanged = false;
                    applied.clear();
                }
            }

            result.optimizedQuery = optimizedSql;
            result.patternsApplied = applied;

            if (globalQueryChanged) {
                double costAfter = costBefore;
                for (OptimizationResult.PatternApplication app : applied) {
                    if ("HIGH".equals(app.impactLevel)) costAfter *= 0.50;
                    else if ("MEDIUM".equals(app.impactLevel)) costAfter *= 0.80;
                    else costAfter *= 0.95;
                }
                result.optimizedCost = Math.max(1.0, costAfter);
                result.speedupFactor = costBefore / result.optimizedCost;
                result.summary = applied.size() + " optimization(s) found.\nQuery was successfully rewritten.";
                result.optimizedPlan = optimizedPlan;
            } else {
                result.optimizedCost = costBefore;
                result.speedupFactor = 1.0;
                result.summary = "No AST structural optimizations found. Query may already be optimal.";
            }

            if (profile != null) {
                result.originalPlan = explainRunner.runExplain(sql, profile);
            }

        } catch (Exception e) {
            log.error("Optimization failed", e);
            result.optimizedQuery = sql;
            result.summary = "Optimization aborted due to parser error.";
        }

        return result;
    }

    private boolean runTier(List<com.optimix.optimizer.patterns.OptimizationPattern> patterns, Select select, Map<String, TableStatistics> stats, List<OptimizationResult.PatternApplication> applied) {
        boolean changed = false;
        for (com.optimix.optimizer.patterns.OptimizationPattern pattern : patterns) {
            Optional<OptimizationResult.PatternApplication> app = pattern.apply(select, stats);
            if (app.isPresent()) {
                boolean alreadyApplied = applied.stream().anyMatch(a -> a.patternName.equals(app.get().patternName));
                if (!alreadyApplied) {
                    applied.add(app.get());
                }
                changed = true;
            }
        }
        return changed;
    }

    private double calculateHeuristicCost(String sql) {
        double cost = 100.0;
        String upper = sql.toUpperCase();
        if (upper.contains("JOIN")) cost *= 10;
        if (upper.contains("GROUP BY")) cost *= 5;
        if (upper.contains("ORDER BY")) cost *= 5;
        if (upper.contains("IN (SELECT")) cost *= 20;
        if (upper.contains("EXISTS")) cost *= 15;
        if (upper.contains("LIKE '%")) cost *= 50; 
        return cost;
    }
}