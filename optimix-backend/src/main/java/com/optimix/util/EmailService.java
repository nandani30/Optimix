package com.optimix.util;

import com.optimix.config.AppConfig;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Email service for sending OTP verification emails.
 *
 * Uses Jakarta Mail (formerly JavaMail) over SMTP with TLS.
 * Configured for Gmail by default — works with any SMTP provider.
 *
 * ── Gmail Setup ───────────────────────────────────────────────────────────
 *  1. Enable 2-Step Verification on your Google account
 *  2. Go to: myaccount.google.com → Security → App Passwords
 *  3. Generate an App Password for "Mail" → "Other (Custom name)" → "Optimix"
 *  4. Use that 16-char password as SMTP_PASSWORD (not your real Gmail password)
 *  5. Set SMTP_USER to your Gmail address
 *
 * Emails are sent asynchronously so the HTTP response isn't blocked.
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    // Single thread pool for async email sending
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-sender");
        t.setDaemon(true);
        return t;
    });

    /**
     * Send an OTP verification email asynchronously.
     *
     * @param toEmail    Recipient email address
     * @param toName     Recipient's name (for personalized greeting)
     * @param otpCode    6-digit OTP code
     */
    public void sendOtpEmail(String toEmail, String toName, String otpCode) {
        executor.submit(() -> {
            try {
                doSendOtp(toEmail, toName, otpCode);
                log.info("OTP email sent to: {}", toEmail);
            } catch (Exception e) {
                log.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
            }
        });
    }

    private void doSendOtp(String toEmail, String toName, String otpCode) throws Exception {
        String smtpUser = AppConfig.getSmtpUser();
        String smtpPass = AppConfig.getSmtpPassword();
        String fromName = AppConfig.getSmtpFromName();

        // Abort gracefully if SMTP not configured yet (common during development)
        if (smtpUser.isBlank() || smtpUser.startsWith("YOUR_")) {
            log.warn("SMTP not configured. Would have sent OTP {} to {}. Set SMTP_USER and SMTP_PASSWORD.", otpCode, toEmail);
            return;
        }

        // Build SMTP session
        Properties props = new Properties();
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host",            AppConfig.getSmtpHost());
        props.put("mail.smtp.port",            String.valueOf(AppConfig.getSmtpPort()));
        props.put("mail.smtp.ssl.trust",       AppConfig.getSmtpHost());

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUser, smtpPass);
            }
        });

        // Build email message
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpUser, fromName));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        message.setSubject("Your Optimix verification code: " + otpCode);

        // HTML email body
        String htmlBody = buildOtpEmailHtml(toName, otpCode);
        message.setContent(htmlBody, "text/html; charset=UTF-8");

        Transport.send(message);
    }

    private String buildOtpEmailHtml(String name, String otp) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="margin:0;padding:0;background:#0D1117;font-family:Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0">
                <tr>
                  <td align="center" style="padding:40px 20px;">
                    <table width="480" cellpadding="0" cellspacing="0"
                           style="background:#161B22;border-radius:12px;border:1px solid #30363D;overflow:hidden;">

                      <!-- Header -->
                      <tr>
                        <td style="padding:32px;text-align:center;border-bottom:1px solid #30363D;">
                          <div style="font-size:28px;margin-bottom:8px;">⚡</div>
                          <h1 style="margin:0;font-size:20px;color:#E6EDF3;letter-spacing:-0.5px;">Optimix</h1>
                          <p style="margin:6px 0 0;font-size:12px;color:#8B949E;">SQL Optimization Tool</p>
                        </td>
                      </tr>

                      <!-- Body -->
                      <tr>
                        <td style="padding:32px;">
                          <p style="margin:0 0 16px;font-size:15px;color:#E6EDF3;">
                            Hi <strong>%s</strong>,
                          </p>
                          <p style="margin:0 0 24px;font-size:14px;color:#8B949E;line-height:1.6;">
                            Use the code below to verify your email and complete your Optimix account setup.
                            This code expires in <strong style="color:#E6EDF3;">10 minutes</strong>.
                          </p>

                          <!-- OTP Code Box -->
                          <div style="text-align:center;margin:32px 0;">
                            <div style="display:inline-block;background:#0D1117;border:2px solid #39D353;
                                        border-radius:8px;padding:20px 40px;">
                              <span style="font-size:40px;font-weight:bold;letter-spacing:12px;
                                           color:#39D353;font-family:'Courier New',monospace;">%s</span>
                            </div>
                          </div>

                          <p style="margin:0;font-size:13px;color:#484F58;line-height:1.5;">
                            If you didn't request this, you can safely ignore this email.
                            Someone may have entered your email address by mistake.
                          </p>
                        </td>
                      </tr>

                      <!-- Footer -->
                      <tr>
                        <td style="padding:20px 32px;border-top:1px solid #30363D;text-align:center;">
                          <p style="margin:0;font-size:11px;color:#484F58;">
                            © 2024 Optimix · SQL Optimization Tool
                          </p>
                        </td>
                      </tr>

                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.formatted(name, otp);
    }
    /**
     * Send a password reset OTP email.
     */
    public void sendPasswordResetEmail(String toEmail, String otpCode) {
        executor.submit(() -> {
            try {
                doSendReset(toEmail, otpCode);
                log.info("Password reset email sent to: {}", toEmail);
            } catch (Exception e) {
                log.error("Failed to send reset email to {}: {}", toEmail, e.getMessage());
            }
        });
    }

    private void doSendReset(String toEmail, String otpCode) throws Exception {
        String smtpUser = com.optimix.config.AppConfig.getSmtpUser();
        String smtpPass = com.optimix.config.AppConfig.getSmtpPassword();
        String fromName = com.optimix.config.AppConfig.getSmtpFromName();

        if (smtpUser.isBlank() || smtpUser.startsWith("YOUR_")) {
            log.warn("SMTP not configured. Reset OTP for {} is: {}", toEmail, otpCode);
            return;
        }

        java.util.Properties props = new java.util.Properties();
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host",            com.optimix.config.AppConfig.getSmtpHost());
        props.put("mail.smtp.port",            String.valueOf(com.optimix.config.AppConfig.getSmtpPort()));
        props.put("mail.smtp.ssl.trust",       com.optimix.config.AppConfig.getSmtpHost());

        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(smtpUser, smtpPass);
            }
        });

        jakarta.mail.Message message = new jakarta.mail.internet.MimeMessage(session);
        message.setFrom(new jakarta.mail.internet.InternetAddress(smtpUser, fromName));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO,
            jakarta.mail.internet.InternetAddress.parse(toEmail));
        message.setSubject("Reset your Optimix password — Code: " + otpCode);

        String html = """
            <!DOCTYPE html><html><body style="background:#0D1117;font-family:Arial,sans-serif;margin:0;padding:40px 20px;">
            <table width="100%%" cellpadding="0" cellspacing="0"><tr><td align="center">
            <table width="480" style="background:#161B22;border-radius:12px;border:1px solid #30363D;overflow:hidden;">
              <tr><td style="padding:32px;text-align:center;border-bottom:1px solid #30363D;">
                <div style="font-size:28px;margin-bottom:8px;">🔐</div>
                <h1 style="margin:0;font-size:20px;color:#E6EDF3;">Reset your password</h1>
              </td></tr>
              <tr><td style="padding:32px;">
                <p style="color:#E6EDF3;font-size:15px;">Use the code below to reset your Optimix password. It expires in <strong>10 minutes</strong>.</p>
                <div style="text-align:center;margin:32px 0;">
                  <div style="display:inline-block;background:#0D1117;border:2px solid #39D353;border-radius:8px;padding:20px 40px;">
                    <span style="font-size:40px;font-weight:bold;letter-spacing:12px;color:#39D353;font-family:monospace;">%s</span>
                  </div>
                </div>
                <p style="color:#484F58;font-size:13px;">If you didn't request this, ignore this email. Your password won't change.</p>
              </td></tr>
            </table></td></tr></table>
            </body></html>
            """.formatted(otpCode);

        message.setContent(html, "text/html; charset=UTF-8");
        jakarta.mail.Transport.send(message);
    }


}
