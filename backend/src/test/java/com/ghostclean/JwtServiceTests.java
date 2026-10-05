package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.auth.AuthProperties;
import com.ghostclean.auth.JwtService;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** JWT mint/validation: claims, expiry, tamper, issuer, fail-fast secrets. */
class JwtServiceTests {

  private AuthProperties properties(String secret) {
    AuthProperties properties = new AuthProperties();
    properties.setJwtSecret(secret);
    properties.setIssuer("ghostclean");
    return properties;
  }

  @Test
  void roundTripCarriesMinimalClaims() {
    JwtService service = new JwtService(properties(TestKeys.syntheticJwtSecret()));
    UUID userId = UUID.randomUUID();
    String token = service.createAccessToken(userId, true);
    assertEquals(3, token.split("\\.").length);
    JwtService.ParsedToken parsed = service.parse(token);
    assertEquals(userId, parsed.userId());
    assertTrue(parsed.emailVerified());
    // Compact serialization carries no readable PII.
    assertFalse(token.contains(userId.toString()));
  }

  @Test
  void expiredTokenFails() {
    JwtService service = new JwtService(properties(TestKeys.syntheticJwtSecret()));
    String token = service.createAccessToken(UUID.randomUUID(), true, Duration.ofSeconds(-60));
    assertThrows(JwtException.class, () -> service.parse(token));
  }

  @Test
  void tamperedTokenFails() {
    JwtService service = new JwtService(properties(TestKeys.syntheticJwtSecret()));
    String token = service.createAccessToken(UUID.randomUUID(), false);
    String tampered = token.substring(0, token.length() - 2) + "AA";
    assertThrows(JwtException.class, () -> service.parse(tampered));
  }

  @Test
  void foreignIssuerFails() {
    // Same key, different issuer: isolates issuer validation from signature.
    AuthProperties foreignProps = properties(TestKeys.syntheticJwtSecret());
    foreignProps.setIssuer("other");
    JwtService other = new JwtService(foreignProps);
    String foreign = other.createAccessToken(UUID.randomUUID(), true);
    JwtService service = new JwtService(properties(TestKeys.syntheticJwtSecret()));
    assertThrows(JwtException.class, () -> service.parse(foreign));
  }

  @Test
  void weakSecretsFailFast() {
    assertThrows(IllegalStateException.class, () -> new JwtService(properties("")));
    assertThrows(IllegalStateException.class, () -> new JwtService(properties("   ")));
    assertThrows(IllegalStateException.class, () -> new JwtService(properties("too-short")));
  }
}
