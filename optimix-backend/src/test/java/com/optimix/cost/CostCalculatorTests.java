package com.optimix.cost;

import com.optimix.model.TableStatistics;
import com.optimix.optimizer.cost.CostCalculator;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CostCalculator Tests")
public class CostCalculatorTests {

    private CostCalculator calc;
    private TableStatistics large, small;

    @BeforeEach
    void setup() {
        calc = new CostCalculator();

        large = new TableStatistics();
        large.tableName  = "orders";
        large.rowCount   = 100_000;
        large.dataPages  = 1_000;

        small = new TableStatistics();
        small.tableName  = "customers";
        small.rowCount   = 1_000;
        small.dataPages  = 10;
    }

    @Test @DisplayName("table scan cost = pages*1.0 + rows*0.01")
    void tableScanFormula() {
        double cost = calc.tableScanCost(large);
        double expected = (1_000 * CostCalculator.SEQ_PAGE_COST)
                        + (100_000 * CostCalculator.CPU_TUPLE_COST);
        assertEquals(expected, cost, 0.001);
    }

    @Test @DisplayName("index scan is cheaper than table scan at 0.1% selectivity")
    void indexCheaperAtLowSelectivity() {
        TableStatistics.IndexStats idx = new TableStatistics.IndexStats();
        idx.indexName = "idx_id"; idx.height = 3;
        large.indexes = java.util.List.of(idx);

        double indexCost = calc.indexScanCost(large, "idx_id", 0.001);
        double tableCost = calc.tableScanCost(large);
        assertTrue(indexCost < tableCost,
            "Index scan should be cheaper at 0.1% selectivity. index=" + indexCost + " table=" + tableCost);
    }

    @Test @DisplayName("table scan is cheaper than index scan at 80% selectivity")
    void tableWinsAtHighSelectivity() {
        TableStatistics.IndexStats idx = new TableStatistics.IndexStats();
        idx.indexName = "idx_id"; idx.height = 3;
        large.indexes = java.util.List.of(idx);

        double indexCost = calc.indexScanCost(large, "idx_id", 0.80);
        double tableCost = calc.tableScanCost(large);
        assertTrue(tableCost < indexCost,
            "Table scan should be cheaper at 80% selectivity");
    }

    @Test @DisplayName("equality selectivity = 1 / distinct_values")
    void equalitySelectivity() {
        TableStatistics.ColumnStats col = new TableStatistics.ColumnStats();
        col.columnName     = "status";
        col.distinctValues = 5;
        large.columns = java.util.List.of(col);

        assertEquals(0.2, calc.equalitySelectivity(large, "status"), 0.001);
    }

    @Test @DisplayName("hash join cost scales with row counts")
    void hashJoinCostScales() {
        double small_small = calc.hashJoinCost(100,  10.0,  100,  10.0);
        double large_large = calc.hashJoinCost(10000, 1000.0, 10000, 1000.0);
        assertTrue(large_large > small_small);
    }

    @Test @DisplayName("sort cost is 0 for 0 or 1 rows")
    void sortCostEdgeCases() {
        assertEquals(0.0, calc.sortCost(0));
        assertEquals(0.0, calc.sortCost(1));
    }

    @Test @DisplayName("outputRows always returns at least 1")
    void outputRowsMinimumOne() {
        assertEquals(1L, calc.outputRows(1000, 0.0));
        assertEquals(1L, calc.outputRows(0,    0.5));
    }

    @Test @DisplayName("merge join with pre-sorted inputs cheaper than unsorted")
    void mergeJoinSortedCheaper() {
        double sorted   = calc.mergeJoinCost(10000, 1000, true,  10000, 1000, true);
        double unsorted = calc.mergeJoinCost(10000, 1000, false, 10000, 1000, false);
        assertTrue(sorted < unsorted, "Pre-sorted merge join should be cheaper");
    }
}
