package com.optimix.config;

import java.io.InputStream;
import java.util.Properties;

/**
 * Application configuration loader.
 *
 * Priority order (highest first):
 *  1. Environment variables  (SCREAMING_SNAKE_CASE)
 *  2. application.properties (in src/main/resources)
 *  3. Hardcoded defaults
 */
public class AppConfig {

    private static final Properties props = new Properties();

    static {
        try (InputStream is = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (Exception e) {
            // Fine — all values have defaults
        }
    }

    // ── Server ────────────────────────────────────────────────────────────────

    public static int getPort() {
        return Integer.parseInt(get("SERVER_PORT", "server.port", "7070"));
    }

    // ── JWT ───────────────────────────────────────────────────────────────────

    public static String getJwtSecret() {
        return get("JWT_SECRET", "jwt.secret",
                "optimix-dev-secret-change-me-in-production-needs-to-be-at-least-64-chars-long!!");
    }

    public static long getJwtExpirationMs() {
        return Long.parseLong(get("JWT_EXPIRATION_MS", "jwt.expiration.ms", "86400000")); // 24h
    }

    // ── SMTP / Email ──────────────────────────────────────────────────────────

    public static String getSmtpHost() {
        return get("SMTP_HOST", "smtp.host", "smtp.gmail.com");
    }

    public static int getSmtpPort() {
        return Integer.parseInt(get("SMTP_PORT", "smtp.port", "587"));
    }

    public static String getSmtpUser() {
        return get("SMTP_USER", "smtp.user", "");
    }

    public static String getSmtpPassword() {
        return get("SMTP_PASSWORD", "smtp.password", "");
    }

    public static String getSmtpFromName() {
        return get("SMTP_FROM_NAME", "smtp.from.name", "Optimix");
    }

    // ── Google OAuth ──────────────────────────────────────────────────────────

    public static String getGoogleClientId() {
        return get("GOOGLE_CLIENT_ID", "google.client.id", "");
    }

    // ── SQLite ────────────────────────────────────────────────────────────────

    public static String getSqlitePath() {
        String defaultPath = System.getProperty("user.home") + "/.optimix/optimix.db";
        return get("SQLITE_PATH", "sqlite.path", defaultPath);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Environment variable takes priority over properties file.
     */
    private static String get(String envKey, String propKey, String defaultVal) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) return envVal.trim();
        return props.getProperty(propKey, defaultVal);
    }
}
