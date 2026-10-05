package com.ghostclean.auth;

/**
 * GhostClean password-reset email content.
 *
 * <p>Pure function of the reset link: no I/O, no secrets, no randomness.
 * The link already embeds the single-use token issued by
 * {@code AuthService}; this builder only presents it with GhostClean
 * branding, an explicit action, expiry information, and a security
 * notice. Never includes passwords, user ids, or any other credential
 * or account material.
 */
public final class PasswordResetEmailContent {

  private PasswordResetEmailContent() {
  }

  public static EmailBody build(String resetLink) {
    String safeLink = resetLink == null ? "" : resetLink;
    String subject = "Reset your GhostClean password";
    String text = "Hello from GhostClean.\n"
        + "\n"
        + "We received a request to reset the password for your GhostClean account. "
        + "Open this link to choose a new password:\n"
        + safeLink + "\n"
        + "\n"
        + "The link expires 30 minutes after it was issued and can be used "
        + "only once. After resetting, you can log in with your new password.\n"
        + "If you did not request a password reset, please ignore this email — "
        + "your password stays unchanged and no further action is needed.\n"
        + "\n"
        + "— The GhostClean team";
    String html = "<!doctype html><html><body style=\"font-family:sans-serif\">"
        + "<h2>Reset your GhostClean password</h2>"
        + "<p>We received a request to reset the password for your GhostClean "
        + "account. Click the button below to choose a new password:</p>"
        + "<p><a href=\"" + escapeHtml(safeLink) + "\">Reset your password</a></p>"
        + "<p>If the button does not work, copy and open this link:<br>"
        + "<code>" + escapeHtml(safeLink) + "</code></p>"
        + "<p>The link expires 30 minutes after it was issued and can be used "
        + "only once. After resetting, you can log in with your new password.</p>"
        + "<p>If you did not request a password reset, please ignore this email — "
        + "your password stays unchanged and no further action is needed.</p>"
        + "<p>— The GhostClean team</p>"
        + "</body></html>";
    return new EmailBody(subject, text, html);
  }

  static String escapeHtml(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  public record EmailBody(String subject, String text, String html) {
  }
}
