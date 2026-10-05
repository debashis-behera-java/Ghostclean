package com.ghostclean.mailbox;

/**
 * Classified Gmail failure. Carries only a status, a provider reason string,
 * and an optional retry delay — never bodies, tokens, or message content.
 * The scanner maps these to bounded retries, single refresh, or safe
 * terminal states.
 */
public class GmailApiException extends MailboxApiException {

  public GmailApiException(int status, String reason, long retryAfterSeconds) {
    super("Gmail request failed (" + status + "/" + reason + ")",
        status, reason, retryAfterSeconds);
  }

  public static GmailApiException unauthenticated() {
    return new GmailApiException(401, "unauthenticated", 0);
  }

  public static GmailApiException insufficientScope(String reason) {
    return new GmailApiException(403, reason, 0);
  }

  public static GmailApiException rateLimited(long retryAfterSeconds) {
    return new GmailApiException(429, "rateLimited", retryAfterSeconds);
  }

  public static GmailApiException notFound() {
    return new GmailApiException(404, "notFound", 0);
  }

  public static GmailApiException serverError(int status, String reason) {
    return new GmailApiException(status, reason, 0);
  }

  public static GmailApiException timeout() {
    return new GmailApiException(0, "timeout", 0);
  }
}
