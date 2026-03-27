package com.optimix.api.controller;

import com.optimix.auth.AuthService;
import com.optimix.model.dto.*;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authentication REST controller.
 *
 * POST /api/auth/signup      → initiate signup, send OTP
 * POST /api/auth/verify-otp  → verify OTP, create account, return JWT
 * POST /api/auth/resend-otp  → resend OTP
 * POST /api/auth/google      → Google OAuth login/signup
 * POST /api/auth/login       → email+password login
 * POST /api/auth/logout      → client-side token discard
 * GET  /api/auth/me          → return current user info from JWT
 */
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService = new AuthService();

    // ── POST /api/auth/signup ─────────────────────────────────────────────────
    public void signup(Context ctx) {
        try {
            SignupRequest req = ctx.bodyAsClass(SignupRequest.class);

            validateSignupInput(req);

            authService.initiateSignup(req.email.trim().toLowerCase(), req.fullName.trim(), req.password);

            ctx.status(200).json(new MessageResponse(
                "Verification code sent to " + req.email + ". Check your inbox (and spam folder)."
            ));

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Signup error", e);
            ctx.status(500).json(new ErrorResponse("Signup failed. Please try again."));
        }
    }

    // ── POST /api/auth/verify-otp ─────────────────────────────────────────────
    public void verifyOtp(Context ctx) {
        try {
            OtpVerifyRequest req = ctx.bodyAsClass(OtpVerifyRequest.class);

            if (req.email == null || req.email.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Email is required.")); return;
            }
            if (req.otpCode == null || !req.otpCode.matches("\\d{6}")) {
                ctx.status(400).json(new ErrorResponse("OTP must be a 6-digit code.")); return;
            }

            AuthResponse response = authService.verifyOtp(
                req.email.trim().toLowerCase(), req.otpCode.trim());

            ctx.status(200).json(response);

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("OTP verify error", e);
            ctx.status(500).json(new ErrorResponse("Verification failed. Please try again."));
        }
    }

    // ── POST /api/auth/resend-otp ─────────────────────────────────────────────
    public void resendOtp(Context ctx) {
        try {
            ResendOtpRequest req = ctx.bodyAsClass(ResendOtpRequest.class);

            if (req.email == null || req.email.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Email is required.")); return;
            }

            authService.resendOtp(req.email.trim().toLowerCase());
            ctx.status(200).json(new MessageResponse("New code sent to " + req.email));

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Resend OTP error", e);
            ctx.status(500).json(new ErrorResponse("Failed to resend code. Please try again."));
        }
    }

    // ── POST /api/auth/google ─────────────────────────────────────────────────
    public void googleLogin(Context ctx) {
        try {
            GoogleAuthRequest req = ctx.bodyAsClass(GoogleAuthRequest.class);

            if (req.googleOauthToken == null || req.googleOauthToken.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Google token is required.")); return;
            }

            AuthResponse response = authService.googleLogin(req.googleOauthToken);
            ctx.status(200).json(response);

        } catch (IllegalArgumentException e) {
            ctx.status(401).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Google login error", e);
            ctx.status(500).json(new ErrorResponse("Google sign-in failed. Please try again."));
        }
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────
    public void login(Context ctx) {
        try {
            LoginRequest req = ctx.bodyAsClass(LoginRequest.class);

            if (req.email == null || req.email.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Email is required.")); return;
            }
            if (req.password == null || req.password.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Password is required.")); return;
            }

            AuthResponse response = authService.login(
                req.email.trim().toLowerCase(), req.password);

            ctx.status(200).json(response);

        } catch (IllegalArgumentException e) {
            ctx.status(401).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Login error", e);
            ctx.status(500).json(new ErrorResponse("Login failed. Please try again."));
        }
    }

    // ── POST /api/auth/logout ─────────────────────────────────────────────────
    public void logout(Context ctx) {
        // JWT is stateless — the client discards the token.
        // For a denylist, store the token's jti claim in Redis with TTL = remaining expiry.
        ctx.status(200).json(new MessageResponse("Logged out successfully."));
    }

    // ── GET /api/auth/me ──────────────────────────────────────────────────────
    public void me(Context ctx) {
        // AuthMiddleware already validated the token and set these attributes
        Long userId = ctx.attribute("userId");
        String email  = ctx.attribute("email");

        // Return minimal info from token — full profile could be fetched from DB if needed
        ctx.status(200).json(new MessageResponse("Authenticated as: " + email));
    }


    // ── POST /api/auth/forgot-password ────────────────────────────────────────
    public void forgotPassword(Context ctx) {
        try {
            com.optimix.model.dto.ResendOtpRequest req = ctx.bodyAsClass(com.optimix.model.dto.ResendOtpRequest.class);
            if (req.email == null || req.email.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Email is required.")); return;
            }
            authService.forgotPassword(req.email.trim().toLowerCase());
            // Always return success — don't reveal if email exists
            ctx.status(200).json(new MessageResponse(
                "If that email is registered, a reset code has been sent."));
        } catch (Exception e) {
            log.error("Forgot password error", e);
            ctx.status(500).json(new ErrorResponse("Failed to send reset code. Please try again."));
        }
    }

    // ── POST /api/auth/reset-password ─────────────────────────────────────────
    public void resetPassword(Context ctx) {
        try {
            var req = ctx.bodyAsClass(ResetPasswordRequest.class);
            if (req.email == null || req.otpCode == null || req.newPassword == null) {
                ctx.status(400).json(new ErrorResponse("Email, code, and new password are required.")); return;
            }
            if (req.newPassword.length() < 8) {
                ctx.status(400).json(new ErrorResponse("Password must be at least 8 characters.")); return;
            }
            authService.resetPassword(req.email.trim().toLowerCase(), req.otpCode.trim(), req.newPassword);
            ctx.status(200).json(new MessageResponse("Password reset successfully. You can now sign in."));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Reset password error", e);
            ctx.status(500).json(new ErrorResponse("Failed to reset password. Please try again."));
        }
    }

    // Simple DTO for reset password request
    public static class ResetPasswordRequest {
        public String email;
        public String otpCode;
        public String newPassword;
    }

    // ── Input validation ──────────────────────────────────────────────────────

    private void validateSignupInput(SignupRequest req) {
        if (req.fullName == null || req.fullName.isBlank())
            throw new IllegalArgumentException("Full name is required.");

        if (req.email == null || !req.email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new IllegalArgumentException("Please enter a valid email address.");

        if (req.password == null || req.password.length() < 8)
            throw new IllegalArgumentException("Password must be at least 8 characters.");

        if (!req.password.matches(".*[A-Z].*"))
            throw new IllegalArgumentException("Password must contain at least one uppercase letter.");

        if (!req.password.matches(".*[a-z].*"))
            throw new IllegalArgumentException("Password must contain at least one lowercase letter.");

        if (!req.password.matches(".*[0-9].*"))
            throw new IllegalArgumentException("Password must contain at least one number.");
    }
}
