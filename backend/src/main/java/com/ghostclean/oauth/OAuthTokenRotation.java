package com.ghostclean.oauth;

import com.ghostclean.common.crypto.TokenCryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 11 lazy re-encrypt-on-read (ADR-024).
 *
 * <p>Reads decrypt with the row's stored {@code key_version}; when the row
 * is stale relative to {@link TokenCryptoProvider#keyVersion()}, both
 * stored token columns are re-encrypted with the current key and the row
 * is persisted with the current version — atomically and idempotently.
 * Absent or unreadable ciphertext yields {@code null} and is never
 * rewritten, so callers fail safe to their existing reconnect flows.
 *
 * <p>No scheduler, thread, polling, or background job is involved:
 * convergence happens only on explicit reads. Plaintext and key material
 * are never logged, audited, or returned beyond the caller.
 */
@Service
public class OAuthTokenRotation {

  private static final Logger log = LoggerFactory.getLogger(OAuthTokenRotation.class);

  private final TokenCryptoProvider crypto;
  private final OAuthConnectionRepository connections;

  public OAuthTokenRotation(TokenCryptoProvider crypto, OAuthConnectionRepository connections) {
    this.crypto = crypto;
    this.connections = connections;
  }

  /** Decrypts the access-token column, rotating the row when stale. */
  @Transactional
  public String decryptAccess(OAuthConnection connection) {
    return decryptColumn(connection, true);
  }

  /** Decrypts the refresh-token column, rotating the row when stale. */
  @Transactional
  public String decryptRefresh(OAuthConnection connection) {
    return decryptColumn(connection, false);
  }

  private String decryptColumn(OAuthConnection connection, boolean access) {
    String ciphertext = access ? connection.getAccessTokenEnc() : connection.getRefreshTokenEnc();
    String plaintext = decryptLenient(ciphertext);
    if (plaintext == null) {
      return null;
    }
    if (connection.getKeyVersion() != crypto.keyVersion()) {
      rotateIfReadable(connection);
    }
    return plaintext;
  }

  /**
   * Re-encrypts every readable token column with the current key and stamps
   * the current version. Rows with nothing readable are left untouched.
   */
  private void rotateIfReadable(OAuthConnection connection) {
    String access = decryptLenient(connection.getAccessTokenEnc());
    String refresh = decryptLenient(connection.getRefreshTokenEnc());
    if (access == null && refresh == null) {
      log.warn("Stored OAuth credential unreadable; leaving version unchanged");
      return;
    }
    if (access != null) {
      connection.setAccessTokenEnc(crypto.encrypt(access));
    }
    if (refresh != null) {
      connection.setRefreshTokenEnc(crypto.encrypt(refresh));
    }
    connection.setKeyVersion(crypto.keyVersion());
    connections.save(connection);
  }

  private String decryptLenient(String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      return null;
    }
    try {
      return crypto.decrypt(ciphertext);
    } catch (RuntimeException ex) {
      log.warn("Stored OAuth credential unreadable; treating as unusable");
      return null;
    }
  }
}
