package com.optimix.util;

import com.optimix.config.AppConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT utility — generates and validates JSON Web Tokens.
 *
 * Token payload:
 *   sub   = userId (as String)
 *   email = user's email
 *   auth  = "email" | "google"
 *   iat   = issued at
 *   exp   = expires at (now + 24h)
 *
 * Algorithm: HMAC-SHA-256 (HS256)
 * Secret:    From AppConfig.getJwtSecret() — min 32 chars required
 */
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil() {
        String secret = AppConfig.getJwtSecret();
        if (secret.length() < 32) {
            throw new IllegalStateException(
                "JWT secret must be at least 32 characters. Current length: " + secret.length());
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = AppConfig.getJwtExpirationMs();
    }

    /** Generate a signed JWT token for a user. */
    public String generateToken(long userId, String email, String authMethod) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("email", email)
                .claim("auth",  authMethod)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validate a token and return its claims.
     * Throws JwtException if token is invalid, expired, or tampered.
     */
    public Claims validateToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** Extract userId from a valid token. */
    public long getUserId(String token) {
        return Long.parseLong(validateToken(token).getSubject());
    }

    /** Extract email from a valid token. */
    public String getEmail(String token) {
        return validateToken(token).get("email", String.class);
    }

    /** Check if a token is valid without throwing. */
    public boolean isValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
