package com.ghostclean.auth;

/**
 * Verification-mail delivery failure (ADR-038).
 *
 * <p>Carries only a coarse outcome {@code category} for safe logging
 * ({@code transport}, {@code timeout}, {@code http-auth},
 * {@code http-error}). Never carries the API key, the recipient address,
 * the verification link, or provider response bodies.
 */
public class VerificationMailException extends RuntimeException {

  private final String category;

  public VerificationMailException(String category) {
    super("Verification email delivery failed");
    this.category = category;
  }

  public String getCategory() {
    return category;
  }
}
