package com.ghostclean.auth;

/**
 * Account-mail seam (Phase 2 verification, extended with password reset).
 *
 * <p>The production provider is the Resend implementation behind this
 * interface; only development-safe fallbacks exist otherwise. This seam
 * carries account-recovery links only and must never be confused with
 * future deletion-request sending — it carries no request content.
 */
public interface VerificationEmailSender {

  /**
   * Delivers the verification link to the given address. Implementations must
   * never log the link in production; the {@code log} implementation is for
   * local development only and warns at startup.
   */
  void sendVerificationEmail(String toEmail, String verificationLink);

  /**
   * Delivers the password-reset link to the given address. Same delivery,
   * timeout, error-classification, and no-logging discipline as
   * {@link #sendVerificationEmail(String, String)}.
   */
  void sendPasswordResetEmail(String toEmail, String resetLink);

  /**
   * Delivers an administrator notification (plain subject + text body, no
   * links carrying authority, no tokens or secrets). Used for operational
   * notices such as Google connection approval requests. Same failure
   * discipline: throws {@link VerificationMailException} with a coarse
   * category only; callers must never fail user-facing work solely because
   * the notification failed.
   */
  void sendAdminNotification(String toEmail, String subject, String textBody);
}
