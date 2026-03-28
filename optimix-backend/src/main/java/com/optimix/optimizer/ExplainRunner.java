package com.optimix.optimizer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.optimix.model.OptimizationResult;
import com.optimix.model.TableStatistics;

public class ExplainRunner {

    private static final Logger log = LoggerFactory.getLogger(ExplainRunner.class);

    public OptimizationResult.ExecutionPlan runExplain(String sql, ConnectionProfile profile) throws Exception {
        if (profile == null || sql == null) return null;

        String url = buildJdbcUrl(profile);
        try (Connection conn = DriverManager.getConnection(url, profile.username, profile.password)) {
            return doExplain(conn, sql);
        } catch (SQLException e) {
            throw new Exception("MySQL Error: " + e.getMessage());
        }
    }

    public List<TableStatistics.IndexStats> runShowIndex(String tableName, ConnectionProfile profile) {
        if (profile == null || tableName == null) return List.of();

        String url = buildJdbcUrl(profile);
        List<TableStatistics.IndexStats> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, profile.username, profile.password);
             PreparedStatement ps = conn.prepareStatement("SHOW INDEX FROM `" + tableName.replace("`", "") + "`")) {

            Map<String, TableStatistics.IndexStats> indexMap = new LinkedHashMap<>();
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String  indexName   = rs.getString("Key_name");
                boolean nonUnique   = rs.getInt("Non_unique") != 0;
                String  colName     = rs.getString("Column_name");
                long    cardinality = rs.getLong("Cardinality");

                if (!indexMap.containsKey(indexName)) {
                    TableStatistics.IndexStats idx = new TableStatistics.IndexStats();
                    idx.indexName   = indexName;
                    idx.isUnique    = !nonUnique;
                    idx.columns     = new ArrayList<>();
                    idx.cardinality = cardinality;
                    idx.height      = 3; 
                    indexMap.put(indexName, idx);
                }
                indexMap.get(indexName).columns.add(colName);
            }
            result.addAll(indexMap.values());

        } catch (Exception e) {
            log.warn("SHOW INDEX FROM {} failed: {}", tableName, e.getMessage());
        }
        return result;
    }

    private OptimizationResult.ExecutionPlan doExplain(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + sql)) {
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            
            StringBuilder raw = new StringBuilder();
            
            for (int i = 1; i <= colCount; i++) {
                raw.append(String.format("%-15s", meta.getColumnName(i)));
            }
            raw.append("\n");

            List<OptimizationResult.PlanNode> nodes = new ArrayList<>();

            while (rs.next()) {
                for (int i = 1; i <= colCount; i++) {
                    String val = rs.getString(i);
                    raw.append(String.format("%-15s", val != null ? val : "NULL"));
                }
                raw.append("\n");

                OptimizationResult.PlanNode node = new OptimizationResult.PlanNode();
                node.table        = safeString(rs, "table");
                node.accessType   = safeString(rs, "type");
                node.keyUsed      = safeString(rs, "key");
                node.possibleKeys = safeString(rs, "possible_keys");
                node.ref          = safeString(rs, "ref");
                node.extra        = safeString(rs, "Extra");
                node.children     = List.of();

                String rowsStr = safeString(rs, "rows");
                if (rowsStr != null) {
                    try { node.rowEstimate = Long.parseLong(rowsStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }

                nodes.add(node);
            }

            if (nodes.isEmpty()) return null;

            OptimizationResult.PlanNode root = nodes.get(0);
            for (int i = 1; i < nodes.size(); i++) {
                OptimizationResult.PlanNode join = new OptimizationResult.PlanNode();
                join.table      = null;
                join.accessType = "JOIN";
                join.children   = List.of(root, nodes.get(i));
                root = join;
            }

            OptimizationResult.ExecutionPlan plan = new OptimizationResult.ExecutionPlan();
            plan.root = root;
            plan.rawExplain = raw.toString();
            return plan;
        }
    }

    private String safeString(ResultSet rs, String col) {
        try { return rs.getString(col); } catch (SQLException e) { return null; }
    }

    private String buildJdbcUrl(ConnectionProfile p) {
        return String.format(
            "jdbc:mysql://%s:%d/%s?connectTimeout=10000&useSSL=false&allowPublicKeyRetrieval=true",
            p.host, p.port, p.databaseName);
    }

    public static class ConnectionProfile {
        public String host, databaseName, username, password;
        public int    port;
    }
}