package com.optimix.join;

import com.optimix.model.TableStatistics;
import com.optimix.optimizer.cost.CostCalculator;
import com.optimix.optimizer.join.JoinOptimizer;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JoinOptimizer (Selinger DP) Tests")
public class JoinOptimizerTests {

    private JoinOptimizer optimizer;

    @BeforeEach
    void setup() {
        Map<String, TableStatistics> stats = new HashMap<>();
        stats.put("tiny",   makeStats("tiny",   100,     1));
        stats.put("small",  makeStats("small",  1_000,   10));
        stats.put("medium", makeStats("medium", 50_000,  500));
        stats.put("large",  makeStats("large",  500_000, 5_000));
        optimizer = new JoinOptimizer(new CostCalculator(), stats);
    }

    @Test @DisplayName("single table returns leaf plan")
    void singleTableReturnsLeaf() {
        JoinOptimizer.JoinPlan plan = optimizer.findOptimalOrder(List.of("tiny"));
        assertTrue(plan.isLeaf());
        assertEquals("tiny", plan.singleTable);
    }

    @Test @DisplayName("two tables returns internal join node")
    void twoTablesReturnsJoin() {
        JoinOptimizer.JoinPlan plan = optimizer.findOptimalOrder(List.of("small", "large"));
        assertFalse(plan.isLeaf());
        assertNotNull(plan.algorithm);
        assertEquals(2, plan.tables.size());
    }

    @Test @DisplayName("four-table DP completes under 200ms")
    void fourTablesPerformance() {
        long start = System.currentTimeMillis();
        JoinOptimizer.JoinPlan plan = optimizer.findOptimalOrder(
            List.of("tiny", "small", "medium", "large"));
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(plan);
        assertEquals(4, plan.tables.size());
        assertTrue(elapsed < 200, "DP should finish in under 200ms, took " + elapsed + "ms");
    }

    @Test @DisplayName("empty table list throws")
    void emptyListThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> optimizer.findOptimalOrder(List.of()));
    }

    @Test @DisplayName("toReadableString contains all table names")
    void readableStringContainsAllTables() {
        JoinOptimizer.JoinPlan plan = optimizer.findOptimalOrder(
            List.of("tiny", "small", "medium"));
        String s = plan.toReadableString();
        assertTrue(s.contains("tiny"));
        assertTrue(s.contains("small"));
        assertTrue(s.contains("medium"));
        assertTrue(s.contains("⋈"));
    }

    @Test @DisplayName("unknown table uses fallback stats (no exception)")
    void unknownTableFallback() {
        assertDoesNotThrow(() -> optimizer.findOptimalOrder(
            List.of("known_table_not_in_stats", "small")));
    }

    @Test @DisplayName("estimated rows is always at least 1")
    void estimatedRowsPositive() {
        JoinOptimizer.JoinPlan plan = optimizer.findOptimalOrder(List.of("tiny", "small"));
        assertTrue(plan.estimatedRows >= 1);
    }

    private TableStatistics makeStats(String name, long rows, long pages) {
        TableStatistics s = new TableStatistics();
        s.tableName = name; s.rowCount = rows; s.dataPages = pages;
        s.columns = List.of(); s.indexes = List.of();
        return s;
    }
}
