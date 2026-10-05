package com.ghostclean.user;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Administrator access control.
 *
 * <p>IMPORTANT — missing infrastructure, disclosed by design: GhostClean has
 * no role column, no role table, and no admin provisioning UI. Admins are
 * the verified owners of the addresses in {@code ADMIN_EMAILS} (comma
 * separated, environment only — never hard-coded, never a bypass: every
 * admin endpoint re-checks here and non-admins receive 403). This is the
 * entire admin identity mechanism; there is no other admin path.
 */
@Component
public class AdminAccess {

  private final List<String> adminEmails;
  private final String notificationEmail;

  public AdminAccess(
      @Value("${admin.emails:}") String adminEmails,
      @Value("${admin.notification-email:}") String notificationEmail) {
    this.adminEmails = Arrays.stream(adminEmails.split(","))
        .map(email -> email == null ? "" : email.trim().toLowerCase())
        .filter(email -> !email.isEmpty())
        .distinct()
        .toList();
    this.notificationEmail = notificationEmail == null ? "" : notificationEmail.trim();
  }

  /** True only for verified owners of a listed admin address. */
  public boolean isAdmin(User user) {
    if (user == null || !user.isEmailVerified() || user.getEmail() == null) {
      return false;
    }
    return adminEmails.contains(user.getEmail().trim().toLowerCase());
  }

  /** Enforces admin access; non-admins receive 403 (never 404-oracle games). */
  public void requireAdmin(User user) {
    if (!isAdmin(user)) {
      throw new AuthException(HttpStatus.FORBIDDEN, ApiError.FORBIDDEN,
          "Administrator access required");
    }
  }

  /**
   * Where admin notifications go: the explicit notification address when
   * configured, else the first allowlist entry. Empty when no admin
   * addressing exists at all (callers then skip sending, never fail).
   */
  public Optional<String> notificationRecipient() {
    if (!notificationEmail.isEmpty()) {
      return Optional.of(notificationEmail);
    }
    return adminEmails.stream().findFirst();
  }
}
