package com.optimix.api.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.optimix.config.DatabaseConfig;
import com.optimix.model.dto.ConnectionDto;
import com.optimix.model.dto.ConnectionSaveRequest;
import com.optimix.model.dto.ConnectionTestResult;
import com.optimix.model.dto.ErrorResponse;
import com.optimix.model.dto.MessageResponse;
import com.optimix.util.CredentialEncryption;

import io.javalin.http.Context;

/**
 * MySQL connection profile management.
 *
 * POST /api/connections/test       → test connection (does not save)
 * POST /api/connections            → save connection profile (encrypts password)
 * GET  /api/connections            → list user's saved connections
 * DELETE /api/connections/:id      → delete a saved connection
 * GET  /api/connections/:id/test   → test a saved connection
 *
 * NOTE: MySQL passwords are encrypted with AES-256-GCM before storage.
 * The encryption key is derived from a server-side secret + user ID.
 * Full session-key-based encryption (PBKDF2 from login password) can be
 * added as a follow-up — requires passing the derived key through the session.
 */
public class ConnectionController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionController.class);

    // ── POST /api/connections/test ────────────────────────────────────────────
    public void testConnection(Context ctx) {
        try {
            ConnectionSaveRequest req = ctx.bodyAsClass(ConnectionSaveRequest.class);
            log.info("REQ → host={}, port={}, db={}, user={}, pass={}",
            req.host, req.port, req.databaseName, req.mysqlUsername, req.mysqlPassword);
            ConnectionTestResult result = doTestConnection(
                req.host, req.port, req.databaseName, req.mysqlUsername, req.mysqlPassword);
            ctx.status(200).json(result);
        } catch (Exception e) {
            log.error("Connection test error", e);
            ctx.status(200).json(new ConnectionTestResult(false,
                "Connection failed: " + sanitize(e.getMessage())));
        }
    }

    // ── POST /api/connections ─────────────────────────────────────────────────
    public void saveConnection(Context ctx) {
        try {
            Long userId = ctx.attribute("userId");
            ConnectionSaveRequest req = ctx.bodyAsClass(ConnectionSaveRequest.class);

            validateConnectionRequest(req);

            // Encrypt the MySQL password before storing
            byte[] salt = CredentialEncryption.generateSalt();
            SecretKey encKey = deriveEncryptionKey(userId, salt);
            CredentialEncryption.EncryptedCredential enc =
                CredentialEncryption.encrypt(req.mysqlPassword, encKey);

            try (Connection conn = DatabaseConfig.getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO saved_connections (" +
                    "user_id, profile_name, host, port, database_name, mysql_username, " +
                    "encrypted_password, encryption_iv, encryption_salt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                );
                ps.setLong  (1, userId);
                ps.setString(2, req.profileName);
                ps.setString(3, req.host);
                ps.setInt   (4, req.port);
                ps.setString(5, req.databaseName);
                ps.setString(6, req.mysqlUsername);
                ps.setString(7, enc.encryptedPassword);
                ps.setString(8, enc.iv);
                ps.setString(9, Base64.getEncoder().encodeToString(salt));
                ps.executeUpdate();

                long connectionId = getLastInsertId(conn);
                log.info("Connection profile saved: '{}' for user {}", req.profileName, userId);
                ctx.status(201).json(new IdResponse(connectionId));
            }

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Save connection error", e);
            ctx.status(500).json(new ErrorResponse("Failed to save connection."));
        }
    }

    // ── GET /api/connections ──────────────────────────────────────────────────
    public void listConnections(Context ctx) {
        try {
            Long userId = ctx.attribute("userId");

            try (Connection conn = DatabaseConfig.getConnection()) {
                PreparedStatement ps = conn.prepareStatement("""
                    SELECT connection_id, profile_name, host, port,
                           database_name, mysql_username, created_at, last_used
                    FROM saved_connections
                    WHERE user_id = ?
                    ORDER BY created_at DESC
                """);
                ps.setLong(1, userId);
                ResultSet rs = ps.executeQuery();

                List<ConnectionDto> list = new ArrayList<>();
                while (rs.next()) {
                    ConnectionDto dto = new ConnectionDto();
                    dto.connectionId  = rs.getInt("connection_id");
                    dto.profileName   = rs.getString("profile_name");
                    dto.host          = rs.getString("host");
                    dto.port          = rs.getInt("port");
                    dto.databaseName  = rs.getString("database_name");
                    dto.mysqlUsername = rs.getString("mysql_username");
                    dto.createdAt     = rs.getString("created_at");
                    dto.lastUsed      = rs.getString("last_used");
                    list.add(dto);
                }
                ctx.status(200).json(list);
            }

        } catch (Exception e) {
            log.error("List connections error", e);
            ctx.status(500).json(new ErrorResponse("Failed to load connections."));
        }
    }

    // ── DELETE /api/connections/:id ───────────────────────────────────────────
    public void deleteConnection(Context ctx) {
        try {
            Long userId = ctx.attribute("userId");
            int  connectionId = Integer.parseInt(ctx.pathParam("id"));

            try (Connection conn = DatabaseConfig.getConnection()) {
                // WHERE user_id ensures users can only delete their own connections
                PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM saved_connections WHERE connection_id = ? AND user_id = ?");
                ps.setInt (1, connectionId);
                ps.setLong(2, userId);
                int deleted = ps.executeUpdate();

                if (deleted == 0) {
                    ctx.status(404).json(new ErrorResponse("Connection not found."));
                } else {
                    ctx.status(200).json(new MessageResponse("Connection deleted."));
                }
            }

        } catch (NumberFormatException e) {
            ctx.status(400).json(new ErrorResponse("Invalid connection ID."));
        } catch (Exception e) {
            log.error("Delete connection error", e);
            ctx.status(500).json(new ErrorResponse("Failed to delete connection."));
        }
    }

    // ── GET /api/connections/:id/test ─────────────────────────────────────────
    public void testSavedConnection(Context ctx) {
        try {
            Long userId = ctx.attribute("userId");
            int  connectionId = Integer.parseInt(ctx.pathParam("id"));

            // Load and decrypt connection details
            try (Connection sqliteConn = DatabaseConfig.getConnection()) {
                PreparedStatement ps = sqliteConn.prepareStatement("""
                    SELECT host, port, database_name, mysql_username,
                           encrypted_password, encryption_iv, encryption_salt
                    FROM saved_connections
                    WHERE connection_id = ? AND user_id = ?
                """);
                ps.setInt (1, connectionId);
                ps.setLong(2, userId);
                ResultSet rs = ps.executeQuery();

                if (!rs.next()) {
                    ctx.status(404).json(new ErrorResponse("Connection not found.")); return;
                }

                String host     = rs.getString("host");
                int    port     = rs.getInt("port");
                String dbName   = rs.getString("database_name");
                String username = rs.getString("mysql_username");
                byte[] salt     = Base64.getDecoder().decode(rs.getString("encryption_salt"));
                SecretKey key   = deriveEncryptionKey(userId, salt);
                String password = CredentialEncryption.decrypt(
                    rs.getString("encrypted_password"), rs.getString("encryption_iv"), key);

                ConnectionTestResult result = doTestConnection(host, port, dbName, username, password);
                ctx.status(200).json(result);
            }

        } catch (Exception e) {
            log.error("Test saved connection error", e);
            ctx.status(200).json(new ConnectionTestResult(false,
                "Test failed: " + sanitize(e.getMessage())));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ConnectionTestResult doTestConnection(
            String host, int port, String dbName, String username, String password) {
        String url = String.format(
    "jdbc:mysql://%s:%d/?connectTimeout=5000&socketTimeout=5000" +
    "&useSSL=false&allowPublicKeyRetrieval=true", host, port);
        System.out.println("CONNECTING → " + host + ":" + port + "/" + dbName + " user=" + username);

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL Driver not found", e);
        }
        try (java.sql.Connection conn = DriverManager.getConnection(url, username, password)) {
            // Check if database exists
            PreparedStatement dbCheckStmt = conn.prepareStatement(
                "SHOW DATABASES LIKE ?");
            dbCheckStmt.setString(1, dbName);
            ResultSet dbCheck = dbCheckStmt.executeQuery();

            if (!dbCheck.next()) {
                return new ConnectionTestResult(false,
                    "Database '" + dbName + "' does not exist.");
            }

            // Switch to the database
            conn.setCatalog(dbName);
            conn.createStatement().execute("USE " + dbName);
            // Get MySQL version
            ResultSet versionRs = conn.createStatement()
                .executeQuery("SELECT VERSION() AS v");
            String version = versionRs.next() ? versionRs.getString("v") : "unknown";

            // Count tables
            ResultSet tableRs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()");
            int tableCount = tableRs.next() ? tableRs.getInt(1) : 0;

            ConnectionTestResult result = new ConnectionTestResult(true, "Connected successfully!");
            result.mysqlVersion = version;
            result.tableCount   = tableCount;
            return result;

        } catch (Exception e) {
            e.printStackTrace();   // 👈 ADD THIS LINE
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            String friendly;
            if (msg.contains("Communications link failure") || msg.contains("Connection refused")) {
                friendly = "MySQL is not running on " + host + ":" + port + ". Start MySQL and try again.";
            } else if (msg.contains("Access denied")) {
                friendly = "Wrong username or password for MySQL.";
            } else if (msg.contains("Unknown database")) {
                friendly = "Database '" + dbName + "' does not exist. Create it first.";
            } else if (msg.contains("connect timed out") || msg.contains("timeout")) {
                friendly = "Connection timed out. Check host and port.";
            } else {
                friendly = sanitize(msg);
            }
            return new ConnectionTestResult(false, friendly);
        }
    }

    /**
     * Derive an AES encryption key from a server-side secret + userId + salt.
     * This ties the key to a specific user without requiring their login password
     * to be in memory. For maximum security, use login-password-derived keys.
     */
    private SecretKey deriveEncryptionKey(long userId, byte[] salt) throws Exception {
        // Combine a server secret + userId as the "password" for PBKDF2
        String serverSecret = System.getenv().getOrDefault("ENCRYPTION_SECRET",
            "optimix-server-secret-change-in-production");
        char[] keyMaterial = (serverSecret + ":" + userId).toCharArray();
        return CredentialEncryption.deriveKey(keyMaterial, salt);
    }

    private void validateConnectionRequest(ConnectionSaveRequest req) {
        if (req.profileName == null || req.profileName.isBlank())
            throw new IllegalArgumentException("Profile name is required.");
        if (req.host == null || req.host.isBlank())
            throw new IllegalArgumentException("Host is required.");
        if (req.port < 1 || req.port > 65535)
            throw new IllegalArgumentException("Invalid port number.");
        if (req.databaseName == null || req.databaseName.isBlank())
            throw new IllegalArgumentException("Database name is required.");
        if (req.mysqlUsername == null || req.mysqlUsername.isBlank())
            throw new IllegalArgumentException("MySQL username is required.");
        if (req.mysqlPassword == null || req.mysqlPassword.isBlank())
            throw new IllegalArgumentException("MySQL password is required.");
    }

    /** Never expose internal JDBC error details to the client */
    private String sanitize(String msg) {
        if (msg == null) return "Unknown error";
        if (msg.contains("Access denied")) return "Access denied. Check your username and password.";
        if (msg.contains("Communications link")) return "Cannot reach the server. Check host and port.";
        if (msg.contains("Unknown database"))    return "Database not found. Check the database name.";
        if (msg.contains("timed out"))           return "Connection timed out. Is the server running?";
        return "Connection error. Check your settings.";
    }

    // Simple response for returning an ID
    public static class IdResponse {
        public long id;
        public IdResponse(long id) { this.id = id; }
    }
    /** SQLite-compatible last insert ID. */
    private long getLastInsertId(java.sql.Connection conn) throws Exception {
        try (java.sql.Statement st = conn.createStatement()) {
            java.sql.ResultSet rs = st.executeQuery("SELECT last_insert_rowid()");
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

}
