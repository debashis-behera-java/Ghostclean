package com.ghostclean.auth;

import com.ghostclean.common.AuthException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rate-limit gate (Phase 2 foundation, Phase 11 backend selection).
 *
 * <p>{@code memory} (default): the original single-instance sliding window —
 * sufficient to stop trivial brute force on this monolith; budgets reset on
 * restart and do not span replicas (documented limitation).
 *
 * <p>{@code redis} ({@code auth.rate-limiter.backend=redis}): delegates to
 * {@link RedisRateLimiter} (distributed sliding window). FAIL-CLOSED per the
 * ADR-019 amendment — a missing bean or any backend failure throws
 * {@code 429 RATE_LIMITED}; there is deliberately no silent fallback that
 * would bypass the intended distributed limiter.
 */
@Component
public class AuthRateLimiter {

  private final ConcurrentHashMap<String, ArrayDeque<Long>> buckets = new ConcurrentHashMap<>();
  private final ObjectProvider<RedisRateLimiter> redis;
  private final String backend;

  /** Local-only gate used by unit tests and single-instance mode. */
  public AuthRateLimiter() {
    this.redis = null;
    this.backend = "memory";
  }

  @Autowired
  public AuthRateLimiter(
      ObjectProvider<RedisRateLimiter> redis,
      @Value("${auth.rate-limiter.backend:memory}") String backend) {
    this.redis = redis;
    this.backend = backend == null ? "memory" : backend.trim();
  }

  /** Records an attempt under {@code key}; throws when over budget. */
  public void check(String key, int limit, Duration window) {
    if ("redis".equalsIgnoreCase(backend)) {
      RedisRateLimiter delegate = redis == null ? null : redis.getIfAvailable();
      if (delegate == null) {
        throw AuthException.rateLimited();
      }
      delegate.check(key, limit, window);
      return;
    }
    checkLocal(key, limit, window);
  }

  private void checkLocal(String key, int limit, Duration window) {
    long now = System.currentTimeMillis();
    long cutoff = now - window.toMillis();
    ArrayDeque<Long> queue = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (queue) {
      while (!queue.isEmpty() && queue.peekFirst() <= cutoff) {
        queue.pollFirst();
      }
      if (queue.size() >= limit) {
        throw AuthException.rateLimited();
      }
      queue.addLast(now);
    }
  }
}
