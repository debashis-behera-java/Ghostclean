package com.ghostclean.auth;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Password-strength policy (Phase 2): 12–128 chars, not blank, and not on the
 * obvious-credential blocklist. Deliberately free of exotic composition rules
 * that harm usability; length is the primary control, BCrypt the backstop.
 */
@Component
public class PasswordPolicy {

  private static final Set<String> BLOCKLIST = Set.of(
      "password", "password1", "password123", "123456", "12345678", "123456789",
      "qwerty", "qwerty123", "letmein", "welcome", "admin123", "ghostclean",
      "changeme", "test123456", "aaaaaaaaaaaa", "abcdefghijkl");

  private final AuthProperties properties;

  public PasswordPolicy(AuthProperties properties) {
    this.properties = properties;
  }

  /** Returns true when the candidate satisfies the policy. */
  public boolean accepts(String password) {
    if (password == null || password.isBlank()) {
      return false;
    }
    if (password.length() < properties.getPasswordMinLength()
        || password.length() > properties.getPasswordMaxLength()) {
      return false;
    }
    return !BLOCKLIST.contains(password.toLowerCase());
  }
}
