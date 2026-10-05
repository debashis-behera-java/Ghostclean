package com.ghostclean.auth;

import com.ghostclean.common.AuthException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Phase 11 Redis-backed sliding-window rate limiter (ADR-019 amendment).
 *
 * <p>One atomic Lua round-trip per check (prune + count + add + expire).
 * FAIL-CLOSED: any backend failure — unreachable Redis, missing data,
 * unexpected reply — throws the controlled {@code 429 RATE_LIMITED}
 * envelope. Exactly one attempt is made (no retry loop) and no backend
 * internals ever reach logs beyond a key-agnostic warning or the
 * caller.
 */
@Component
public class RedisRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

  private static final String KEY_PREFIX = "gc:rl:";

  private static final String LUA =
      "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) "
          + "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end "
          + "redis.call('ZADD', KEYS[1], ARGV[5], ARGV[3]) "
          + "redis.call('PEXPIRE', KEYS[1], ARGV[4]) "
          + "return 1";

  private static final RedisScript<Long> SCRIPT = RedisScript.of(LUA, Long.class);

  private final StringRedisTemplate template;

  public RedisRateLimiter(StringRedisTemplate template) {
    this.template = template;
  }

  /** Records an attempt under {@code key}; throws when over budget or unavailable. */
  public void check(String key, int limit, Duration window) {
    long now = System.currentTimeMillis();
    long windowMs = window.toMillis();
    try {
      Long allowed = template.execute(SCRIPT, List.of(KEY_PREFIX + key),
          String.valueOf(now - windowMs),
          String.valueOf(limit),
          UUID.randomUUID().toString(),
          String.valueOf(windowMs),
          String.valueOf(now));
      if (allowed != null && allowed == 1L) {
        return;
      }
      throw AuthException.rateLimited();
    } catch (AuthException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      log.warn("Rate-limit backend unavailable; failing closed");
      throw AuthException.rateLimited();
    }
  }
}
