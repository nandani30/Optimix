package com.optimix.model;

import java.util.List;

/**
 * The complete result returned by the optimization engine.
 * Serialized to JSON and sent to the frontend.
 *
 * Cost numbers (originalCost, optimizedCost, speedupFactor) have been removed.
 * They were fabricated multipliers with no grounding in real DB measurements.
 * Impact is now expressed qualitatively: LOW | MEDIUM | HIGH, with a reason.
 */
public class OptimizationResult {

    public String originalQuery;
    public String optimizedQuery;

    public List<PatternApplication>   patternsApplied;
    public List<IndexRecommendation>  indexRecommendations;

    /** Populated from real MySQL EXPLAIN when a connection is available. */
    public ExecutionPlan originalPlan;

    /** Populated from EXPLAIN of the rewritten query, when applicable. */
    public ExecutionPlan optimizedPlan;

    /** Join order explanation derived from EXPLAIN row estimates, not invented. */
    public String joinOrderExplanation;

    /** One-line human summary. */
    public String summary;

    // ── Nested types ──────────────────────────────────────────────────────────

    public static class PatternApplication {
        public String patternId;
        public String patternName;
        public String tier;
        public String problem;
        public String transformation;
        public String beforeSnippet;
        public String afterSnippet;

        /** LOW | MEDIUM | HIGH */
        public String impactLevel;

        /** Why this impact level — e.g. "HIGH because correlated subquery runs once per row." */
        public String impactReason;
    }

    public static class IndexRecommendation {
        public String       tableName;
        public List<String> columns;
        public String       reason;
        public String       createStatement;
        /** True when SHOW INDEX confirmed the index does NOT already exist. */
        public boolean      confirmed;
    }

    public static class ExecutionPlan {
        public PlanNode root;
    }

    /**
     * One node in the execution plan tree.
     * ALL fields come directly from MySQL EXPLAIN output — nothing is invented.
     */
    public static class PlanNode {
        /** EXPLAIN 'type': ALL, index, range, ref, eq_ref, const, … */
        public String accessType;

        /** EXPLAIN 'table' */
        public String table;

        /** EXPLAIN 'key' — index used, or null */
        public String keyUsed;

        /** EXPLAIN 'rows' — optimizer row estimate */
        public Long   rowEstimate;

        /** EXPLAIN 'Extra' — "Using filesort", "Using temporary", etc. */
        public String extra;

        /** EXPLAIN 'possible_keys' */
        public String possibleKeys;

        /** EXPLAIN 'ref' */
        public String ref;

        public List<PlanNode> children;
    }
}
