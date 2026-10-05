package com.ghostclean.oauth;

import com.ghostclean.common.OAuthException;

import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use authorization state (CSRF/replay protection for OAuth).
 *
 * <p>Guarantees: 256-bit entropy, 10-minute expiry, hash-only storage,
 * exactly-once consumption, and server-side binding to the initiating
 * GhostClean user. Consuming an expired row still marks it used, so a stale
 * state can never become valid later.
 */
@Service
public class OAuthStateService {

  private final OAuthStateRepository states;
  private final UserRepository users;
  private final SecureRandom random = new SecureRandom();

  public OAuthStateService(OAuthStateRepository states, UserRepository users) {
    this.states = states;
    this.users = users;
  }

  /** Issues a fresh state + PKCE pair bound to the user. */
  @Transactional
  public CreatedState create(UUID userId, String provider, String returnUrl, Duration ttl) {
    User user = users.findById(userId).orElseThrow(
        () -> new IllegalStateException("State owner does not exist"));
    String rawState = randomToken();
    String verifier = randomToken();
    OAuthState row = new OAuthState(user, provider, sha256Hex(rawState), verifier,
        returnUrl, Instant.now().plus(ttl));
    states.save(row);
    return new CreatedState(rawState, codeChallenge(verifier), row.getExpiresAt());
  }

  /**
   * Consumes a state exactly once, resolving the bound user. Consumption is
   * gated by a single atomic conditional update, so concurrent callbacks
   * presenting the same state serialize and only the first wins; losers
   * observe zero claimed rows. The state is bound to the provider it was
   * issued for: presenting a Google state to another provider's callback
   * (or vice versa) is a mismatch and does not burn the state. Consuming
   * an expired row still marks it used, so a stale state can never become
   * valid later.
   */
  @Transactional
  public ConsumedState consume(String rawState, String expectedProvider) {
    if (rawState == null || rawState.isBlank()) {
      throw OAuthException.stateMismatch();
    }
    String hash = sha256Hex(rawState.trim());
    OAuthState row = states.findByStateHash(hash).orElse(null);
    if (row == null) {
      throw OAuthException.stateMismatch();
    }
    if (expectedProvider == null
        || !expectedProvider.trim().equalsIgnoreCase(row.getProvider())) {
      throw OAuthException.stateMismatch();
    }
    Instant now = Instant.now();
    if (states.claimUnusedByStateHash(hash, now) != 1) {
      throw OAuthException.stateMismatch();
    }
    OAuthState claimed = states.findByStateHash(hash)
        .orElseThrow(OAuthException::stateMismatch);
    if (claimed.getExpiresAt().isBefore(now)) {
      throw OAuthException.stateMismatch();
    }
    return new ConsumedState(claimed.getUser().getId(), claimed.getProvider(),
        claimed.getCodeVerifier(), claimed.getReturnUrl());
  }

  /** PKCE S256 challenge for a verifier (pure function, also used by tests). */
  public static String codeChallenge(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  public record CreatedState(String state, String codeChallenge, Instant expiresAt) {
  }

  public record ConsumedState(UUID userId, String provider, String codeVerifier, String returnUrl) {
  }
}
