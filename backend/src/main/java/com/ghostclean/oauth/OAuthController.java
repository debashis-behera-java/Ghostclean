package com.ghostclean.oauth;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth endpoints (Phase 3: Google connect/disconnect; exact paths per
 * {@code docs/API_SPECIFICATION.md}).
 *
 * <p>Ownership always derives from the security context or the consumed
 * state row — the browser never supplies a GhostClean user id. The callback
 * is intentionally unauthenticated (the browser arrives from Google) and
 * ends in a 302 carrying status codes only, never tokens.
 */
@RestController
@RequestMapping("/api/v1/oauth")
public class OAuthController {

  private final OAuthService service;

  public OAuthController(OAuthService service) {
    this.service = service;
  }

  /** Starts a connection; returns the Google redirect URL (bearer, verified). */
  @GetMapping("/{provider}/authorize")
  public ResponseEntity<AuthorizeResponse> authorize(
      @PathVariable("provider") String provider,
      @RequestParam(value = "returnUrl", required = false) String returnUrl,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    OAuthService.AuthorizeResult result =
        service.authorize(userId, provider, returnUrl, clientIp(request));
    return ResponseEntity.ok(new AuthorizeResponse(
        result.authorizationUrl(), result.state(), result.expiresAt().toString()));
  }

  /** Provider redirect target (no bearer). Success and denial both 302. */
  @GetMapping("/{provider}/callback")
  public ResponseEntity<Void> callback(
      @PathVariable("provider") String provider,
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String error,
      HttpServletRequest request) {
    String target = service.handleCallback(provider, code, state, error, clientIp(request));
    return ResponseEntity.status(HttpStatus.FOUND).header("Location", target).build();
  }

  /** Safe connection list for the owner. No token material, ever. */
  @GetMapping("/connections")
  public ResponseEntity<List<OAuthService.ConnectionDto>> connections(
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(service.connections(userId));
  }

  /** Disconnects an owned connection (404-mask for foreign ids). */
  @DeleteMapping("/connections/{id}")
  public ResponseEntity<Void> disconnect(
      @PathVariable("id") UUID id,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    service.disconnect(userId, id, clientIp(request));
    return ResponseEntity.noContent().build();
  }

  /** Force-refreshes stored credentials (rate-guarded). */
  @PostMapping("/connections/{id}/refresh")
  public ResponseEntity<OAuthService.ConnectionDto> refresh(
      @PathVariable("id") UUID id,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(service.refreshConnection(userId, id, clientIp(request)));
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }

  private static String clientIp(HttpServletRequest request) {
    return request.getRemoteAddr();
  }

  public record AuthorizeResponse(String authorizationUrl, String state, String expiresAt) {
  }
}
