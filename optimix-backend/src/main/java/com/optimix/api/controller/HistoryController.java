package com.optimix.api.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.optimix.config.DatabaseConfig;
import com.optimix.model.dto.ErrorResponse;
import com.optimix.model.dto.MessageResponse;

import io.javalin.http.Context;

public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    public void listHistory(Context ctx) {
        try {
            Long userId = ctx.attribute("userId");

            try (Connection conn = DatabaseConfig.getConnection()) {
                // FIXED: Explicitly selecting the speedup and cost columns
                PreparedStatement ps = conn.prepareStatement("""
                    SELECT history_id, connection_id, original_query, optimized_query,
                           original_cost, optimized_cost, speedup_factor,
                           patterns_applied, index_recommendations, created_at
                    FROM query_history
                    WHERE user_id = ?
                    ORDER BY created_at DESC
                    LIMIT 100
                """);
                ps.setLong(1, userId);
                ResultSet rs = ps.executeQuery();

                List<Map<String, Object>> entries = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("historyId",            rs.getInt   ("history_id"));
                    entry.put("connectionId",         rs.getObject("connection_id"));
                    entry.put("originalQuery",        rs.getString("original_query"));
                    entry.put("optimizedQuery",       rs.getString("optimized_query"));
                    
                    // FIXED: Restoring the missing metrics to the JSON response
                    entry.put("originalCost",         rs.getDouble("original_cost"));
                    entry.put("optimizedCost",        rs.getDouble("optimized_cost"));
                    entry.put("speedupFactor",        rs.getDouble("speedup_factor"));
                    
                    entry.put("patternsApplied",      rs.getString("patterns_applied"));
                    entry.put("indexRecommendations", rs.getString("index_recommendations"));
                    entry.put("createdAt",            rs.getString("created_at"));
                    entries.add(entry);
                }
                ctx.status(200).json(entries);
            }

        } catch (Exception e) {
            log.error("List history error", e);
            ctx.status(500).json(new ErrorResponse("Failed to load history."));
        }
    }

    public void deleteHistory(Context ctx) {
        try {
            Long userId = ctx.attribute("userId");
            int  historyId = Integer.parseInt(ctx.pathParam("id"));

            try (Connection conn = DatabaseConfig.getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM query_history WHERE history_id = ? AND user_id = ?");
                ps.setInt (1, historyId);
                ps.setLong(2, userId);
                int deleted = ps.executeUpdate();

                if (deleted == 0) {
                    ctx.status(404).json(new ErrorResponse("History entry not found."));
                } else {
                    ctx.status(200).json(new MessageResponse("Deleted successfully."));
                }
            }

        } catch (NumberFormatException e) {
            ctx.status(400).json(new ErrorResponse("Invalid history ID."));
        } catch (Exception e) {
            log.error("Delete history error", e);
            ctx.status(500).json(new ErrorResponse("Failed to delete history entry."));
        }
    }
}