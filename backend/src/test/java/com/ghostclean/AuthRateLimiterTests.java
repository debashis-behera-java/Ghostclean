package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.AuthException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Sliding-window budgets: allow-then-block, key independence, expiry. */
class AuthRateLimiterTests {

  @Test
  void allowsUpToLimitThenBlocks() {
    AuthRateLimiter limiter = new AuthRateLimiter();
    for (int i = 0; i < 5; i++) {
      assertDoesNotThrow(() -> limiter.check("k1", 5, Duration.ofMinutes(1)));
    }
    assertThrows(AuthException.class, () -> limiter.check("k1", 5, Duration.ofMinutes(1)));
  }

  @Test
  void keysAreIndependent() {
    AuthRateLimiter limiter = new AuthRateLimiter();
    for (int i = 0; i < 5; i++) {
      assertDoesNotThrow(() -> limiter.check("a", 5, Duration.ofMinutes(1)));
    }
    assertDoesNotThrow(() -> limiter.check("b", 5, Duration.ofMinutes(1)));
  }

  @Test
  void windowExpiryResetsBudget() throws Exception {
    AuthRateLimiter limiter = new AuthRateLimiter();
    limiter.check("w", 1, Duration.ofMillis(100));
    assertThrows(AuthException.class, () -> limiter.check("w", 1, Duration.ofMillis(100)));
    Thread.sleep(200);
    assertDoesNotThrow(() -> limiter.check("w", 1, Duration.ofMillis(100)));
  }
}
