package com.optimix.optimizer.patterns;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;
import net.sf.jsqlparser.statement.Statement;

import java.util.Map;
import java.util.Optional;

/**
 * Base interface for all 40 SQL optimization patterns.
 *
 * Each pattern implements two methods:
 *   detect() — fast check: does this pattern apply to this query?
 *   apply()  — mutates the AST and returns a description of what changed
 *
 * Patterns are stateless. All context is passed as method parameters.
 * The OptimizationEngine iterates all patterns in PatternRegistry order.
 *
 * IMPORTANT: estimatedSpeedup (fake multipliers) has been removed.
 * Patterns now report qualitative impact: LOW | MEDIUM | HIGH, with a reason.
 */
public interface OptimizationPattern {

    /** Unique identifier, e.g. "P01_CORRELATED_SUBQUERY" */
    String getId();

    /** Human-readable name shown in the UI results panel */
    String getName();

    /** Which tier this pattern belongs to */
    Tier getTier();

    /**
     * Returns true if this pattern is applicable to the given SQL statement.
     * Should be fast — called for every pattern on every query.
     * Do NOT mutate the statement in detect().
     */
    boolean detect(Statement statement, Map<String, TableStatistics> stats);

    /**
     * Apply the optimization transformation.
     * May mutate the AST (statement) in-place.
     *
     * @return PatternApplication describing what changed, or empty if nothing to do.
     */
    Optional<OptimizationResult.PatternApplication> apply(
            Statement statement, Map<String, TableStatistics> stats);

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Build a PatternApplication with qualitative impact level.
     *
     * @param problem        What is wrong in the current query
     * @param transformation What rewrite was applied / should be applied
     * @param impactLevel    "LOW" | "MEDIUM" | "HIGH"
     * @param impactReason   One sentence explaining why the impact is rated this way
     * @param before         SQL snippet showing the problem pattern
     * @param after          SQL snippet showing the rewritten form
     */
    default OptimizationResult.PatternApplication buildApplication(
            String problem, String transformation,
            String impactLevel, String impactReason,
            String before, String after) {

        OptimizationResult.PatternApplication pa = new OptimizationResult.PatternApplication();
        pa.patternId      = getId();
        pa.patternName    = getName();
        pa.tier           = getTier().name();
        pa.problem        = problem;
        pa.transformation = transformation;
        pa.impactLevel    = impactLevel;
        pa.impactReason   = impactReason;
        pa.beforeSnippet  = before;
        pa.afterSnippet   = after;
        return pa;
    }

    enum Tier {
        TIER1("High Complexity — advanced algorithms, semantic analysis"),
        TIER2("Medium Complexity — structural transformations"),
        TIER3("Low Complexity — quick wins, algebraic simplifications");

        public final String description;
        Tier(String description) { this.description = description; }
    }
}
