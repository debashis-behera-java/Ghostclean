package com.ghostclean.mailbox;

/**
 * Classified mailbox-provider failure (Phase 4: Gmail; Phase 10: + Graph).
 * Carries only a status, a provider reason string, and an optional retry
 * delay — never bodies, tokens, or message content. The scanner maps these
 * to bounded retries, single refresh, or safe terminal states. Provider
 * subclasses keep their own factories and failure vocabulary.
 */
public class MailboxApiException extends RuntimeException {

  private final int status;
  private final String reason;
  private final long retryAfterSeconds;

  public MailboxApiException(String message, int status, String reason, long retryAfterSeconds) {
    super(message);
    this.status = status;
    this.reason = reason;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int getStatus() {
    return status;
  }

  public String getReason() {
    return reason;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  /** Transient: rate limits, quota, 5xx, timeouts. */
  public boolean isRetryable() {
    return status == 429 || (status >= 500 && status < 600) || status == 0;
  }
}
