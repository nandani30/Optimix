package com.optimix.api.middleware;

import io.javalin.http.Context;
import io.javalin.http.TooManyRequestsResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter for authentication endpoints.
 * - Login/signup: 10 attempts per IP per 15 minutes (prevent brute force)
 * - OTP verify/resend: 20 per IP per 15 minutes (more lenient — user needs retries)
 */
public class RateLimitMiddleware {

    private static final long WINDOW_MS = 15 * 60 * 1000L; // 15 minutes

    // IP → [count, windowStart]
    private static final Map<String, long[]> loginAttempts  = new ConcurrentHashMap<>();
    private static final Map<String, long[]> otpAttempts    = new ConcurrentHashMap<>();

    public static void checkAuthRate(Context ctx) {
        String path = ctx.path();

        // OTP-related endpoints get a more lenient limit
        if (path.contains("verify-otp") || path.contains("resend-otp")) {
            checkRate(ctx.ip(), otpAttempts, 20);
        } else {
            // login, signup, google — stricter
            checkRate(ctx.ip(), loginAttempts, 10);
        }
    }

    private static void checkRate(String ip, Map<String, long[]> store, int max) {
        long now = System.currentTimeMillis();

        store.compute(ip, (key, existing) -> {
            if (existing == null || (now - existing[1]) > WINDOW_MS) {
                return new long[]{ 1, now };
            }
            existing[0]++;
            return existing;
        });

        long[] record = store.get(ip);
        if (record != null && record[0] > max) {
            long resetIn = (WINDOW_MS - (now - record[1])) / 1000;
            throw new TooManyRequestsResponse(
                "Too many attempts. Please wait " + resetIn + " seconds and try again.");
        }
    }
}
