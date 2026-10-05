package com.ghostclean.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development-safe sender (Phase 2).
 *
 * <p>Mode {@code silent} (default): delivers nothing; the token is issued and
 * stored, and tests/local tooling obtain it through controlled channels.
 * Mode {@code log}: writes the account link to the application log for
 * local development without a mail provider. {@code log} mode prints a
 * loud startup warning and MUST never be enabled in production.
 */
@Component
public class LoggingVerificationEmailSender implements VerificationEmailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingVerificationEmailSender.class);

  private final AuthProperties properties;

  public LoggingVerificationEmailSender(AuthProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void warnIfLogMode() {
    if ("log".equalsIgnoreCase(properties.getMailMode())) {
      log.warn("Verification mail mode=log: verification links will appear in logs. "
          + "Local development only — never enable in production.");
    }
  }

  @Override
  public void sendVerificationEmail(String toEmail, String verificationLink) {
    if ("log".equalsIgnoreCase(properties.getMailMode())) {
      // Deliberately no recipient address: the link alone suffices for dev.
      log.info("Verification link (local development only): {}", verificationLink);
    } else {
      log.debug("Verification token issued; delivery suppressed (mail-mode=silent)");
    }
  }

  @Override
  public void sendPasswordResetEmail(String toEmail, String resetLink) {
    if ("log".equalsIgnoreCase(properties.getMailMode())) {
      // Deliberately no recipient address: the link alone suffices for dev.
      log.info("Password reset link (local development only): {}", resetLink);
    } else {
      log.debug("Password reset token issued; delivery suppressed (mail-mode=silent)");
    }
  }

  @Override
  public void sendAdminNotification(String toEmail, String subject, String textBody) {
    // Operational notice only: subject line shape without recipient or body.
    log.debug("Admin notification suppressed (mail-mode={}): {}", properties.getMailMode(), subject);
  }
}
