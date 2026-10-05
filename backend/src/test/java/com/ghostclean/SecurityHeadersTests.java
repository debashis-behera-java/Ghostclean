package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Phase 11 minimal CSP hardening (ADR-027): safe directives are added,
 * legacy headers are retained, OAuth 302/callback semantics are untouched
 * (no redirect or form-post change), and no SPA-specific source lists are
 * invented before the real frontend inventory exists.
 */
class SecurityHeadersTests {

  private static String nginxConf() throws Exception {
    Path conf = Paths.get("..", "infrastructure", "nginx.conf");
    if (!Files.exists(conf)) {
      conf = Paths.get("infrastructure", "nginx.conf");
    }
    assertTrue(Files.exists(conf), "infrastructure/nginx.conf must exist");
    return Files.readString(conf);
  }

  @Test
  void minimalCspDirectivesPresent() throws Exception {
    String conf = nginxConf();
    assertTrue(conf.contains("default-src 'self'"), "default-src 'self' must be present");
    assertTrue(conf.contains("object-src 'none'"), "object-src 'none' must be present");
    assertTrue(conf.contains("frame-ancestors 'none'"), "frame-ancestors 'none' must be present");
    assertTrue(conf.contains("base-uri 'self'"), "base-uri 'self' must be present");
    assertTrue(conf.contains("form-action 'self'"), "form-action 'self' must be present");
  }

  @Test
  void legacyHeadersRetained() throws Exception {
    String conf = nginxConf();
    assertTrue(conf.contains("X-Content-Type-Options"), "nosniff header must be retained");
    assertTrue(conf.contains("X-Frame-Options"), "frame-options header must be retained");
    assertTrue(conf.contains("Referrer-Policy"), "referrer-policy header must be retained");
    assertTrue(conf.contains("server_tokens off"), "server banner suppression must be retained");
    assertTrue(conf.contains("location /actuator/"), "actuator edge block must be retained");
  }

  @Test
  void noInventedSpaSourceLists() throws Exception {
    String conf = nginxConf();
    assertFalse(conf.contains("connect-src"), "no invented connect-src before the real frontend");
    assertFalse(conf.contains("img-src"), "no invented img-src before the real frontend");
    assertFalse(conf.contains("script-src"), "no invented script-src before the real frontend");
  }

}
