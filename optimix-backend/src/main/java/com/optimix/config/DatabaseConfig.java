package com.optimix.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite database configuration.
 *
 * Manages the local SQLite database stored at ~/.optimix/optimix.db
 * Creates all tables on first run (schema is idempotent — safe to run multiple times).
 */
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);
    private static final String DB_URL;

    static {
        String path = AppConfig.getSqlitePath();
        // Ensure parent directory exists (e.g. ~/.optimix/)
        File dbFile = new File(path);
        dbFile.getParentFile().mkdirs();
        DB_URL = "jdbc:sqlite:" + path;
        log.info("SQLite database path: {}", path);
    }

    /** Get a new SQLite connection. Caller must close it. */
    public static Connection getConnection() throws Exception {
        return DriverManager.getConnection(DB_URL);
    }

    /** Create all tables on first run. Safe to call multiple times (uses IF NOT EXISTS). */
    public static void initializeSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Enable foreign key enforcement
            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.execute(CREATE_USERS);
            stmt.execute(CREATE_EMAIL_VERIFICATION);
            stmt.execute(CREATE_SAVED_CONNECTIONS);
            stmt.execute(CREATE_QUERY_HISTORY);
            stmt.execute(CREATE_USER_PREFERENCES);

            log.info("✓ SQLite schema initialized successfully");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite schema: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DDL Statements
    // ══════════════════════════════════════════════════════════════════════════

    private static final String CREATE_USERS = """
        CREATE TABLE IF NOT EXISTS users (
            user_id             INTEGER  PRIMARY KEY AUTOINCREMENT,
            email               TEXT     NOT NULL UNIQUE,
            password_hash       TEXT,
            full_name           TEXT     NOT NULL,
            profile_picture_url TEXT,
            auth_method         TEXT     NOT NULL CHECK(auth_method IN ('email','google')),
            email_verified      INTEGER  NOT NULL DEFAULT 0,
            google_id           TEXT     UNIQUE,
            created_at          DATETIME NOT NULL DEFAULT (datetime('now')),
            last_login          DATETIME
        )
        """;

    private static final String CREATE_EMAIL_VERIFICATION = """
        CREATE TABLE IF NOT EXISTS email_verification (
            verification_id INTEGER  PRIMARY KEY AUTOINCREMENT,
            email           TEXT     NOT NULL,
            otp_code        TEXT     NOT NULL,
            full_name       TEXT     NOT NULL,
            password_hash   TEXT     NOT NULL,
            created_at      DATETIME NOT NULL DEFAULT (datetime('now')),
            expires_at      DATETIME NOT NULL,
            attempts        INTEGER  NOT NULL DEFAULT 0
        )
        """;

    private static final String CREATE_SAVED_CONNECTIONS = """
        CREATE TABLE IF NOT EXISTS saved_connections (
            connection_id      INTEGER  PRIMARY KEY AUTOINCREMENT,
            user_id            INTEGER  NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
            profile_name       TEXT     NOT NULL,
            host               TEXT     NOT NULL,
            port               INTEGER  NOT NULL DEFAULT 3306,
            database_name      TEXT     NOT NULL,
            mysql_username     TEXT     NOT NULL,
            encrypted_password TEXT     NOT NULL,
            encryption_iv      TEXT     NOT NULL,
            encryption_salt    TEXT     NOT NULL,
            created_at         DATETIME NOT NULL DEFAULT (datetime('now')),
            last_used          DATETIME
        )
        """;

    private static final String CREATE_QUERY_HISTORY = """
        CREATE TABLE IF NOT EXISTS query_history (
            history_id             INTEGER  PRIMARY KEY AUTOINCREMENT,
            user_id                INTEGER  NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
            connection_id          INTEGER  REFERENCES saved_connections(connection_id),
            original_query         TEXT     NOT NULL,
            optimized_query        TEXT     NOT NULL,
            original_cost          REAL,
            optimized_cost         REAL,
            speedup_factor         REAL,
            patterns_applied       TEXT,
            transformation_details TEXT,
            index_recommendations  TEXT,
            created_at             DATETIME NOT NULL DEFAULT (datetime('now'))
        )
        """;

    private static final String CREATE_USER_PREFERENCES = """
        CREATE TABLE IF NOT EXISTS user_preferences (
            preference_id         INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id               INTEGER NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
            theme                 TEXT    NOT NULL DEFAULT 'dark',
            default_connection_id INTEGER,
            auto_save_history     INTEGER NOT NULL DEFAULT 1,
            show_cost_details     INTEGER NOT NULL DEFAULT 1
        )
        """;
}
