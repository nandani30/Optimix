package com.optimix.api.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optimix.config.DatabaseConfig;
import com.optimix.model.OptimizationResult;
import com.optimix.model.dto.ErrorResponse;
import com.optimix.model.dto.OptimizeRequest;
import com.optimix.optimizer.OptimizationEngine;

import io.javalin.http.Context;

/**
 * Handles query optimization requests.
 *
 * POST /api/optimize  → full optimization (rewrites query, shows all patterns)
 * POST /api/analyze   → dry-run analysis only (issues detected, no rewrite)
 */
public class OptimizeController {

    private static final Logger           log    = LoggerFactory.getLogger(OptimizeController.class);
    private static final OptimizationEngine engine = new OptimizationEngine();
    private static final ObjectMapper     mapper = new ObjectMapper();

    public void optimize(Context ctx) {
        try {
            OptimizeRequest req = ctx.bodyAsClass(OptimizeRequest.class);

            if (req.query == null || req.query.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Query cannot be empty.")); return;
            }

            Long userId = ctx.attribute("userId");
            OptimizationResult result = engine.optimize(req.query.trim(), req.connectionId, userId);

            // Persist to history asynchronously (best-effort, non-fatal)
            saveToHistory(userId, req.connectionId, result);

            ctx.status(200).json(result);

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Optimization error", e);

            String message = e.getMessage();

            // Show real SQL error (like MySQL)
            if (message != null && message.contains("SQL")) {
                ctx.status(400).json(new ErrorResponse(message));
            } else {
                ctx.status(500).json(new ErrorResponse(
                    "Optimization failed. Please try again."));
            }
        }
    }

    public void analyze(Context ctx) {
        try {
            OptimizeRequest req = ctx.bodyAsClass(OptimizeRequest.class);

            if (req.query == null || req.query.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Query cannot be empty.")); return;
            }

            Long userId = ctx.attribute("userId");
            Map<String, Object> analysis = engine.analyze(req.query.trim(), req.connectionId, userId);
            ctx.status(200).json(analysis);

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Analysis error", e);
            ctx.status(500).json(new ErrorResponse("Analysis failed. Please try again."));
        }
    }

    private void saveToHistory(long userId, Integer connectionId, OptimizationResult result) {
        try (Connection conn = DatabaseConfig.getConnection()) {
            // Note: original_cost / optimized_cost / speedup_factor columns kept in schema
            // for backwards compatibility but stored as 0 — these were fabricated values.
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO query_history
                    (user_id, connection_id, original_query, optimized_query,
                     original_cost, optimized_cost, speedup_factor,
                     patterns_applied, index_recommendations)
                VALUES (?, ?, ?, ?, 0, 0, 0, ?, ?)
            """);
            ps.setLong  (1, userId);
            if (connectionId != null) ps.setInt(2, connectionId);
            else ps.setNull(2, Types.INTEGER);
            ps.setString(3, result.originalQuery);
            ps.setString(4, result.optimizedQuery);

            List<String> names = result.patternsApplied.stream()
                .map(p -> p.patternName).toList();
            ps.setString(5, mapper.writeValueAsString(names));
            ps.setString(6, mapper.writeValueAsString(result.indexRecommendations));
            ps.executeUpdate();

        } catch (Exception e) {
            log.warn("Could not save to history: {}", e.getMessage());
        }
    }
}
