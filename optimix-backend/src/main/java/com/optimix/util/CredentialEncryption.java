package com.optimix.util;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM credential encryption for stored MySQL passwords.
 *
 * ── Security Design ───────────────────────────────────────────────────────
 *
 * PROBLEM: We need to store MySQL passwords so users don't re-enter them,
 * but storing them in plaintext is insecure.
 *
 * SOLUTION:
 *  1. Master key is NEVER stored. It's derived fresh each session from the
 *     user's login password using PBKDF2 (65,536 iterations, SHA-256).
 *  2. MySQL password encrypted with AES-256-GCM (authenticated encryption).
 *     GCM provides both confidentiality AND integrity (detects tampering).
 *  3. Random 96-bit IV generated per encryption — same plaintext → different ciphertext.
 *  4. Random 128-bit salt stored in DB (safe to store, needed to re-derive key).
 *
 * ── What's stored in SQLite ───────────────────────────────────────────────
 *   encrypted_password  (AES-256-GCM ciphertext, Base64)
 *   encryption_iv       (96-bit random IV, Base64)
 *   encryption_salt     (128-bit PBKDF2 salt, Base64)
 *
 * ── Threat model ──────────────────────────────────────────────────────────
 *   Even if attacker steals the SQLite file, they CANNOT decrypt MySQL
 *   passwords without knowing the user's login password.
 *   Different users have different salts → different derived keys.
 *
 * NOTE: For Google OAuth users (no login password), a session-specific key
 * is derived from their Google token + a server secret. TODO: implement
 * full session key management in a follow-up.
 */
public class CredentialEncryption {

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String AES_ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    PBKDF2_ITERATIONS = 65_536;
    private static final int    KEY_LENGTH_BITS   = 256;
    private static final int    GCM_TAG_LENGTH    = 128; // bits
    private static final int    IV_BYTES          = 12;  // 96-bit IV (GCM standard)
    private static final int    SALT_BYTES        = 16;  // 128-bit salt

    // ── Key derivation ────────────────────────────────────────────────────────

    /**
     * Derive a 256-bit AES key from a login password + salt.
     * Call once on login; keep in memory for the session. NEVER store this key.
     *
     * @param loginPassword The user's plaintext login password (char[] for security)
     * @param salt          The stored salt (from encryption_salt column)
     * @return 256-bit AES SecretKey
     */
    public static SecretKey deriveKey(char[] loginPassword, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        KeySpec spec = new PBEKeySpec(loginPassword, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    /** Generate a new random salt. Call once per user at account creation. */
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    // ── Encrypt ───────────────────────────────────────────────────────────────

    /**
     * Encrypt a MySQL password using the session's derived AES key.
     *
     * @param plaintext  The MySQL password to encrypt
     * @param key        The session's derived AES key (from deriveKey())
     * @return EncryptedCredential with Base64-encoded ciphertext + IV
     */
    public static EncryptedCredential encrypt(String plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

        return new EncryptedCredential(
            Base64.getEncoder().encodeToString(ciphertext),
            Base64.getEncoder().encodeToString(iv)
        );
    }

    // ── Decrypt ───────────────────────────────────────────────────────────────

    /**
     * Decrypt a stored MySQL password.
     *
     * @param encryptedBase64 Ciphertext from DB (encrypted_password column)
     * @param ivBase64        IV from DB (encryption_iv column)
     * @param key             Session's derived AES key (from deriveKey())
     * @return Plaintext MySQL password
     * @throws Exception if key is wrong or data is tampered (GCM auth tag fails)
     */
    public static String decrypt(String encryptedBase64, String ivBase64, SecretKey key) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(encryptedBase64);
        byte[] iv         = Base64.getDecoder().decode(ivBase64);

        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, "UTF-8");
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public static class EncryptedCredential {
        public final String encryptedPassword; // Base64 ciphertext
        public final String iv;                // Base64 IV

        public EncryptedCredential(String encryptedPassword, String iv) {
            this.encryptedPassword = encryptedPassword;
            this.iv                = iv;
        }
    }
}
