package com.ghostclean.privacy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development/test sink provider (Phase 7). Records outbound messages in
 * memory instead of sending anything: no network, no SMTP, no Gmail, no
 * credentials. Sink acceptance ({@code sink-<uuid>} references) means
 * "recorded for tests" — it must never be presented as real delivery.
 *
 * <p>The scripted-failure hooks below exist so tests can prove bounded
 * retry and failure mapping without external services. They are plain
 * in-memory script state on a dev-only bean, not production behavior.
 */
@Component
@ConditionalOnProperty(name = "outbound.mail.provider", havingValue = "sink",
    matchIfMissing = true)
public class SinkOutboundMailProvider implements OutboundMailProvider {

  private static final Logger log = LoggerFactory.getLogger(SinkOutboundMailProvider.class);

  private final List<OutboundMailMessage> sent = new CopyOnWriteArrayList<>();
  private final AtomicInteger temporaryFailuresRemaining = new AtomicInteger(0);
  private volatile String permanentFailureCategory;

  @Override
  public String providerId() {
    return "sink";
  }

  @Override
  public SendResult send(OutboundMailMessage message) {
    if (permanentFailureCategory != null) {
      // Safe diagnostics only: outcome + category, never content or secrets.
      log.info("Sink rejecting message: category={}", permanentFailureCategory);
      return SendResult.permanentFailure(permanentFailureCategory);
    }
    if (temporaryFailuresRemaining.getAndDecrement() > 0) {
      log.info("Sink simulating transient failure");
      return SendResult.temporaryFailure("TEMPORARY_PROVIDER_ERROR");
    }
    sent.add(message);
    String reference = "sink-" + UUID.randomUUID();
    log.info("Sink recorded message: reference={}", reference);
    return SendResult.accepted(reference);
  }

  /** Messages recorded so far (test assertions use deltas or reset first). */
  public List<OutboundMailMessage> sent() {
    return List.copyOf(sent);
  }

  /** Test hook: the next {@code n} sends fail transiently, then succeed. */
  public void failNextTemporary(int n) {
    temporaryFailuresRemaining.set(Math.max(0, n));
  }

  /** Test hook: every send fails permanently with the given category. */
  public void failNextPermanent(String category) {
    permanentFailureCategory = category;
  }

  /** Test hook: clears recorded messages and scripted failures. */
  public void reset() {
    sent.clear();
    temporaryFailuresRemaining.set(0);
    permanentFailureCategory = null;
  }
}
