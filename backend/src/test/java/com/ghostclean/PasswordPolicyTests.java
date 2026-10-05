package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.auth.AuthProperties;
import com.ghostclean.auth.PasswordPolicy;
import org.junit.jupiter.api.Test;

/** Policy matrix: length bounds, blank rejection, blocklist (case-insensitive). */
class PasswordPolicyTests {

  private final PasswordPolicy policy = new PasswordPolicy(new AuthProperties());

  @Test
  void acceptsStrongPasswords() {
    assertTrue(policy.accepts("Str0ng-Enough-Password"));
    assertTrue(policy.accepts("twelve-chars"));
    assertTrue(policy.accepts("correct horse battery staple!"));
  }

  @Test
  void rejectsShortBlankAndOversized() {
    assertFalse(policy.accepts(null));
    assertFalse(policy.accepts(""));
    assertFalse(policy.accepts("   "));
    assertFalse(policy.accepts("eleven-chr"));
    assertFalse(policy.accepts("x".repeat(129)));
  }

  @Test
  void rejectsBlocklistedCredentials() {
    assertFalse(policy.accepts("Password123"));
    assertFalse(policy.accepts("Abcdefghijkl"));
    assertFalse(policy.accepts("Aaaaaaaaaaaa"));
  }
}
