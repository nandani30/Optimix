package com.optimix.model;

import java.util.List;

/**
 * The complete result returned by the optimization engine.
 * Serialized to JSON and sent to the frontend.
 */
public class OptimizationResult {

    public String originalQuery;
    public String optimizedQuery;

    // --- CBO Cost Fields required by the Frontend ---
    public Double originalCost;
    public Double optimizedCost;
    public Double speedupFactor;

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

    public static class PlanNode {
        public String accessType;
        public String table;
        public String keyUsed;
        public Long   rowEstimate;
        public String extra;
        public String possibleKeys;
        public String ref;
        public List<PlanNode> children;
    }
}