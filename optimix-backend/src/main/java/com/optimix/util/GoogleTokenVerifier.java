package com.optimix.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optimix.config.AppConfig;
import com.optimix.model.dto.GoogleTokenInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Verifies Google OAuth 2.0 ID tokens by calling Google's tokeninfo endpoint.
 *
 * We deliberately avoid the heavy Google Auth libraries and just call the
 * tokeninfo REST endpoint directly — simpler, fewer dependencies.
 *
 * ── Flow ──────────────────────────────────────────────────────────────────
 *  Frontend sends:  Google ID token (from google.accounts.id.initialize)
 *  We call:         https://oauth2.googleapis.com/tokeninfo?id_token=TOKEN
 *  Google returns:  { sub, email, name, picture, aud, ... }
 *  We verify:       aud matches our GOOGLE_CLIENT_ID
 *
 * ── Frontend setup ────────────────────────────────────────────────────────
 *  1. Go to console.cloud.google.com
 *  2. APIs & Services → Credentials → Create OAuth 2.0 Client ID
 *  3. Application type: Web application
 *  4. Authorized origins: http://localhost:5173 (dev), app:// (Electron)
 *  5. Copy the Client ID → set as GOOGLE_CLIENT_ID env var
 *  6. In frontend: load Google Identity Services script, call
 *     google.accounts.id.initialize({ client_id: '...' })
 *     On credential callback, send response.credential to POST /api/auth/google
 */
public class GoogleTokenVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";
    private static final ObjectMapper mapper  = new ObjectMapper();
    private static final HttpClient   client  = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Verify a Google ID token and return the user info.
     *
     * @param idToken  The raw ID token string from the frontend
     * @return         Verified GoogleTokenInfo with user details
     * @throws IllegalArgumentException if token is invalid or audience doesn't match
     */
    public static GoogleTokenInfo verify(String idToken) throws Exception {
        // Call Google's tokeninfo endpoint
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKENINFO_URL + idToken))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("Invalid Google token. Please sign in again.");
        }

        GoogleTokenInfo info = mapper.readValue(response.body(), GoogleTokenInfo.class);

        // Verify audience (the client_id this token was issued for)
        String expectedClientId = AppConfig.getGoogleClientId();
        if (!expectedClientId.isBlank() && !expectedClientId.startsWith("YOUR_")) {
            if (info.aud == null || !info.aud.equals(expectedClientId)) {
                throw new IllegalArgumentException("Google token audience mismatch. Please reconfigure.");
            }
        }

        // Verify email is confirmed by Google
        if (!"true".equals(info.email_verified)) {
            throw new IllegalArgumentException("Google account email is not verified.");
        }

        if (info.sub == null || info.email == null) {
            throw new IllegalArgumentException("Google token is missing required fields.");
        }

        return info;
    }
}
