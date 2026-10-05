package com.ghostclean.user;

import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.common.RlsContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Own-profile endpoint (Phase 2). Identity comes solely from the
 * authentication context — there is no {@code /users/{id}} route, so
 * cross-user access is impossible by construction. Returns profile facts
 * only: never password hashes or token material.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserProfileController {

  private final UserRepository users;
  private final RlsContext rls;
  private final AdminAccess admins;

  public UserProfileController(UserRepository users, RlsContext rls, AdminAccess admins) {
    this.users = users;
    this.rls = rls;
    this.admins = admins;
  }

  @GetMapping("/me")
  @Transactional(readOnly = true)
  public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
    rls.apply();
    AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
    UUID userId = principal.userId();
    User user = users.findById(userId).orElseThrow(() ->
        new IllegalStateException("Authenticated user no longer exists"));
    return ResponseEntity.ok(Map.of(
        "userId", user.getId().toString(),
        "email", user.getEmail(),
        "emailVerified", user.isEmailVerified(),
        "admin", admins.isAdmin(user),
        "status", user.getStatus(),
        "createdAt", user.getCreatedAt().toString(),
        "lastLoginAt", user.getLastLoginAt() != null
            ? user.getLastLoginAt().toString() : ""));
  }
}
