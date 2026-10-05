package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.ghostclean.auth.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 12 trusted-edge client IP (ADR-034): forwarded headers are honored
 * only from configured edge addresses; everything else — including forged
 * headers from untrusted remotes — resolves to the direct connection.
 */
@ExtendWith(MockitoExtension.class)
class ClientIpResolverTests {

  @Mock
  private HttpServletRequest request;

  @Test
  void emptyTrustListAlwaysUsesDirectAddress() {
    ClientIpResolver resolver = new ClientIpResolver("");
    when(request.getRemoteAddr()).thenReturn("203.0.113.9");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 203.0.113.9");

    assertEquals("203.0.113.9", resolver.clientIp(request));
  }

  @Test
  void trustedExactEdgeUsesFirstForwardedEntry() {
    ClientIpResolver resolver = new ClientIpResolver("10.0.0.7");
    when(request.getRemoteAddr()).thenReturn("10.0.0.7");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.7");

    assertEquals("198.51.100.7", resolver.clientIp(request));
  }

  @Test
  void trustedCidrEdgeIsHonored() {
    ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8, 192.168.0.0/16");
    when(request.getRemoteAddr()).thenReturn("10.4.5.6");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

    assertEquals("198.51.100.7", resolver.clientIp(request));
  }

  @Test
  void untrustedRemoteIgnoresForwardedHeader() {
    ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
    when(request.getRemoteAddr()).thenReturn("203.0.113.9");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

    assertEquals("203.0.113.9", resolver.clientIp(request));
  }

  @Test
  void blankForwardedHeaderFallsBackToDirectAddress() {
    ClientIpResolver resolver = new ClientIpResolver("10.0.0.7");
    when(request.getRemoteAddr()).thenReturn("10.0.0.7");
    when(request.getHeader("X-Forwarded-For")).thenReturn("   ");

    assertEquals("10.0.0.7", resolver.clientIp(request));
  }

  @Test
  void invalidTrustEntriesAreIgnoredSafely() {
    ClientIpResolver resolver = new ClientIpResolver("not-an-address, 10.0.0.0/99");
    when(request.getRemoteAddr()).thenReturn("203.0.113.9");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

    assertEquals("203.0.113.9", resolver.clientIp(request));
  }
}
