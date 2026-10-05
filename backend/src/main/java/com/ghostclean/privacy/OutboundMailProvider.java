package com.ghostclean.privacy;

/**
 * Outbound-mail provider abstraction (Phase 7). The domain layer depends
 * only on this interface — never on a concrete vendor, SMTP client, or
 * Gmail surface (Gmail stays read-only; this path never touches it).
 *
 * <p>To add a real provider later: implement this interface, guard the bean
 * with {@code @ConditionalOnProperty} on {@code outbound.mail.provider},
 * use HTTPS with environment credentials, bounded timeouts/retries, and
 * never log secrets or full message content. Do NOT fabricate delivery:
 * report ACCEPTED only when the provider confirms acceptance.
 */
public interface OutboundMailProvider {

  /** Stable provider identifier (e.g. {@code "sink"}). Safe to log/audit. */
  String providerId();

  /**
   * Sends one message. Implementations must be synchronous and must not
   * retry internally — retry policy lives in the dispatch service so
   * attempts are counted and bounded in one place.
   */
  SendResult send(OutboundMailMessage message);

  /** Text-only message. Deliberately no attachments field: Phase 7 never sends any. */
  record OutboundMailMessage(String from, String to, String subject, String body) {
  }

  /** Provider outcome. Only ACCEPTED means the provider took the message. */
  record SendResult(Outcome outcome, String providerReference, String failureCategory) {

    public enum Outcome {
      ACCEPTED,
      TEMPORARY_FAILURE,
      PERMANENT_FAILURE
    }

    public static SendResult accepted(String providerReference) {
      return new SendResult(Outcome.ACCEPTED, providerReference, null);
    }

    public static SendResult temporaryFailure(String failureCategory) {
      return new SendResult(Outcome.TEMPORARY_FAILURE, null,
          failureCategory == null ? "TEMPORARY_PROVIDER_ERROR" : failureCategory);
    }

    public static SendResult permanentFailure(String failureCategory) {
      return new SendResult(Outcome.PERMANENT_FAILURE, null,
          failureCategory == null ? "UNKNOWN_PROVIDER_ERROR" : failureCategory);
    }
  }
}
