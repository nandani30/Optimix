package com.optimix.optimizer.join;

import com.optimix.model.TableStatistics;
import com.optimix.optimizer.cost.CostCalculator;

import java.util.*;

/**
 * Selinger Dynamic Programming Join Optimizer.
 *
 * ── The problem ────────────────────────────────────────────────────────────
 * Joining N tables has N! possible orderings.
 *   5 tables  →     120 orderings
 *   10 tables →  3,628,800 orderings
 * Testing all is computationally infeasible.
 *
 * ── The solution ───────────────────────────────────────────────────────────
 * Dynamic Programming exploits optimal substructure:
 *   Best plan for {A,B,C} = min over:
 *     best({A,B}) ⋈ C
 *     best({A,C}) ⋈ B
 *     best({B,C}) ⋈ A
 *
 * Each subproblem is solved once and memoized.
 * Complexity: O(3^N) instead of O(N!)
 *   10 tables: 59,049 subproblems vs 3,628,800 brute force orderings
 *
 * ── Algorithm steps ────────────────────────────────────────────────────────
 *  1. Base: for each table, compute cheapest access path (scan or index)
 *  2. Size 2: for each pair, try all join algorithms, pick cheapest
 *  3. Size 3 to N: for each subset S, split S = (S \ {t}) ⋈ t for each t in S
 *  4. Answer: memo[full set] = globally optimal plan
 */
public class JoinOptimizer {

    private final CostCalculator              costCalc;
    private final Map<String, TableStatistics> statsMap;

    public JoinOptimizer(CostCalculator costCalc, Map<String, TableStatistics> statsMap) {
        this.costCalc = costCalc;
        this.statsMap = statsMap;
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Find the optimal join order for a list of tables.
     *
     * @param tables  Table names to join (order in the list doesn't matter)
     * @return        JoinPlan with minimum estimated cost
     */
    public JoinPlan findOptimalOrder(List<String> tables) {
        if (tables == null || tables.isEmpty())
            throw new IllegalArgumentException("No tables provided.");
        if (tables.size() == 1)
            return leafPlan(tables.get(0));

        // memo: bitmask of table set → cheapest JoinPlan for that set
        int n = tables.size();
        Map<Integer, JoinPlan> memo = new HashMap<>();

        // Step 1 — Base case: single tables
        for (int i = 0; i < n; i++) {
            int mask = 1 << i;
            memo.put(mask, leafPlan(tables.get(i)));
        }

        // Step 2+ — Build up subsets by increasing size
        for (int size = 2; size <= n; size++) {
            for (int mask : subsetsOfSize(n, size)) {
                JoinPlan best = null;

                // Try removing each table from the set as the "right" side
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) == 0) continue;  // table i not in this subset

                    int leftMask  = mask ^ (1 << i);       // mask without table i
                    int rightMask = 1 << i;                 // just table i

                    JoinPlan left  = memo.get(leftMask);
                    JoinPlan right = memo.get(rightMask);
                    if (left == null || right == null) continue;

                    // Try each join algorithm and pick the cheapest
                    for (JoinAlgorithm algo : JoinAlgorithm.values()) {
                        double cost = computeJoinCost(left, right, algo);
                        if (best == null || cost < best.totalCost) {
                            best = new JoinPlan(left, right, algo, cost, buildTableSet(tables, mask));
                        }
                    }
                }

                if (best != null) memo.put(mask, best);
            }
        }

        // Full set bitmask = all 1s for n tables
        int fullMask = (1 << n) - 1;
        JoinPlan result = memo.get(fullMask);
        return result != null ? result : leafPlan(tables.get(0));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private JoinPlan leafPlan(String tableName) {
        TableStatistics stats = statsMap.getOrDefault(
            tableName.toLowerCase(), CostCalculator.defaultStats(tableName));
        double cost = costCalc.tableScanCost(stats);
        return new JoinPlan(tableName, cost, stats.rowCount);
    }

    private double computeJoinCost(JoinPlan left, JoinPlan right, JoinAlgorithm algo) {
        return switch (algo) {
            case NESTED_LOOP -> costCalc.nestedLoopJoinCost(
                left.totalCost, left.estimatedRows,
                right.totalCost / Math.max(1, right.estimatedRows));

            case HASH_JOIN   -> costCalc.hashJoinCost(
                right.estimatedRows, right.totalCost,
                left.estimatedRows,  left.totalCost);

            case MERGE_JOIN  -> costCalc.mergeJoinCost(
                left.estimatedRows,  left.totalCost,  false,
                right.estimatedRows, right.totalCost, false);
        };
    }

    /** Generate all bitmasks of exactly 'size' bits set, within n bits. */
    private List<Integer> subsetsOfSize(int n, int size) {
        List<Integer> result = new ArrayList<>();
        for (int mask = 0; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) == size) result.add(mask);
        }
        return result;
    }

    private Set<String> buildTableSet(List<String> tables, int mask) {
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < tables.size(); i++) {
            if ((mask & (1 << i)) != 0) set.add(tables.get(i));
        }
        return set;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public types
    // ════════════════════════════════════════════════════════════════════════

    public enum JoinAlgorithm {
        NESTED_LOOP,
        HASH_JOIN,
        MERGE_JOIN
    }

    public static class JoinPlan {

        // Leaf node fields
        public final String singleTable;   // null for internal nodes

        // Internal node fields
        public final JoinPlan     left;
        public final JoinPlan     right;
        public final JoinAlgorithm algorithm;

        // Common fields
        public final double      totalCost;
        public final long        estimatedRows;
        public final Set<String> tables;

        /** Leaf constructor (single table). */
        JoinPlan(String table, double cost, long rows) {
            this.singleTable   = table;
            this.left          = null;
            this.right         = null;
            this.algorithm     = null;
            this.totalCost     = cost;
            this.estimatedRows = rows;
            this.tables        = Set.of(table);
        }

        /** Internal node constructor (join of two subplans). */
        JoinPlan(JoinPlan left, JoinPlan right, JoinAlgorithm algo, double cost, Set<String> tables) {
            this.singleTable   = null;
            this.left          = left;
            this.right         = right;
            this.algorithm     = algo;
            this.totalCost     = cost;
            this.estimatedRows = estimateOutputRows(left.estimatedRows, right.estimatedRows);
            this.tables        = Collections.unmodifiableSet(tables);
        }

        public boolean isLeaf() {
            return singleTable != null;
        }

        /**
         * Human-readable join order.
         * Example: "(customers ⋈[HASH] orders) ⋈[HASH] order_items"
         */
        public String toReadableString() {
            if (isLeaf()) return singleTable;
            // Note: algorithm is the optimizer's internal cost-model choice, not a MySQL execution hint.
            // The actual join algorithm is selected by MySQL at runtime.
            return String.format("(%s ⋈ %s)",
                left.toReadableString(),
                right.toReadableString());
        }

        /**
         * Simple cardinality estimate for a join:
         * sqrt(left × right) × join_selectivity
         * (selectivity ≈ 10% — assumes most joins reduce cardinality)
         */
        private static long estimateOutputRows(long l, long r) {
            return Math.max(1L, Math.round(Math.sqrt((double) l * r) * 0.1));
        }
    }
}
