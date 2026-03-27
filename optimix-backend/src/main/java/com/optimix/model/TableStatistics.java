package com.optimix.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Statistics about a single database table.
 * Collected from MySQL's information_schema by StatisticsCollector.
 * Used by the CostCalculator and all 40 optimization patterns.
 */
public class TableStatistics {

    public String tableName;
    public long   rowCount;
    public long   dataPages;   // data_length / 16384 (InnoDB page size)
    public long   indexPages;

    public List<ColumnStats> columns  = new ArrayList<>();
    public List<IndexStats>  indexes  = new ArrayList<>();

    // ── Column info ───────────────────────────────────────────────────────────

    public static class ColumnStats {
        public String  columnName;
        public String  dataType;
        public boolean isNullable;
        public boolean isUnique;
        public long    distinctValues;
        public String  keyType;        // "PRI", "UNI", "MUL", ""
    }

    // ── Index info ────────────────────────────────────────────────────────────

    public static class IndexStats {
        public String       indexName;
        public boolean      isUnique;
        public List<String> columns   = new ArrayList<>();
        public int          height    = 3;    // B-tree height estimate
        public long         cardinality;
    }

    /** Quick lookup: find a column by name (case-insensitive). */
    public ColumnStats findColumn(String name) {
        return columns.stream()
            .filter(c -> c.columnName.equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    /** Quick lookup: find an index by name (case-insensitive). */
    public IndexStats findIndex(String name) {
        return indexes.stream()
            .filter(i -> i.indexName.equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    /** Returns true if the column has any index (including composite). */
    public boolean isIndexed(String columnName) {
        return indexes.stream()
            .anyMatch(i -> i.columns.stream()
                .anyMatch(c -> c.equalsIgnoreCase(columnName)));
    }
}
