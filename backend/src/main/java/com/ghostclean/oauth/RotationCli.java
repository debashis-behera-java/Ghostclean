package com.ghostclean.oauth;

import com.ghostclean.common.crypto.TokenCryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 offline rotation runner (ADR-031).
 *
 * <p>Operator-invoked only, behind the {@code rotation-cli} profile: one
 * bounded pass over rows not yet on the active key version, re-encrypting
 * every readable token column and stamping the active version. Idempotent
 * (reruns converge), resumable (the sweep set is defined by
 * {@code key_version}), bounded (operator row cap), auditable (counts
 * only — never key material or plaintext).
 *
 * <p>This is NOT a background job: no scheduler, no polling, no threads.
 * The application never activates this profile on its own; an operator
 * runs it explicitly after quiescing writes per the KMS rotation
 * runbook. Unreadable rows are counted, never rewritten.
 */
@Component
@Profile("rotation-cli")
public class RotationCli implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RotationCli.class);

  private final OAuthConnectionRepository connections;
  private final TokenCryptoProvider crypto;
  private final int maxRows;

  public RotationCli(
      OAuthConnectionRepository connections,
      TokenCryptoProvider crypto,
      @Value("${crypto.rotation-cli.max-rows:1000}") int maxRows) {
    this.connections = connections;
    this.crypto = crypto;
    this.maxRows = Math.max(1, maxRows);
  }

  @Override
  public void run(ApplicationArguments args) {
    Summary summary = rotateOnce();
    log.info("Offline rotation pass complete: rotated={} skipped={} failed={} (cap={})",
        summary.rotated(), summary.skipped(), summary.failed(), maxRows);
  }

  /** One bounded convergence pass; safe to rerun. */
  @Transactional
  public Summary rotateOnce() {
    int active = crypto.keyVersion();
    var page = connections.findByKeyVersionNot(active, PageRequest.of(0, maxRows));
    int rotated = 0;
    int skipped = 0;
    int failed = 0;
    for (OAuthConnection connection : page.getContent()) {
      if (connection.getKeyVersion() == active) {
        skipped++;
        continue;
      }
      if (rotateRow(connection, active)) {
        rotated++;
      } else {
        failed++;
      }
    }
    return new Summary(rotated, skipped, failed);
  }

  private boolean rotateRow(OAuthConnection connection, int active) {
    String access = decryptLenient(connection.getAccessTokenEnc());
    String refresh = decryptLenient(connection.getRefreshTokenEnc());
    if (access == null && refresh == null) {
      return false;
    }
    try {
      if (access != null) {
        connection.setAccessTokenEnc(crypto.encrypt(access));
      }
      if (refresh != null) {
        connection.setRefreshTokenEnc(crypto.encrypt(refresh));
      }
      connection.setKeyVersion(active);
      connections.save(connection);
      return true;
    } catch (RuntimeException ex) {
      log.warn("Rotation skipped an unreadable credential row");
      return false;
    }
  }

  private String decryptLenient(String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      return null;
    }
    try {
      return crypto.decrypt(ciphertext);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** Counts only — safe to log and audit. */
  public record Summary(int rotated, int skipped, int failed) {
  }
}
