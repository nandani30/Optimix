package com.optimix.api.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optimix.config.DatabaseConfig;
import com.optimix.model.OptimizationResult;
import com.optimix.model.dto.ErrorResponse;
import com.optimix.model.dto.OptimizeRequest;
import com.optimix.optimizer.OptimizationEngine;

import io.javalin.http.Context;

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

            // Persist to history asynchronously
            saveToHistory(userId, req.connectionId, result);

            ctx.status(200).json(result);

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Optimization error", e);

            String message = e.getMessage();
            if (message != null && message.contains("SQL")) {
                ctx.status(400).json(new ErrorResponse(message));
            } else {
                ctx.status(500).json(new ErrorResponse("Optimization failed. Please try again."));
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
        // Professional Guard: Do not save invalid queries (e.g. syntax errors or missing tables) to history
        if (connectionId != null && result.originalPlan == null) {
            log.info("Skipping history save: Query was rejected by MySQL (likely invalid syntax or missing table).");
            return;
        }

        try (Connection conn = DatabaseConfig.getConnection()) {
            // FIXED: Removed hardcoded 0s and correctly mapped real speedup and cost metrics to the database
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO query_history
                    (user_id, connection_id, original_query, optimized_query,
                     original_cost, optimized_cost, speedup_factor,
                     patterns_applied, index_recommendations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """);
            
            ps.setLong(1, userId);
            if (connectionId != null) ps.setInt(2, connectionId);
            else ps.setNull(2, Types.INTEGER);
            
            ps.setString(3, result.originalQuery);
            ps.setString(4, result.optimizedQuery);
            
            // Injecting the actual mathematical performance metrics
            ps.setDouble(5, result.originalCost);
            ps.setDouble(6, result.optimizedCost);
            ps.setDouble(7, result.speedupFactor);

            List<String> names = result.patternsApplied.stream().map(p -> p.patternName).collect(Collectors.toList());
            ps.setString(8, mapper.writeValueAsString(names));
            ps.setString(9, mapper.writeValueAsString(result.indexRecommendations));
            
            ps.executeUpdate();

        } catch (Exception e) {
            log.warn("Could not save to history: {}", e.getMessage());
        }
    }
}