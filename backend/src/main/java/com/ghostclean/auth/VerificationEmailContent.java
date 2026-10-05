package com.ghostclean.auth;

/**
 * GhostClean verification-email content (ADR-038).
 *
 * <p>Pure function of the verification link: no I/O, no secrets, no
 * randomness. The link already embeds the single-use token issued by
 * {@code AuthService}; this builder only presents it with GhostClean
 * branding, an explicit action, expiry information, and a security
 * notice. Never includes passwords or any other credential material.
 */
public final class VerificationEmailContent {

  private VerificationEmailContent() {
  }

  public static EmailBody build(String verificationLink) {
    String safeLink = verificationLink == null ? "" : verificationLink;
    String subject = "Verify your GhostClean account";
    String text = "Welcome to GhostClean.\n"
        + "\n"
        + "Please verify your email address by opening this link:\n"
        + safeLink + "\n"
        + "\n"
        + "The link verifies your account immediately, expires 24 hours after "
        + "it was issued, and can be used only once. After verifying, you can log in.\n"
        + "If you did not create a GhostClean account, please ignore this email — "
        + "no further action is needed and no account will be activated.\n"
        + "\n"
        + "— The GhostClean team";
    String html = "<!doctype html><html><body style=\"font-family:sans-serif\">"
        + "<h2>Welcome to GhostClean</h2>"
        + "<p>Please verify your email address by clicking the button below:</p>"
        + "<p><a href=\"" + escapeHtml(safeLink) + "\">Verify your email</a></p>"
        + "<p>If the button does not work, copy and open this link:<br>"
        + "<code>" + escapeHtml(safeLink) + "</code></p>"
        + "<p>The link verifies your account immediately, expires 24 hours after "
        + "it was issued, and can be used only once. After verifying, you can log in.</p>"
        + "<p>If you did not create a GhostClean account, please ignore this email — "
        + "no further action is needed and no account will be activated.</p>"
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
