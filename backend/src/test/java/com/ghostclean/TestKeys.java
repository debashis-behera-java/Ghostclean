package com.ghostclean;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Obviously synthetic, non-production test values.
 *
 * <p>Kept in a Docker-free helper so pure unit tests never trigger container
 * startup through class initialization.
 */
public final class TestKeys {

  private TestKeys() {
  }

  /**
   * 32-byte test key: the ASCII string "12345678901234567890123456789012",
   * Base64-encoded inline. Valid length, zero secrecy, test-only.
   */
  public static String syntheticEncryptionKey() {
    return Base64.getEncoder().encodeToString(
        "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Synthetic JWT signing secret (44 chars, ≥256 bits). Obviously
   * non-production; test-only.
   */
  public static String syntheticJwtSecret() {
    return "test-only-jwt-signing-secret-0123456789abcdef";
  }
}
