package com.optimix.model;

import java.util.List;

public class OptimizationResult {

    public String originalQuery;
    public String optimizedQuery;
    public Double originalCost;
    public Double optimizedCost;
    public Double speedupFactor;

    public List<PatternApplication>   patternsApplied;
    public List<IndexRecommendation>  indexRecommendations;

    public ExecutionPlan originalPlan;
    public ExecutionPlan optimizedPlan;

    public String joinOrderExplanation;
    public String summary;

    public static class PatternApplication {
        public String patternId;
        public String patternName;
        public String tier;
        public String problem;
        public String transformation;
        public String beforeSnippet;
        public String afterSnippet;
        public String impactLevel;
        public String impactReason;
    }

    public static class IndexRecommendation {
        public String       tableName;
        public List<String> columns;
        public String       reason;
        public String       createStatement;
        public boolean      confirmed;
    }

    public static class ExecutionPlan {
        public PlanNode root;
        public String rawExplain;
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