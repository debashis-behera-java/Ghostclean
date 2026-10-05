package com.ghostclean.auth;

import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.common.AuthException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (Phase 2). Exact paths follow
 * {@code docs/API_SPECIFICATION.md}. All responses are credential-free:
 * no passwords, hashes, or token material beyond the tokens the caller
 * just earned (access token in body, refresh in httpOnly cookie).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;
  private final ClientIpResolver clientIps;

  public AuthController(AuthService authService, ClientIpResolver clientIps) {
    this.authService = authService;
    this.clientIps = clientIps;
  }

  /** Registers; identical 201 whether the address is new or taken (no oracle). */
  @PostMapping("/register")
  public ResponseEntity<Map<String, Object>> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    authService.register(request.email(), request.password(), clientIp(http));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "message", "Registration successful. Please verify your email.",
        "verificationRequired", true));
  }

  /** Consumes a verification token (single-use, expiring). */
  @PostMapping("/verify")
  public ResponseEntity<Void> verify(
      @Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
    authService.verify(request.token(), clientIp(http));
    return ResponseEntity.noContent().build();
  }

  /**
   * Browser verification link (email clients only follow GET).
   *
   * <p>Consumes the token through the same single-use, expiring pipeline
   * as {@code POST /verify}, then 302-redirects to a token-free result
   * page. The redirect target is an absolute path (leading slash, no
   * host), so no origin is hard-coded, every HTTP client resolves it
   * identically regardless of the request path, and the flow works on
   * any host, port, or domain. Success and every failure mode (invalid,
   * expired, replayed, missing) land on generic pages that never echo
   * the token.
   */
  @GetMapping("/verify")
  public ResponseEntity<Void> verifyByLink(
      @RequestParam(value = "token", required = false) String token,
      HttpServletRequest http) {
    if (token == null || token.isBlank()) {
      return redirectToResult(false);
    }
    try {
      authService.verify(token, clientIp(http));
    } catch (AuthException ex) {
      return redirectToResult(false);
    }
    return redirectToResult(true);
  }

  /** Token-free verification success page (no token in URL, body, or logs). */
  @GetMapping("/verify/success")
  public ResponseEntity<String> verifySuccess() {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body("<!doctype html><html><body style=\"font-family:sans-serif\">"
            + "<h2>GhostClean email verified</h2>"
            + "<p>Your email address is verified. You can now log in and connect your accounts.</p>"
            + "</body></html>");
  }

  /** Generic token-free verification failure page (no validity oracle). */
  @GetMapping("/verify/failed")
  public ResponseEntity<String> verifyFailed() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_HTML)
        .body("<!doctype html><html><body style=\"font-family:sans-serif\">"
            + "<h2>GhostClean verification link invalid</h2>"
            + "<p>This link is invalid, expired, or already used. "
            + "Please request a new verification email and try the newest link.</p>"
            + "</body></html>");
  }

  private static ResponseEntity<Void> redirectToResult(boolean verified) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, verified
            ? "/api/v1/auth/verify/success"
            : "/api/v1/auth/verify/failed")
        .build();
  }

  /** Re-issues a verification token. Always 202 (no oracle, anti-spam caps). */
  @PostMapping("/verification/resend")
  public ResponseEntity<Void> resend(
      @Valid @RequestBody ResendRequest request, HttpServletRequest http) {
    authService.resendVerification(request.email(), clientIp(http));
    return ResponseEntity.accepted().build();
  }

  /**
   * Requests a password-reset email. Always 202 with the identical generic
   * body whether the address exists or not (no account oracle). For a known,
   * non-deleted account a short-lived single-use token is issued and mailed;
   * per-address daily caps bound Resend usage.
   */
  @PostMapping("/password/forgot")
  public ResponseEntity<Map<String, Object>> forgotPassword(
      @Valid @RequestBody ForgotRequest request, HttpServletRequest http) {
    authService.requestPasswordReset(request.email(), clientIp(http));
    return ResponseEntity.accepted().body(Map.of(
        "message", "If an account exists for this email, a password reset link has been sent."));
  }

  /**
   * Consumes a reset token and sets a new password (single-use, expiring).
   * Invalid, expired, replayed, and weak-password attempts are rejected with
   * generic error codes that never reveal account existence.
   */
  @PostMapping("/password/reset")
  public ResponseEntity<Map<String, Object>> resetPassword(
      @Valid @RequestBody ResetRequest request, HttpServletRequest http) {
    authService.resetPassword(request.token(), request.newPassword(), clientIp(http));
    return ResponseEntity.ok(Map.of(
        "message", "Password reset successful."));
  }

  /**
   * Browser reset link (email clients only follow GET).
   *
   * <p>Validates the token <em>without consuming it</em> and renders the
   * password-entry form; the actual update happens through
   * {@code POST /password/reset} via the page's fetch call. Missing,
   * malformed, invalid, or expired tokens 302-redirect to a token-free
   * failure page. Redirect targets are absolute paths (leading slash, no
   * host), so no origin is hard-coded. The token never appears in the
   * redirect, in logs, or on the result pages.
   */
  @GetMapping("/password/reset")
  public ResponseEntity<String> resetPasswordPage(
      @RequestParam(value = "token", required = false) String token,
      HttpServletRequest http) {
    if (!isTokenShaped(token) || !authService.resetTokenUsable(token, clientIp(http))) {
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, "/api/v1/auth/password/reset/failed")
          .build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body(resetForm(token.trim()));
  }

  /** Token-free reset success page (no token in URL, body, or logs). */
  @GetMapping("/password/reset/success")
  public ResponseEntity<String> resetPasswordSuccess() {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body("<!doctype html><html><body style=\"font-family:sans-serif\">"
            + "<h2>GhostClean password reset</h2>"
            + "<p>Your password has been reset. You can now log in with your new password.</p>"
            + "</body></html>");
  }

  /** Generic token-free reset failure page (no validity oracle). */
  @GetMapping("/password/reset/failed")
  public ResponseEntity<String> resetPasswordFailed() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_HTML)
        .body("<!doctype html><html><body style=\"font-family:sans-serif\">"
            + "<h2>GhostClean reset link invalid</h2>"
            + "<p>This link is invalid, expired, or already used. "
            + "Please request a new password reset email and try the newest link.</p>"
            + "</body></html>");
  }

  /**
   * Reset tokens are CSPRNG Base64url without padding; anything else is
   * rejected before any lookup so malformed input never reaches the query.
   */
  private static boolean isTokenShaped(String token) {
    if (token == null) {
      return false;
    }
    String raw = token.trim();
    return !raw.isEmpty() && raw.length() <= 255 && raw.matches("[A-Za-z0-9_-]+");
  }

  private static String resetForm(String token) {
    return "<!doctype html><html><body style=\"font-family:sans-serif\">"
        + "<h2>GhostClean password reset</h2>"
        + "<p>Choose a new password (minimum 12 characters).</p>"
        + "<form id=\"reset-form\">"
        + "<p><label>New password<br><input type=\"password\" id=\"pw\" "
        + "autocomplete=\"new-password\" minlength=\"12\" maxlength=\"128\" required></label></p>"
        + "<p><label>Confirm password<br><input type=\"password\" id=\"pw2\" "
        + "autocomplete=\"new-password\" minlength=\"12\" maxlength=\"128\" required></label></p>"
        + "<p><button type=\"submit\">Reset password</button></p>"
        + "<p id=\"reset-error\" style=\"color:#a00\"></p>"
        + "</form>"
        + "<script>"
        + "const RESET_TOKEN = '" + token + "';"
        + "document.getElementById('reset-form').addEventListener('submit', async (e) => {"
        + "e.preventDefault();"
        + "const pw = document.getElementById('pw').value;"
        + "const pw2 = document.getElementById('pw2').value;"
        + "const err = document.getElementById('reset-error');"
        + "if (pw !== pw2) { err.textContent = 'Passwords do not match.'; return; }"
        + "const res = await fetch('/api/v1/auth/password/reset', {method: 'POST',"
        + "headers: {'Content-Type': 'application/json'},"
        + "body: JSON.stringify({token: RESET_TOKEN, newPassword: pw})});"
        + "if (res.ok) { window.location = '/api/v1/auth/password/reset/success'; }"
        + "else { err.textContent = 'Reset failed. The link may be invalid, expired, "
        + "or already used, or the password too weak.'; }"
        + "});"
        + "</script>"
        + "</body></html>";
  }

  /** Authenticates; works while unverified (flagged) per the security model. */
  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    AuthService.LoginResult result =
        authService.login(request.email(), request.password(), clientIp(http));
    ResponseCookie cookie = authService.refreshCookie(
        result.session().getRawTokenForResponse());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(Map.of(
            "accessToken", result.accessToken(),
            "tokenType", "Bearer",
            "expiresIn", result.expiresInSeconds(),
            "emailVerified", result.emailVerified()));
  }

  /** Rotates the refresh session from the httpOnly cookie. */
  @PostMapping("/refresh")
  public ResponseEntity<Map<String, Object>> refresh(
      @CookieValue(name = "${auth.cookie-name:gc_refresh}", required = false) String refreshToken,
      HttpServletRequest http) {
    AuthService.LoginResult result = authService.refresh(refreshToken, clientIp(http));
    ResponseCookie cookie = authService.refreshCookie(
        result.session().getRawTokenForResponse());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(Map.of(
            "accessToken", result.accessToken(),
            "tokenType", "Bearer",
            "expiresIn", result.expiresInSeconds(),
            "emailVerified", result.emailVerified()));
  }

  /** Revokes the refresh session and clears the cookie. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "${auth.cookie-name:gc_refresh}", required = false) String refreshToken,
      Authentication authentication) {
    AuthPrincipal principal = null;
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal p) {
      principal = p;
    }
    authService.logout(refreshToken, principal);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, authService.clearCookie().toString())
        .build();
  }

  private String clientIp(HttpServletRequest request) {
    // Trusted-edge resolution (ADR-034): forwarded headers count only from
    // configured edge addresses; otherwise the direct remote address.
    return clientIps.clientIp(request);
  }

  public record RegisterRequest(
      @NotBlank @Email @jakarta.validation.constraints.Size(max = 254) String email,
      @NotBlank @jakarta.validation.constraints.Size(max = 256) String password) {
  }

  public record LoginRequest(
      @NotBlank @Email @jakarta.validation.constraints.Size(max = 254) String email,
      @NotBlank @jakarta.validation.constraints.Size(max = 256) String password) {
  }

  public record VerifyRequest(@NotBlank String token) {
  }

  public record ResendRequest(
      @NotBlank @Email @jakarta.validation.constraints.Size(max = 254) String email) {
  }

  public record ForgotRequest(
      @NotBlank @Email @jakarta.validation.constraints.Size(max = 254) String email) {
  }

  public record ResetRequest(
      @NotBlank @jakarta.validation.constraints.Size(max = 512) String token,
      @NotBlank @jakarta.validation.constraints.Size(max = 256) String newPassword) {
  }
}
