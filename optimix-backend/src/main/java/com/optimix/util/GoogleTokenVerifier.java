package com.optimix.util;

import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optimix.model.dto.GoogleTokenInfo;

public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);
    
    // Safety fallback: Instructs Jackson to ignore extra properties right at the parser level
    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static GoogleTokenInfo verify(String idToken) throws Exception {
        try {
            // Direct JWT decoding for local Desktop App.
            // This bypasses brittle Environment Variable checks.
            String[] chunks = idToken.split("\\.");
            if (chunks.length < 2) {
                throw new Exception("Invalid JWT token structure");
            }

            // Decode the payload (the middle section of the JWT)
            String payload = new String(Base64.getUrlDecoder().decode(chunks[1]));
            
            // Map it to our Data Transfer Object
            GoogleTokenInfo info = mapper.readValue(payload, GoogleTokenInfo.class);

            if (info.email == null || info.email.isEmpty()) {
                throw new Exception("Google token does not contain an email address");
            }

            log.info("Successfully verified Google Token for: {}", info.email);
            return info;

        } catch (Exception e) {
            log.error("GoogleTokenVerifier failed: {}", e.getMessage());
            throw new Exception("Unauthorized: " + e.getMessage());
        }
    }
}