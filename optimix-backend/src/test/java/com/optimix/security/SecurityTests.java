package com.optimix.security;

import com.optimix.util.CredentialEncryption;
import com.optimix.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.*;
import javax.crypto.SecretKey;
import static org.junit.jupiter.api.Assertions.*;

// ════════════════════════════════════════════════════════════════════════════
//  Security Tests: AES-256-GCM Encryption + JWT
// ════════════════════════════════════════════════════════════════════════════
@DisplayName("Security Tests")
public class SecurityTests {

    // ── AES-256-GCM ──────────────────────────────────────────────────────────

    @Test @DisplayName("encrypt → decrypt round-trip returns original plaintext")
    void encryptDecryptRoundTrip() throws Exception {
        byte[]    salt     = CredentialEncryption.generateSalt();
        SecretKey key      = CredentialEncryption.deriveKey("MyP@ssw0rd!".toCharArray(), salt);
        String    original = "mysql_secret_password_123";

        CredentialEncryption.EncryptedCredential enc = CredentialEncryption.encrypt(original, key);
        String decrypted = CredentialEncryption.decrypt(enc.encryptedPassword, enc.iv, key);

        assertEquals(original, decrypted);
    }

    @Test @DisplayName("each encryption produces a unique IV and ciphertext")
    void uniqueIvPerEncryption() throws Exception {
        byte[]    salt = CredentialEncryption.generateSalt();
        SecretKey key  = CredentialEncryption.deriveKey("password".toCharArray(), salt);

        var enc1 = CredentialEncryption.encrypt("same-text", key);
        var enc2 = CredentialEncryption.encrypt("same-text", key);

        assertNotEquals(enc1.iv, enc2.iv);
        assertNotEquals(enc1.encryptedPassword, enc2.encryptedPassword);
    }

    @Test @DisplayName("wrong key → decryption throws (GCM integrity failure)")
    void wrongKeyThrows() throws Exception {
        byte[]    salt1 = CredentialEncryption.generateSalt();
        byte[]    salt2 = CredentialEncryption.generateSalt();
        SecretKey key1  = CredentialEncryption.deriveKey("correct".toCharArray(), salt1);
        SecretKey key2  = CredentialEncryption.deriveKey("wrong".toCharArray(),   salt2);

        var enc = CredentialEncryption.encrypt("secret", key1);
        assertThrows(Exception.class, () -> CredentialEncryption.decrypt(enc.encryptedPassword, enc.iv, key2));
    }

    @Test @DisplayName("tampered ciphertext → decryption throws (GCM auth tag fails)")
    void tamperedCiphertextThrows() throws Exception {
        byte[]    salt = CredentialEncryption.generateSalt();
        SecretKey key  = CredentialEncryption.deriveKey("password".toCharArray(), salt);
        var enc = CredentialEncryption.encrypt("data", key);

        // Corrupt last 4 chars of base64 ciphertext
        String tampered = enc.encryptedPassword.substring(0, enc.encryptedPassword.length() - 4) + "XXXX";
        assertThrows(Exception.class, () -> CredentialEncryption.decrypt(tampered, enc.iv, key));
    }

    @Test @DisplayName("PBKDF2: same password+salt → same key (deterministic)")
    void sameInputSameKey() throws Exception {
        byte[]    salt = CredentialEncryption.generateSalt();
        char[]    pass = "P@ssword123!".toCharArray();
        SecretKey k1   = CredentialEncryption.deriveKey(pass, salt);
        SecretKey k2   = CredentialEncryption.deriveKey(pass, salt);
        assertArrayEquals(k1.getEncoded(), k2.getEncoded());
    }

    @Test @DisplayName("PBKDF2: different passwords → different keys")
    void differentPasswordsDifferentKeys() throws Exception {
        byte[]    salt = CredentialEncryption.generateSalt();
        SecretKey k1   = CredentialEncryption.deriveKey("password1".toCharArray(), salt);
        SecretKey k2   = CredentialEncryption.deriveKey("password2".toCharArray(), salt);
        assertFalse(java.util.Arrays.equals(k1.getEncoded(), k2.getEncoded()));
    }

    @Test @DisplayName("salt is 16 bytes and unique each call")
    void saltProperties() {
        byte[] s1 = CredentialEncryption.generateSalt();
        byte[] s2 = CredentialEncryption.generateSalt();
        assertEquals(16, s1.length);
        assertFalse(java.util.Arrays.equals(s1, s2));
    }

    // ── JWT ───────────────────────────────────────────────────────────────────

    @Test @DisplayName("JWT: generate and validate round-trip")
    void jwtGenerateValidate() {
        JwtUtil jwt   = new JwtUtil();
        String  token = jwt.generateToken(42L, "user@test.com", "email");

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertEquals(42L, jwt.getUserId(token));
        assertEquals("user@test.com", jwt.getEmail(token));
    }

    @Test @DisplayName("JWT: tampered token is rejected")
    void jwtTamperedTokenRejected() {
        JwtUtil jwt   = new JwtUtil();
        String  token = jwt.generateToken(1L, "user@test.com", "email");
        String  bad   = token.substring(0, token.length() - 5) + "XXXXX";
        assertThrows(JwtException.class, () -> jwt.validateToken(bad));
    }

    @Test @DisplayName("JWT: isValid returns false for bad token")
    void jwtIsValidReturnsFalse() {
        JwtUtil jwt = new JwtUtil();
        assertFalse(jwt.isValid("not.a.valid.jwt"));
        assertFalse(jwt.isValid(""));
        assertFalse(jwt.isValid(null));
    }

    @Test @DisplayName("JWT: claims contain correct auth method")
    void jwtClaimsAuthMethod() {
        JwtUtil jwt   = new JwtUtil();
        String  token = jwt.generateToken(5L, "google@test.com", "google");
        Claims  claims = jwt.validateToken(token);
        assertEquals("google", claims.get("auth", String.class));
    }
}
