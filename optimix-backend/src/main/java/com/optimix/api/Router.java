package com.optimix.api;

import com.optimix.api.controller.AuthController;
import com.optimix.api.controller.ConnectionController;
import com.optimix.api.controller.HistoryController;
import com.optimix.api.controller.OptimizeController;
import com.optimix.api.middleware.AuthMiddleware;
import com.optimix.api.middleware.RateLimitMiddleware;
import io.javalin.Javalin;

/**
 * Centralized route registration.
 *
 * Route table:
 *
 *  PUBLIC (no auth):
 *    POST /api/auth/signup        → start email signup, send OTP
 *    POST /api/auth/verify-otp    → verify OTP, create account
 *    POST /api/auth/resend-otp    → resend OTP
 *    POST /api/auth/google        → Google OAuth login/signup
 *    POST /api/auth/login         → email+password login
 *    POST /api/auth/logout        → client-side logout (stateless)
 *    GET  /health                 → health check
 *
 *  PROTECTED (requires Bearer token):
 *    GET  /api/auth/me            → current user info
 *    POST /api/connections/test   → test MySQL connection (not saved)
 *    POST /api/connections        → save connection profile
 *    GET  /api/connections        → list saved connections
 *    DELETE /api/connections/:id  → delete connection
 *    GET  /api/connections/:id/test → test saved connection
 *    POST /api/optimize           → optimize a query
 *    POST /api/analyze            → analyze without rewriting
 *    GET  /api/history            → list optimization history
 *    DELETE /api/history/:id      → delete history entry
 */
public class Router {

    public static void register(Javalin app) {

        // ── Controllers ────────────────────────────────────────────────────────
        AuthController       auth    = new AuthController();
        ConnectionController conn    = new ConnectionController();
        OptimizeController   opt     = new OptimizeController();
        HistoryController    history = new HistoryController();

        // ── Rate limiting on auth endpoints ────────────────────────────────────
        app.before("/api/auth/*", RateLimitMiddleware::checkAuthRate);

        // ── Auth guard on protected endpoints ──────────────────────────────────
        app.before("/api/auth/me",            AuthMiddleware::requireAuth);
        app.before("/api/connections",        AuthMiddleware::requireAuth);
        app.before("/api/connections/*",      AuthMiddleware::requireAuth);
        app.before("/api/optimize",           AuthMiddleware::requireAuth);
        app.before("/api/analyze",            AuthMiddleware::requireAuth);
        app.before("/api/history",            AuthMiddleware::requireAuth);
        app.before("/api/history/*",          AuthMiddleware::requireAuth);

        // ── Auth routes ────────────────────────────────────────────────────────
        app.post("/api/auth/signup",          auth::signup);
        app.post("/api/auth/verify-otp",      auth::verifyOtp);
        app.post("/api/auth/resend-otp",      auth::resendOtp);
        app.post("/api/auth/google",          auth::googleLogin);
        app.post("/api/auth/login",           auth::login);
        app.post("/api/auth/logout",          auth::logout);
        app.post("/api/auth/forgot-password",  auth::forgotPassword);
        app.post("/api/auth/reset-password",   auth::resetPassword);
        app.get ("/api/auth/me",              auth::me);

        // ── Connection routes ──────────────────────────────────────────────────
        app.post  ("/api/connections/test",    conn::testConnection);
        app.post  ("/api/connections",         conn::saveConnection);
        app.get   ("/api/connections",         conn::listConnections);
        app.delete("/api/connections/{id}",    conn::deleteConnection);
        app.get   ("/api/connections/{id}/test", conn::testSavedConnection);

        // ── Optimization routes ────────────────────────────────────────────────
        app.post("/api/optimize",             opt::optimize);
        app.post("/api/analyze",              opt::analyze);

        // ── History routes ─────────────────────────────────────────────────────
        app.get   ("/api/history",            history::listHistory);
        app.delete("/api/history/{id}",       history::deleteHistory);

        // ── Health check ───────────────────────────────────────────────────────
        app.get("/health", ctx -> ctx.status(200).result(
            "{\"status\":\"ok\",\"version\":\"1.0.0\",\"service\":\"optimix-backend\"}"));

        // ── 404 fallback ───────────────────────────────────────────────────────
        app.error(404, ctx -> ctx.json(
            new com.optimix.model.dto.ErrorResponse("Route not found: " + ctx.path())));

        // ── 401/403 handler ────────────────────────────────────────────────────
        app.error(401, ctx -> ctx.json(
            new com.optimix.model.dto.ErrorResponse("Unauthorized. Please log in.")));
    }
}
