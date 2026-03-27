package com.optimix.api.middleware;

import com.optimix.util.JwtUtil;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * JWT authentication middleware.
 *
 * Checks Authorization: Bearer <token> header.
 * On success, sets ctx attributes:
 *   "userId" → long
 *   "email"  → String
 *
 * Called by Router via app.before() for all protected routes.
 */
public class AuthMiddleware {

    private static final JwtUtil jwtUtil = new JwtUtil();

    public static void requireAuth(Context ctx) {
        String header = ctx.header("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            throw new UnauthorizedResponse("Missing Authorization header. Please log in.");
        }

        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedResponse("Empty token. Please log in.");
        }

        try {
            Claims claims = jwtUtil.validateToken(token);
            long   userId = Long.parseLong(claims.getSubject());
            String email  = claims.get("email", String.class);

            // Make userId and email available to downstream handlers
            ctx.attribute("userId", userId);
            ctx.attribute("email",  email);

        } catch (JwtException | NumberFormatException e) {
            throw new UnauthorizedResponse("Invalid or expired token. Please log in again.");
        }
    }
}
