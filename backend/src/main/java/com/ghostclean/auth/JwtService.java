package com.ghostclean.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * HS256 access-token mint/validation (Phase 2).
 *
 * <p>Claims are minimal: {@code iss}, {@code sub} (user id), {@code jti},
 * {@code iat}/{@code exp}, {@code scope=user}, {@code ev} (verified-at-mint
 * hint; the database remains the source of truth). No passwords, no OAuth
 * material, no mailbox data. The signing secret comes only from
 * {@code JWT_SIGNING_KEY} and must be at least 256 bits — otherwise the bean
 * refuses to construct and the application fails fast.
 */
@Component
public class JwtService {

  private static final int MIN_SECRET_BYTES = 32;

  private final SecretKey key;
  private final String issuer;
  private final Duration defaultTtl;

  public JwtService(AuthProperties properties) {
    String secret = properties.getJwtSecret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "Missing required JWT configuration: set JWT_SIGNING_KEY "
              + "(at least 32 characters). Refusing to start without it.");
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "Invalid JWT configuration: signing secret must be at least 256 bits.");
    }
    this.key = Keys.hmacShaKeyFor(bytes);
    this.issuer = properties.getIssuer();
    this.defaultTtl = properties.getAccessTokenTtl();
  }

  public String createAccessToken(UUID userId, boolean emailVerified) {
    return createAccessToken(userId, emailVerified, defaultTtl);
  }

  /** Mint with an explicit lifetime (used by tests to craft expired tokens). */
  public String createAccessToken(UUID userId, boolean emailVerified, Duration ttl) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(issuer)
        .subject(userId.toString())
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .claim("scope", "user")
        .claim("ev", emailVerified)
        .signWith(key)
        .compact();
  }

  /** Parses and validates signature, issuer, scope, and expiration. */
  public ParsedToken parse(String token) {
    try {
      Claims claims = Jwts.parser()
          .verifyWith(key)
          .requireIssuer(issuer)
          .build()
          .parseSignedClaims(token)
          .getPayload();
      if (!"user".equals(claims.get("scope", String.class))) {
        throw new JwtException("Unexpected token scope");
      }
      return new ParsedToken(
          UUID.fromString(claims.getSubject()),
          Boolean.TRUE.equals(claims.get("ev", Boolean.class)),
          claims.getId(),
          claims.getExpiration().toInstant());
    } catch (IllegalArgumentException ex) {
      throw new JwtException("Malformed token", ex);
    }
  }

  /** Validated access-token claims (no sensitive data). */
  public record ParsedToken(UUID userId, boolean emailVerified, String jwtId, Instant expiresAt) {
  }
}
