package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ghostclean.auth.RedisRateLimiter;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

/**
 * Phase 11 Redis-backed rate limiting (ADR-019 amendment): allow/deny
 * parity plus FAIL-CLOSED on backend failure — controlled 429, no
 * internals leaked, exactly one backend attempt (no retry loop).
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTests {

  @Mock
  private StringRedisTemplate template;

  private RedisRateLimiter limiter;

  @BeforeEach
  void setUp() {
    limiter = new RedisRateLimiter(template);
  }

  @Test
  @SuppressWarnings("unchecked")
  void allowsUnderLimit() {
    when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

    assertDoesNotThrow(() -> limiter.check("login:1.2.3.4", 5, Duration.ofMinutes(1)));
  }

  @Test
  @SuppressWarnings("unchecked")
  void deniesAtLimitWithControlledEnvelope() {
    when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

    AuthException ex = assertThrows(AuthException.class,
        () -> limiter.check("login:1.2.3.4", 5, Duration.ofMinutes(1)));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
    assertEquals(ApiError.RATE_LIMITED, ex.getCode());
  }

  @Test
  @SuppressWarnings("unchecked")
  void backendFailureFailsClosedWithoutLeakingInternals() {
    when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(new RedisConnectionFailureException(
            "simulated outage REFUSED-xyz", new ConnectException("refused")));

    AuthException ex = assertThrows(AuthException.class,
        () -> limiter.check("login:1.2.3.4", 5, Duration.ofMinutes(1)));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
    assertEquals(ApiError.RATE_LIMITED, ex.getCode());
    assertTrue(!ex.getMessage().contains("REFUSED-xyz")
        && !ex.getMessage().toLowerCase().contains("redis"),
        "failure envelope must not leak backend internals");
    verify(template, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void keysAreIsolatedPerBackendKey() {
    when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

    limiter.check("user:A", 5, Duration.ofMinutes(1));
    limiter.check("user:B", 5, Duration.ofMinutes(1));

    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(template, times(2)).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
    assertTrue(!keys.getAllValues().get(0).get(0).equals(keys.getAllValues().get(1).get(0)),
        "distinct limiter keys must map to distinct backend keys");
  }
}
