package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.auth.RedisRateLimiter;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

/**
 * Phase 11 backend selection (ADR-019 amendment): the default stays the
 * local sliding window, while a configured-but-absent Redis backend fails
 * closed instead of silently bypassing the distributed limiter.
 */
@ExtendWith(MockitoExtension.class)
class AuthRateLimiterBackendTests {

  @Mock
  private ObjectProvider<RedisRateLimiter> provider;

  @Mock
  private RedisRateLimiter delegate;

  @Test
  void defaultConstructorKeepsLocalSemantics() {
    AuthRateLimiter limiter = new AuthRateLimiter();
    assertDoesNotThrow(() -> limiter.check("sel", 1, Duration.ofMinutes(1)));
    assertThrows(AuthException.class, () -> limiter.check("sel", 1, Duration.ofMinutes(1)));
  }

  @Test
  void configuredRedisDelegates() {
    when(provider.getIfAvailable()).thenReturn(delegate);
    AuthRateLimiter limiter = new AuthRateLimiter(provider, "redis");

    limiter.check("sel", 5, Duration.ofMinutes(1));

    verify(delegate).check("sel", 5, Duration.ofMinutes(1));
  }

  @Test
  void configuredRedisWithoutBeanFailsClosed() {
    when(provider.getIfAvailable()).thenReturn(null);
    AuthRateLimiter limiter = new AuthRateLimiter(provider, "redis");

    AuthException ex = assertThrows(AuthException.class,
        () -> limiter.check("sel", 5, Duration.ofMinutes(1)));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
    assertEquals(ApiError.RATE_LIMITED, ex.getCode());
  }
}
