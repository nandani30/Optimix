package com.optimix.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.optimix.config.DatabaseConfig;
import com.optimix.model.dto.AuthResponse;
import com.optimix.model.dto.GoogleTokenInfo;
import com.optimix.model.dto.UserDto;
import com.optimix.util.EmailService;
import com.optimix.util.GoogleTokenVerifier;
import com.optimix.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

/**
 * Core authentication business logic.
 *
 *  Method 1 — Email + OTP:
 *    signup()    → hash password, store in email_verification, send OTP
 *    verifyOtp() → validate OTP, create user, return JWT
 *    resendOtp() → regenerate OTP, resend email
 *    login()     → verify BCrypt hash, return JWT
 *
 *  Method 2 — Google OAuth:
 *    googleLogin() → verify Google ID token, create/find user, return JWT
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int BCRYPT_ROUNDS      = 12;
    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int MAX_OTP_ATTEMPTS   = 3;

    private final JwtUtil      jwtUtil      = new JwtUtil();
    private final EmailService emailService = new EmailService();

    // ════════════════════════════════════════════════════════════════════════
    //  Email/Password Auth
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Step 1 of signup — validates input, hashes password, sends OTP.
     * Does NOT create a users row yet (only after OTP verified).
     */
    public void initiateSignup(String email, String fullName, String password) throws Exception {
        // Check if email already registered in users table
        if (emailExistsInUsers(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        // Hash password with BCrypt (12 rounds)
        String passwordHash = BCrypt.withDefaults()
                .hashToString(BCRYPT_ROUNDS, password.toCharArray());

        // Generate 6-digit OTP
        String otp = String.format("%06d", new Random().nextInt(1_000_000));
        Instant expiresAt = Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES);

        try (Connection conn = DatabaseConfig.getConnection()) {
            // Remove any existing pending verification for this email
            PreparedStatement del = conn.prepareStatement(
                "DELETE FROM email_verification WHERE email = ?");
            del.setString(1, email);
            del.executeUpdate();

            // Insert into temporary verification table (NOT users table yet)
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO email_verification
                    (email, otp_code, full_name, password_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
            """);
            ps.setString(1, email);
            ps.setString(2, otp);
            ps.setString(3, fullName);
            ps.setString(4, passwordHash);
            ps.setString(5, expiresAt.toString());
            ps.executeUpdate();
        }

        // Send OTP email asynchronously — doesn't block the HTTP response
        emailService.sendOtpEmail(email, fullName, otp);
        log.info("Signup initiated for email: {} (OTP sent)", email);
    }

    /**
     * Step 2 of signup — validates OTP, creates user account, returns JWT.
     */
    public AuthResponse verifyOtp(String email, String otpCode) throws Exception {
        try (Connection conn = DatabaseConfig.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT verification_id, otp_code, full_name, password_hash,
                       expires_at, attempts
                FROM email_verification
                WHERE email = ?
                ORDER BY created_at DESC
                LIMIT 1
            """);
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                throw new IllegalArgumentException(
                    "No pending verification found. Please sign up first.");
            }

            int     verId     = rs.getInt("verification_id");
            String  stored    = rs.getString("otp_code");
            String  fullName  = rs.getString("full_name");
            String  pwdHash   = rs.getString("password_hash");
            Instant expiresAt = Instant.parse(rs.getString("expires_at"));
            int     attempts  = rs.getInt("attempts");

            // Too many wrong attempts
            if (attempts >= MAX_OTP_ATTEMPTS) {
                throw new IllegalArgumentException(
                    "Too many incorrect attempts. Please request a new code.");
            }

            // OTP expired
            if (Instant.now().isAfter(expiresAt)) {
                throw new IllegalArgumentException(
                    "OTP has expired. Please click 'Resend code'.");
            }

            // Wrong OTP
            if (!stored.equals(otpCode.trim())) {
                // Increment attempt counter
                PreparedStatement inc = conn.prepareStatement(
                    "UPDATE email_verification SET attempts = attempts + 1 WHERE verification_id = ?");
                inc.setInt(1, verId);
                inc.executeUpdate();

                int remaining = MAX_OTP_ATTEMPTS - attempts - 1;
                throw new IllegalArgumentException(
                    "Incorrect code. " + remaining + " attempt(s) remaining.");
            }

            // ✓ OTP valid — create user account
            PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO users
                    (email, password_hash, full_name, auth_method, email_verified)
                VALUES (?, ?, ?, 'email', 1)
            """);
            insert.setString(1, email);
            insert.setString(2, pwdHash);
            insert.setString(3, fullName);
            insert.executeUpdate();

            long userId = getLastInsertId(conn);

            // Delete OTP record — it's consumed
            PreparedStatement del = conn.prepareStatement(
                "DELETE FROM email_verification WHERE email = ?");
            del.setString(1, email);
            del.executeUpdate();

            // Create default preferences row
            createDefaultPreferences(conn, userId);

            log.info("Email verified — user created: {} (id={})", email, userId);

            UserDto user  = new UserDto(userId, email, fullName, null, "email", true);
            String  token = jwtUtil.generateToken(userId, email, "email");
            return new AuthResponse(token, user);
        }
    }

    /**
     * Resend a fresh OTP to the email. Resets attempt counter.
     */
    public void resendOtp(String email) throws Exception {
        try (Connection conn = DatabaseConfig.getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                "SELECT full_name, password_hash FROM email_verification WHERE email = ? LIMIT 1");
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                throw new IllegalArgumentException(
                    "No pending signup found for this email. Please sign up first.");
            }

            String fullName = rs.getString("full_name");
            String pwdHash  = rs.getString("password_hash");

            String  newOtp    = String.format("%06d", new Random().nextInt(1_000_000));
            Instant expiresAt = Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES);

            // Replace the old record
            PreparedStatement del = conn.prepareStatement(
                "DELETE FROM email_verification WHERE email = ?");
            del.setString(1, email);
            del.executeUpdate();

            PreparedStatement ins = conn.prepareStatement("""
                INSERT INTO email_verification
                    (email, otp_code, full_name, password_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
            """);
            ins.setString(1, email);
            ins.setString(2, newOtp);
            ins.setString(3, fullName);
            ins.setString(4, pwdHash);
            ins.setString(5, expiresAt.toString());
            ins.executeUpdate();

            emailService.sendOtpEmail(email, fullName, newOtp);
            log.info("OTP resent to: {}", email);
        }
    }

    /**
     * Email + password login. No OTP needed (email already verified at signup).
     */
    public AuthResponse login(String email, String password) throws Exception {
        try (Connection conn = DatabaseConfig.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT user_id, password_hash, full_name, profile_picture_url, auth_method
                FROM users
                WHERE email = ? AND email_verified = 1
                LIMIT 1
            """);
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                // Intentionally vague — don't reveal whether email exists
                throw new IllegalArgumentException("Invalid email or password.");
            }

            String storedHash = rs.getString("password_hash");
            if (storedHash == null) {
                throw new IllegalArgumentException(
                    "This account uses Google Sign-In. Please continue with Google.");
            }

            // Verify password against BCrypt hash
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), storedHash);
            if (!result.verified) {
                throw new IllegalArgumentException("Invalid email or password.");
            }

            long   userId  = rs.getLong("user_id");
            String name    = rs.getString("full_name");
            String pic     = rs.getString("profile_picture_url");

            // Update last_login timestamp
            PreparedStatement upd = conn.prepareStatement(
                "UPDATE users SET last_login = datetime('now') WHERE user_id = ?");
            upd.setLong(1, userId);
            upd.executeUpdate();

            log.info("Login successful: {} (id={})", email, userId);

            UserDto user  = new UserDto(userId, email, name, pic, "email", true);
            String  token = jwtUtil.generateToken(userId, email, "email");
            return new AuthResponse(token, user);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Google OAuth
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Google OAuth login/signup.
     * Verifies the Google ID token, then creates or finds the user.
     */
    public AuthResponse googleLogin(String googleIdToken) throws Exception {
        // Verify token with Google's tokeninfo endpoint
        GoogleTokenInfo info = GoogleTokenVerifier.verify(googleIdToken);

        try (Connection conn = DatabaseConfig.getConnection()) {
            // Look for existing user by Google ID or email
            PreparedStatement ps = conn.prepareStatement("""
                SELECT user_id, full_name, profile_picture_url
                FROM users
                WHERE google_id = ? OR email = ?
                LIMIT 1
            """);
            ps.setString(1, info.sub);
            ps.setString(2, info.email);
            ResultSet rs = ps.executeQuery();

            long   userId;
            String fullName;
            String picture;

            if (rs.next()) {
                // Returning Google user — update their profile pic in case it changed
                userId   = rs.getLong("user_id");
                fullName = rs.getString("full_name");
                picture  = info.picture != null ? info.picture : rs.getString("profile_picture_url");

                PreparedStatement upd = conn.prepareStatement("""
                    UPDATE users
                    SET last_login = datetime('now'),
                        profile_picture_url = ?,
                        google_id = ?
                    WHERE user_id = ?
                """);
                upd.setString(1, picture);
                upd.setString(2, info.sub);
                upd.setLong(3, userId);
                upd.executeUpdate();

            } else {
                // New Google user — create account (no password needed)
                fullName = info.name != null ? info.name : info.email;
                picture  = info.picture;

                PreparedStatement ins = conn.prepareStatement("""
                    INSERT INTO users
                        (email, full_name, profile_picture_url, auth_method, email_verified, google_id)
                    VALUES (?, ?, ?, 'google', 1, ?)
                """);
                ins.setString(1, info.email);
                ins.setString(2, fullName);
                ins.setString(3, picture);
                ins.setString(4, info.sub);
                ins.executeUpdate();

                userId = getLastInsertId(conn);
                createDefaultPreferences(conn, userId);

                log.info("New Google user created: {} (id={})", info.email, userId);
            }

            log.info("Google login successful: {} (id={})", info.email, userId);

            UserDto user  = new UserDto(userId, info.email, fullName, picture, "google", true);
            String  token = jwtUtil.generateToken(userId, info.email, "google");
            return new AuthResponse(token, user);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private boolean emailExistsInUsers(String email) throws Exception {
        try (Connection conn = DatabaseConfig.getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM users WHERE email = ? LIMIT 1");
            ps.setString(1, email);
            return ps.executeQuery().next();
        }
    }

    private void createDefaultPreferences(Connection conn, long userId) throws Exception {
        PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO user_preferences (user_id) VALUES (?)");
        ps.setLong(1, userId);
        ps.executeUpdate();
    }
    // ════════════════════════════════════════════════════════════════════════
    //  Forgot / Reset Password
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Step 1: User submits their email.
     * We send a 6-digit reset code (same OTP mechanism as signup).
     * We reuse the email_verification table with a special marker.
     */
    public void forgotPassword(String email) throws Exception {
        // Check if this email exists in users table
        if (!emailExistsInUsers(email)) {
            // Don't reveal whether email exists — just silently succeed
            // (security best practice: don't enumerate accounts)
            log.info("Forgot password requested for unknown email: {}", email);
            return;
        }

        String  otp       = String.format("%06d", new java.util.Random().nextInt(1_000_000));
        java.time.Instant exp = java.time.Instant.now().plus(OTP_EXPIRY_MINUTES, java.time.temporal.ChronoUnit.MINUTES);

        try (java.sql.Connection conn = com.optimix.config.DatabaseConfig.getConnection()) {
            // Delete any existing reset request for this email
            java.sql.PreparedStatement del = conn.prepareStatement(
                "DELETE FROM email_verification WHERE email = ?");
            del.setString(1, email);
            del.executeUpdate();

            // Store with a placeholder password_hash (not used for reset)
            java.sql.PreparedStatement ins = conn.prepareStatement("""
                INSERT INTO email_verification (email, otp_code, full_name, password_hash, expires_at)
                VALUES (?, ?, 'RESET', 'RESET', ?)
            """);
            ins.setString(1, email);
            ins.setString(2, otp);
            ins.setString(3, exp.toString());
            ins.executeUpdate();
        }

        // Send reset email
        emailService.sendPasswordResetEmail(email, otp);
        log.info("Password reset OTP sent to: {}", email);
    }

    /**
     * Step 2: User submits the reset code + their new password.
     */
    public void resetPassword(String email, String otpCode, String newPassword) throws Exception {
        try (java.sql.Connection conn = com.optimix.config.DatabaseConfig.getConnection()) {
            java.sql.PreparedStatement ps = conn.prepareStatement("""
                SELECT otp_code, expires_at, attempts FROM email_verification
                WHERE email = ? AND full_name = 'RESET'
                ORDER BY created_at DESC LIMIT 1
            """);
            ps.setString(1, email);
            java.sql.ResultSet rs = ps.executeQuery();

            if (!rs.next()) throw new IllegalArgumentException("No reset request found. Please request a new code.");

            String  stored   = rs.getString("otp_code");
            java.time.Instant exp  = java.time.Instant.parse(rs.getString("expires_at"));
            int     attempts = rs.getInt("attempts");

            if (attempts >= MAX_OTP_ATTEMPTS)
                throw new IllegalArgumentException("Too many incorrect attempts. Please request a new code.");
            if (java.time.Instant.now().isAfter(exp))
                throw new IllegalArgumentException("Code has expired. Please request a new one.");
            if (!stored.equals(otpCode.trim())) {
                conn.prepareStatement(
                    "UPDATE email_verification SET attempts = attempts + 1 WHERE email = ?")
                    .execute();
                throw new IllegalArgumentException("Incorrect code. " + (MAX_OTP_ATTEMPTS - attempts - 1) + " attempt(s) remaining.");
            }

            // ✓ Code valid — update password
            String newHash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults()
                .hashToString(BCRYPT_ROUNDS, newPassword.toCharArray());

            java.sql.PreparedStatement upd = conn.prepareStatement(
                "UPDATE users SET password_hash = ? WHERE email = ?");
            upd.setString(1, newHash);
            upd.setString(2, email);
            int updated = upd.executeUpdate();

            if (updated == 0) throw new IllegalArgumentException("Account not found.");

            // Clean up reset record
            conn.prepareStatement("DELETE FROM email_verification WHERE email = ?")
                .execute();

            log.info("Password reset successfully for: {}", email);
        }
    }


    /**
     * SQLite-compatible way to get the last inserted row ID.
     * Uses SELECT last_insert_rowid() since SQLite JDBC doesn't support
     * Statement.RETURN_GENERATED_KEYS reliably.
     */
    private long getLastInsertId(java.sql.Connection conn) throws Exception {
        try (java.sql.Statement st = conn.createStatement()) {
            java.sql.ResultSet rs = st.executeQuery("SELECT last_insert_rowid()");
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

}
