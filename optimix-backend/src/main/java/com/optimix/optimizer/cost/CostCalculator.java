package com.optimix.optimizer.cost;

import com.optimix.model.TableStatistics;

/**
 * Cost Calculator — assigns numeric cost estimates to query execution strategies.
 *
 * Cost units are dimensionless; only relative comparisons matter.
 * Based on PostgreSQL-style cost model with MySQL-specific tweaks.
 *
 * ── I/O cost constants ─────────────────────────────────────────────────────
 *   Sequential page read = 1.0  (data read in order, disk head barely moves)
 *   Random page read     = 4.0  (index pointer → jump to scattered data pages)
 *
 * ── CPU cost constants ─────────────────────────────────────────────────────
 *   Row processing = 0.01  per row  (evaluate WHERE clause per row)
 *   Hash operation = 0.005 per row  (hash join build/probe phase)
 *   Comparison     = 0.001 per op   (sorting, merging)
 */
public class CostCalculator {

    public static final double SEQ_PAGE_COST    = 1.0;
    public static final double RANDOM_PAGE_COST = 4.0;
    public static final double CPU_TUPLE_COST   = 0.01;
    public static final double CPU_HASH_COST    = 0.005;
    public static final double CPU_OP_COST      = 0.001;

    // Default selectivity estimates when we have no histogram
    public static final double EQUALITY_DEFAULT_SEL = 0.05;   // 5%
    public static final double RANGE_SELECTIVITY    = 0.33;   // 33%
    public static final double LIKE_PREFIX_SEL      = 0.01;   // 1%
    public static final double LIKE_WILDCARD_SEL    = 0.10;   // 10%

    // ── Access path costs ─────────────────────────────────────────────────────

    /**
     * Full sequential table scan.
     * Formula: (pages × SEQ_PAGE_COST) + (rows × CPU_TUPLE_COST)
     */
    public double tableScanCost(TableStatistics stats) {
        return (stats.dataPages * SEQ_PAGE_COST)
             + (stats.rowCount  * CPU_TUPLE_COST);
    }

    /**
     * B-tree index scan with given selectivity.
     * Formula:
     *   (index_height × RANDOM_PAGE_COST)           ← traverse B-tree
     *   + (selectivity × pages × RANDOM_PAGE_COST)  ← random heap fetches
     *   + (selectivity × rows  × CPU_TUPLE_COST)    ← CPU per matched row
     */
    public double indexScanCost(TableStatistics stats, String indexName, double selectivity) {
        TableStatistics.IndexStats idx = stats.findIndex(indexName);
        int height = (idx != null) ? Math.max(1, idx.height) : 3; // default B-tree height

        return (height                              * RANDOM_PAGE_COST)
             + (selectivity * stats.dataPages       * RANDOM_PAGE_COST)
             + (selectivity * stats.rowCount        * CPU_TUPLE_COST);
    }

    /**
     * Index-only scan (covering index — no heap fetch needed).
     * Cheaper than regular index scan because we never touch data pages.
     */
    public double indexOnlyScanCost(TableStatistics stats, String indexName, double selectivity) {
        TableStatistics.IndexStats idx = stats.findIndex(indexName);
        int height = (idx != null) ? idx.height : 3;

        return (height                    * RANDOM_PAGE_COST)
             + (selectivity * stats.rowCount * CPU_TUPLE_COST);
    }

    // ── Selectivity estimation ─────────────────────────────────────────────────

    /**
     * Selectivity for equality predicate: WHERE col = value
     * = 1 / distinct_values   (assumes uniform distribution)
     */
    public double equalitySelectivity(TableStatistics stats, String columnName) {
        TableStatistics.ColumnStats col = stats.findColumn(columnName);
        if (col == null || col.distinctValues <= 0) return EQUALITY_DEFAULT_SEL;
        return 1.0 / col.distinctValues;
    }

    /**
     * Selectivity for range predicate: WHERE col > x
     * Uses default heuristic of 1/3 rows.
     */
    public double rangeSelectivity(TableStatistics stats, String columnName) {
        return RANGE_SELECTIVITY; // Can be improved with histogram data
    }

    // ── Join costs ────────────────────────────────────────────────────────────

    /**
     * Nested Loop Join.
     * Formula: outer_cost + (outer_rows × inner_cost_per_row)
     *
     * Best when: inner table is small OR inner side has an index.
     * Worst when: both sides are large (quadratic behaviour).
     */
    public double nestedLoopJoinCost(double outerCost, long outerRows, double innerCostPerRow) {
        return outerCost + (outerRows * innerCostPerRow);
    }

    /**
     * Hash Join.
     * Build phase: hash the smaller (inner) table into memory.
     * Probe phase: scan outer table, probe the hash map.
     *
     * Best when: inner table fits in memory; neither side is sorted.
     */
    public double hashJoinCost(long innerRows, double innerCost,
                                long outerRows, double outerCost) {
        double buildCost = innerCost + (innerRows * CPU_HASH_COST);
        double probeCost = outerCost + (outerRows * CPU_HASH_COST);
        return buildCost + probeCost;
    }

    /**
     * Merge Join.
     * Both inputs must be sorted. If already sorted by an index, sort cost = 0.
     *
     * Best when: both inputs are already sorted (e.g. via index).
     */
    public double mergeJoinCost(long outerRows, double outerCost, boolean outerSorted,
                                 long innerRows, double innerCost, boolean innerSorted) {
        double sortOuter = outerSorted ? 0 : sortCost(outerRows);
        double sortInner = innerSorted ? 0 : sortCost(innerRows);
        double mergeScan = (outerRows + innerRows) * CPU_OP_COST;
        return outerCost + innerCost + sortOuter + sortInner + mergeScan;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Estimate cost of an O(N log N) sort operation.
     */
    public double sortCost(long rows) {
        if (rows <= 1) return 0;
        return rows * (Math.log(rows) / Math.log(2)) * CPU_OP_COST;
    }

    /**
     * Estimate output rows after applying a selectivity filter.
     * Always returns at least 1 (avoid zero-row estimates).
     */
    public long outputRows(long inputRows, double selectivity) {
        return Math.max(1L, Math.round(inputRows * selectivity));
    }

    /**
     * Build a default stats object for tables we don't have info on.
     * Used as fallback when MySQL connection is unavailable.
     */
    public static TableStatistics defaultStats(String tableName) {
        TableStatistics s = new TableStatistics();
        s.tableName = tableName;
        s.rowCount  = 10_000;   // conservative 10K row estimate
        s.dataPages = 100;
        return s;
    }
}
