package com.ghostclean.auth;

import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.common.RlsContext;
import com.ghostclean.user.EmailVerificationToken;
import com.ghostclean.user.EmailVerificationTokenRepository;
import com.ghostclean.user.PasswordResetToken;
import com.ghostclean.user.PasswordResetTokenRepository;
import com.ghostclean.user.RefreshSession;
import com.ghostclean.user.RefreshSessionRepository;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import com.ghostclean.user.UserSettings;
import com.ghostclean.user.UserSettingsRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication orchestration (Phase 2): registration with required email
 * verification, login with lockout, short-lived JWT access tokens, rotating
 * opaque refresh sessions, and logout.
 *
 * <p>Security posture, all deliberate:
 * <ul>
 *   <li>Emails are normalized (trim + lowercase) before every lookup.</li>
 *   <li>Unknown emails take the same code path as known ones (dummy-hash
 *       compare) so timing and responses reveal nothing (no oracle).</li>
 *   <li>Duplicate registration returns the identical 201 as a fresh one.</li>
 *   <li>Only hashes of verification/refresh tokens touch the database.</li>
 *   <li>Logs carry user ids, never emails, passwords, or tokens.</li>
 * </ul>
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private final UserRepository users;
  private final EmailVerificationTokenRepository verificationTokens;
  private final PasswordResetTokenRepository resetTokens;
  private final RefreshSessionRepository sessions;
  private final UserSettingsRepository settings;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final JwtService jwtService;
  private final AuthProperties properties;
  private final AuthRateLimiter rateLimiter;
  private final AuditService audit;
  private final RlsContext rls;
  private final VerificationEmailSender mailer;
  private final String appBaseUrl;
  /**
   * Self-reference for must-commit boundaries: mutations that accompany a
   * thrown error (failed-attempt counter, token consumption, chain
   * revocation) run {@code REQUIRES_NEW} so the error response never rolls
   * them back.
   */
  private final AuthService self;

  /** Precomputed dummy hash so unknown-email logins cost the same as real ones. */
  private final String dummyHash;

  private final SecureRandom random = new SecureRandom();

  public AuthService(
      UserRepository users,
      EmailVerificationTokenRepository verificationTokens,
      PasswordResetTokenRepository resetTokens,
      RefreshSessionRepository sessions,
      UserSettingsRepository settings,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy,
      JwtService jwtService,
      AuthProperties properties,
      AuthRateLimiter rateLimiter,
      AuditService audit,
      RlsContext rls,
      VerificationEmailSender mailer,
      @Value("${app.base-url:http://localhost:8080}") String appBaseUrl,
      @Lazy AuthService self) {
    this.users = users;
    this.verificationTokens = verificationTokens;
    this.resetTokens = resetTokens;
    this.sessions = sessions;
    this.settings = settings;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
    this.jwtService = jwtService;
    this.properties = properties;
    this.rateLimiter = rateLimiter;
    this.audit = audit;
    this.rls = rls;
    this.mailer = mailer;
    this.appBaseUrl = appBaseUrl;
    this.self = self;
    this.dummyHash = passwordEncoder.encode("auth-dummy-comparison-value");
  }

  // -- Registration ----------------------------------------------------------

  /** Registers (or re-offers verification for) an account. Always 201-shaped. */
  @Transactional
  public void register(String email, String password, String clientIp) {
    rateLimiter.check("register:ip:" + clientIp,
        properties.getRateLimits().getRegisterPerMinutePerIp(), Duration.ofMinutes(1));
    String normalized = normalize(email);
    if (!passwordPolicy.accepts(password)) {
      throw AuthException.weakPassword();
    }
    Optional<User> existing = users.findByEmail(normalized);
    if (existing.isPresent()) {
      User user = existing.get();
      if (!user.isEmailVerified()) {
        issueVerificationToken(user);
        audit.emit(AuditEvents.EMAIL_VERIFICATION_REQUESTED, user.getId(), "anonymous",
            "user", user.getId().toString(), Map.of("reason", "register-repeat"));
      }
      // Verified duplicates: identical response, zero side effects (no oracle).
      return;
    }
    User user = new User(normalized, passwordEncoder.encode(password));
    users.save(user);
    settings.save(new UserSettings(user));
    issueVerificationToken(user);
    audit.emit(AuditEvents.USER_REGISTERED, user.getId(), "anonymous",
        "user", user.getId().toString(), Map.of("emailVerified", false));
    log.info("User registered: {}", user.getId());
  }

  // -- Verification ----------------------------------------------------------

  /** Consumes a verification token: single-use, expiring, non-replayable. */
  @Transactional
  public void verify(String rawToken, String clientIp) {
    rateLimiter.check("verify:ip:" + clientIp,
        properties.getRateLimits().getVerifyPerMinutePerIp(), Duration.ofMinutes(1));
    EmailVerificationToken token =
        verificationTokens.findByTokenHash(sha256Hex(rawToken)).orElse(null);
    if (token == null || token.getUsedAt() != null) {
      audit.emitAnonymous(AuditEvents.EMAIL_VERIFICATION_FAILED, Map.of("reason", "invalid"));
      throw AuthException.invalidToken();
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      self.consumeVerificationToken(token.getId());
      audit.emit(AuditEvents.EMAIL_VERIFICATION_FAILED, token.getUser().getId(), "anonymous",
          "user", token.getUser().getId().toString(), Map.of("reason", "expired"));
      throw AuthException.expiredToken();
    }
    User user = token.getUser();
    token.setUsedAt(Instant.now());
    verificationTokens.save(token);
    // Invalidate any sibling active tokens (single-active invariant, resend races).
    for (EmailVerificationToken sibling
        : verificationTokens.findByUser_IdAndUsedAtIsNull(user.getId())) {
      sibling.setUsedAt(Instant.now());
      verificationTokens.save(sibling);
    }
    user.setEmailVerified(true);
    user.setStatus("active");
    users.save(user);
    audit.emit(AuditEvents.EMAIL_VERIFIED, user.getId(), "anonymous",
        "user", user.getId().toString(), Map.of("emailVerified", true));
    log.info("Email verified for user: {}", user.getId());
  }

  /** Re-issues a verification token. Always 202-shaped (no oracle). */
  @Transactional
  public void resendVerification(String email, String clientIp) {
    rateLimiter.check("resend:ip:" + clientIp,
        properties.getRateLimits().getResendPerMinutePerIp(), Duration.ofMinutes(1));
    String normalized = normalize(email);
    rateLimiter.check("resend:email:" + normalized,
        properties.getRateLimits().getResendPerDayPerEmail(), Duration.ofHours(24));
    users.findByEmail(normalized).ifPresent(user -> {
      if (!user.isEmailVerified() && !"locked".equals(user.getStatus())) {
        issueVerificationToken(user);
        audit.emit(AuditEvents.EMAIL_VERIFICATION_REQUESTED, user.getId(), "anonymous",
            "user", user.getId().toString(), Map.of("reason", "resend"));
      }
    });
  }

  // -- Password reset ----------------------------------------------------------

  /**
   * Issues a password-reset token. Always silent-shaped (no oracle): unknown
   * addresses and deleted accounts take the same observable path as known
   * ones. Provider outages keep the persisted token (the caller retries
   * through this same endpoint, which supersedes the previous token).
   */
  @Transactional
  public void requestPasswordReset(String email, String clientIp) {
    rateLimiter.check("forgot:ip:" + clientIp,
        properties.getRateLimits().getForgotPerMinutePerIp(), Duration.ofMinutes(1));
    String normalized = normalize(email);
    rateLimiter.check("forgot:email:" + normalized,
        properties.getRateLimits().getForgotPerDayPerEmail(), Duration.ofHours(24));
    users.findByEmail(normalized).ifPresent(user -> {
      if (user.getDeletedAt() == null) {
        issueResetToken(user);
        audit.emit(AuditEvents.PASSWORD_RESET_REQUESTED, user.getId(), "anonymous",
            "user", user.getId().toString(), Map.of("reason", "forgot"));
      }
    });
  }

  /**
   * Non-consuming validity probe for the browser reset page: true only when
   * a live, unexpired token for a non-deleted account exists. Shares the
   * reset brute-force budget with {@link #resetPassword} so page probes
   * cannot bypass token-guessing protection. Never logs the token.
   */
  @Transactional(readOnly = true)
  public boolean resetTokenUsable(String rawToken, String clientIp) {
    rateLimiter.check("reset:ip:" + clientIp,
        properties.getRateLimits().getResetPerMinutePerIp(), Duration.ofMinutes(1));
    if (rawToken == null || rawToken.isBlank()) {
      return false;
    }
    PasswordResetToken token =
        resetTokens.findByTokenHash(sha256Hex(rawToken.trim())).orElse(null);
    return token != null
        && token.getUsedAt() == null
        && !token.getExpiresAt().isBefore(Instant.now())
        && token.getUser().getDeletedAt() == null;
  }

  /**
   * Consumes a reset token and sets a new password: single-use, expiring
   * (30 minutes by default), non-replayable. Success also revokes every
   * refresh session (existing sessions must not survive a credential
   * change) and clears any brute-force lockout.
   */
  @Transactional
  public void resetPassword(String rawToken, String newPassword, String clientIp) {
    rateLimiter.check("reset:ip:" + clientIp,
        properties.getRateLimits().getResetPerMinutePerIp(), Duration.ofMinutes(1));
    if (rawToken == null || rawToken.isBlank()) {
      audit.emitAnonymous(AuditEvents.PASSWORD_RESET_FAILED, Map.of("reason", "invalid"));
      throw AuthException.invalidToken();
    }
    PasswordResetToken token =
        resetTokens.findByTokenHash(sha256Hex(rawToken.trim())).orElse(null);
    if (token == null || token.getUsedAt() != null) {
      audit.emitAnonymous(AuditEvents.PASSWORD_RESET_FAILED, Map.of("reason", "invalid"));
      throw AuthException.invalidToken();
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      self.consumeResetToken(token.getId());
      audit.emit(AuditEvents.PASSWORD_RESET_FAILED, token.getUser().getId(), "anonymous",
          "user", token.getUser().getId().toString(), Map.of("reason", "expired"));
      throw AuthException.expiredToken();
    }
    if (!passwordPolicy.accepts(newPassword)) {
      audit.emit(AuditEvents.PASSWORD_RESET_FAILED, token.getUser().getId(), "anonymous",
          "user", token.getUser().getId().toString(), Map.of("reason", "weak-password"));
      throw AuthException.weakPassword();
    }
    User user = token.getUser();
    if (user.getDeletedAt() != null) {
      self.consumeResetToken(token.getId());
      audit.emitAnonymous(AuditEvents.PASSWORD_RESET_FAILED, Map.of("reason", "invalid"));
      throw AuthException.invalidToken();
    }
    token.setUsedAt(Instant.now());
    resetTokens.save(token);
    // Invalidate any sibling active tokens (single-active invariant).
    for (PasswordResetToken sibling
        : resetTokens.findByUser_IdAndUsedAtIsNull(user.getId())) {
      sibling.setUsedAt(Instant.now());
      resetTokens.save(sibling);
    }
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    users.save(user);
    self.revokeAllUserSessions(user.getId());
    audit.emit(AuditEvents.PASSWORD_RESET_SUCCEEDED, user.getId(), "anonymous",
        "user", user.getId().toString(), Map.of("emailVerified", user.isEmailVerified()));
    log.info("Password reset for user: {}", user.getId());
  }

  // -- Login / refresh / logout ----------------------------------------------

  /** Authenticates. Succeeds while unverified (flagged) per the security model. */
  @Transactional
  public LoginResult login(String email, String password, String clientIp) {
    rateLimiter.check("login:ip:" + clientIp,
        properties.getRateLimits().getLoginPerMinutePerIp(), Duration.ofMinutes(1));
    String normalized = normalize(email);
    User user = users.findByEmail(normalized).orElse(null);
    String hash = user != null ? user.getPasswordHash() : dummyHash;
    boolean matches = passwordEncoder.matches(password != null ? password : "", hash);

    if (user == null || !matches) {
      if (user != null) {
        self.recordFailedLogin(user.getId());
        audit.emit(AuditEvents.LOGIN_FAILED, user.getId(), "anonymous",
            "user", user.getId().toString(), Map.of("reason", "bad-credentials"));
      } else {
        audit.emitAnonymous(AuditEvents.LOGIN_FAILED, Map.of("reason", "bad-credentials"));
      }
      throw AuthException.invalidCredentials();
    }
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
      audit.emit(AuditEvents.LOGIN_FAILED, user.getId(), "anonymous",
          "user", user.getId().toString(), Map.of("reason", "locked"));
      throw AuthException.accountLocked();
    }
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(Instant.now());
    users.save(user);

    String accessToken = jwtService.createAccessToken(user.getId(), user.isEmailVerified());
    RefreshSession session = newSession(user);
    audit.emit(AuditEvents.LOGIN_SUCCESS, user.getId(), "user",
        "session", session.getId().toString(), Map.of("emailVerified", user.isEmailVerified()));
    log.info("Login success for user: {}", user.getId());
    return new LoginResult(accessToken,
        properties.getAccessTokenTtl().toSeconds(), user.isEmailVerified(), session);
  }

  /** Rotates a refresh session: one use, then the presented token dies. */
  @Transactional
  public LoginResult refresh(String rawRefreshToken, String clientIp) {
    rateLimiter.check("refresh:ip:" + clientIp,
        properties.getRateLimits().getRefreshPerMinutePerIp(), Duration.ofMinutes(1));
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw AuthException.refreshRequired();
    }
    RefreshSession session =
        sessions.findByTokenHash(sha256Hex(rawRefreshToken)).orElse(null);
    if (session == null) {
      audit.emitAnonymous(AuditEvents.REFRESH_TOKEN_REVOKED, Map.of("reason", "unknown"));
      throw AuthException.refreshRequired();
    }
    User user = session.getUser();
    if (session.isRevoked()) {
      // Reuse of a dead token: assume theft, revoke the whole chain.
      self.revokeAllUserSessions(user.getId());
      audit.emit(AuditEvents.REFRESH_TOKEN_REVOKED, user.getId(), "system",
          "user", user.getId().toString(), Map.of("reason", "reuse-detected"));
      log.warn("Refresh reuse detected; sessions revoked for user: {}", user.getId());
      throw AuthException.refreshRequired();
    }
    if (session.getExpiresAt().isBefore(Instant.now())) {
      session.setRevoked(true);
      sessions.save(session);
      audit.emit(AuditEvents.REFRESH_TOKEN_REVOKED, user.getId(), "system",
          "session", session.getId().toString(), Map.of("reason", "expired"));
      throw AuthException.refreshRequired();
    }
    session.setRevoked(true);
    sessions.save(session);
    RefreshSession next = newSession(user);
    next.setRotatedFrom(session.getId());
    sessions.save(next);
    String accessToken = jwtService.createAccessToken(user.getId(), user.isEmailVerified());
    audit.emit(AuditEvents.REFRESH_TOKEN_ROTATED, user.getId(), "user",
        "session", next.getId().toString(), Map.of("rotated", true));
    return new LoginResult(accessToken,
        properties.getAccessTokenTtl().toSeconds(), user.isEmailVerified(), next);
  }

  /** Revokes the presented refresh session. Idempotent; always clears. */
  @Transactional
  public UUID logout(String rawRefreshToken, AuthPrincipal principal) {
    rls.apply();
    UUID userId = null;
    if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
      RefreshSession session =
          sessions.findByTokenHash(sha256Hex(rawRefreshToken)).orElse(null);
      if (session != null) {
        userId = session.getUser().getId();
        if (!session.isRevoked()) {
          session.setRevoked(true);
          sessions.save(session);
        }
      }
    }
    if (userId == null && principal != null) {
      userId = principal.userId();
    }
    if (userId == null) {
      throw AuthException.refreshRequired();
    }
    audit.emit(AuditEvents.LOGOUT, userId, "user", "user", userId.toString(),
        Map.of("emailVerified", true));
    log.info("Logout for user: {}", userId);
    return userId;
  }

  // -- Cookies -----------------------------------------------------------------

  public ResponseCookie refreshCookie(String rawToken) {
    return ResponseCookie.from(properties.getCookieName(), rawToken)
        .httpOnly(true)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/api/v1/auth")
        .maxAge(properties.getRefreshTokenTtl())
        .build();
  }

  public ResponseCookie clearCookie() {
    return ResponseCookie.from(properties.getCookieName(), "")
        .httpOnly(true)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .path("/api/v1/auth")
        .maxAge(0)
        .build();
  }

  // -- Must-commit boundaries (REQUIRES_NEW; survive the error they precede) -----

  /** Failed-attempt counter + lockout. Commits before the 401 is raised. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailedLogin(UUID userId) {
    User user = users.findById(userId).orElseThrow();
    int fails = user.getFailedLoginAttempts() + 1;
    user.setFailedLoginAttempts(fails);
    if (fails >= properties.getRateLimits().getMaxFailedLogins()) {
      user.setLockedUntil(Instant.now().plus(properties.getRateLimits().getLockout()));
      log.warn("Account locked after repeated failures: {}", user.getId());
    }
    users.save(user);
  }

  /** Consumes a verification token. Commits before the 410 is raised. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void consumeVerificationToken(UUID tokenId) {
    EmailVerificationToken token = verificationTokens.findById(tokenId).orElseThrow();
    token.setUsedAt(Instant.now());
    verificationTokens.save(token);
  }

  /** Consumes a password-reset token. Commits before the error is raised. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void consumeResetToken(UUID tokenId) {
    PasswordResetToken token = resetTokens.findById(tokenId).orElseThrow();
    token.setUsedAt(Instant.now());
    resetTokens.save(token);
  }

  /** Revokes every session of a user. Commits before the 401 is raised. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revokeAllUserSessions(UUID userId) {
    List<RefreshSession> all = sessions.findByUser_Id(userId);
    for (RefreshSession s : all) {
      if (!s.isRevoked()) {
        s.setRevoked(true);
        sessions.save(s);
      }
    }
  }

  // -- Internals (join the caller's transaction) ---------------------------------

  private RefreshSession newSession(User user) {
    String raw = randomToken();
    RefreshSession session = new RefreshSession(
        user, sha256Hex(raw), Instant.now().plus(properties.getRefreshTokenTtl()));
    sessions.save(session);
    session.setRawTokenForResponse(raw);
    return session;
  }

  private void issueVerificationToken(User user) {
    List<EmailVerificationToken> actives =
        verificationTokens.findByUser_IdAndUsedAtIsNull(user.getId());
    Instant now = Instant.now();
    for (EmailVerificationToken old : actives) {
      old.setUsedAt(now);
      verificationTokens.save(old);
    }
    // Flush the expirations first: Hibernate issues inserts before updates,
    // which would otherwise trip the single-active partial unique index.
    verificationTokens.flush();
    String raw = randomToken();
    EmailVerificationToken token = new EmailVerificationToken(
        user, sha256Hex(raw), now.plus(properties.getVerificationTokenTtl()));
    verificationTokens.save(token);
    deliverVerificationEmail(user, verificationLink(raw));
  }

  /**
   * Hands the verification link to the mail seam (ADR-038).
   *
   * <p>A provider outage must neither break the anti-enumeration contract
   * (registration stays 201-shaped, resend stays 202-shaped) nor roll back
   * the persisted token: the user recovers through the resend endpoint.
   * Failures are logged with outcome categories only — never the address,
   * link, or provider credentials.
   */
  private void deliverVerificationEmail(User user, String verificationLink) {
    try {
      log.info("Verification email requested for user {}", user.getId());
      mailer.sendVerificationEmail(user.getEmail(), verificationLink);
      log.info("Verification email handed to delivery for user {}", user.getId());
    } catch (VerificationMailException ex) {
      log.warn("Verification email delivery failed for user {} reason={}",
          user.getId(), ex.getCategory());
    } catch (RuntimeException ex) {
      log.warn("Verification email delivery failed for user {} reason=error", user.getId());
    }
  }

  private String verificationLink(String rawToken) {
    return appBaseUrl + "/api/v1/auth/verify?token=" + rawToken;
  }

  private void issueResetToken(User user) {
    List<PasswordResetToken> actives =
        resetTokens.findByUser_IdAndUsedAtIsNull(user.getId());
    Instant now = Instant.now();
    for (PasswordResetToken old : actives) {
      old.setUsedAt(now);
      resetTokens.save(old);
    }
    // Flush the expirations first: Hibernate issues inserts before updates,
    // which would otherwise trip the single-active partial unique index.
    resetTokens.flush();
    String raw = randomToken();
    PasswordResetToken token = new PasswordResetToken(
        user, sha256Hex(raw), now.plus(properties.getResetTokenTtl()));
    resetTokens.save(token);
    deliverResetEmail(user, resetLink(raw));
  }

  /**
   * Hands the reset link to the mail seam.
   *
   * <p>Same contract as verification delivery: a provider outage must
   * neither reveal account existence nor roll back the persisted token —
   * the caller recovers by requesting again. Failures are logged with
   * outcome categories only — never the address, link, or credentials.
   */
  private void deliverResetEmail(User user, String resetLink) {
    try {
      log.info("Password reset requested for user {}", user.getId());
      mailer.sendPasswordResetEmail(user.getEmail(), resetLink);
      log.info("Password reset handed to delivery for user {}", user.getId());
    } catch (VerificationMailException ex) {
      log.warn("Password reset delivery failed for user {} reason={}",
          user.getId(), ex.getCategory());
    } catch (RuntimeException ex) {
      log.warn("Password reset delivery failed for user {} reason=error", user.getId());
    }
  }

  private String resetLink(String rawToken) {
    return appBaseUrl + "/api/v1/auth/password/reset?token=" + rawToken;
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase();
  }

  /** Service result: fresh access token plus the session holding its refresh. */
  public record LoginResult(String accessToken, long expiresInSeconds,
      boolean emailVerified, RefreshSession session) {
  }
}
