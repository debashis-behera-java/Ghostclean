package com.ghostclean.mailbox;

/**
 * Classified Microsoft Graph failure (Phase 10). Same normalized shape as
 * the Gmail failure so the scanner's bounded retry/refresh/terminal-state
 * mapping applies unchanged: 401 refreshes once, non-retryable 403 fails to
 * {@code SCOPE_INSUFFICIENT}, 404 skips the message, retryable failures are
 * bounded, then the scan fails safe. Carries no bodies, tokens, or content.
 */
public class GraphApiException extends MailboxApiException {

  public GraphApiException(int status, String reason, long retryAfterSeconds) {
    super("Microsoft Graph request failed (" + status + "/" + reason + ")",
        status, reason, retryAfterSeconds);
  }

  public static GraphApiException unauthenticated() {
    return new GraphApiException(401, "unauthenticated", 0);
  }

  public static GraphApiException insufficientScope(String reason) {
    return new GraphApiException(403, reason, 0);
  }

  public static GraphApiException rateLimited(long retryAfterSeconds) {
    return new GraphApiException(429, "rateLimited", retryAfterSeconds);
  }

  public static GraphApiException notFound() {
    return new GraphApiException(404, "notFound", 0);
  }

  public static GraphApiException serverError(int status, String reason) {
    return new GraphApiException(status, reason, 0);
  }

  public static GraphApiException timeout() {
    return new GraphApiException(0, "timeout", 0);
  }
}
