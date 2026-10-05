package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.oauth.OAuthConnection;
import com.ghostclean.oauth.OAuthConnectionRepository;
import com.ghostclean.oauth.OAuthTokenRotation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 11 lazy re-encrypt-on-read (ADR-024): stale rows converge on
 * access, current rows are untouched, failures fail safe, and the
 * operation is idempotent. No scheduler, thread, or job is involved.
 */
@ExtendWith(MockitoExtension.class)
class OAuthTokenRotationTests {

  @Mock
  private TokenCryptoProvider crypto;

  @Mock
  private OAuthConnectionRepository connections;

  @Mock
  private OAuthConnection connection;

  private OAuthTokenRotation rotation;

  @BeforeEach
  void setUp() {
    rotation = new OAuthTokenRotation(crypto, connections);
  }

  private void currentKeyIsVersion2() {
    when(crypto.keyVersion()).thenReturn(2);
  }

  @Test
  void currentVersionIsNoOp() {
    currentKeyIsVersion2();
    when(connection.getKeyVersion()).thenReturn(2);
    when(connection.getAccessTokenEnc()).thenReturn("current-enc");
    when(crypto.decrypt("current-enc")).thenReturn("access-plaintext");

    assertEquals("access-plaintext", rotation.decryptAccess(connection));

    verify(connections, never()).save(connection);
  }

  @Test
  void staleVersionRotatesBothColumnsAtomically() {
    currentKeyIsVersion2();
    when(connection.getKeyVersion()).thenReturn(1);
    when(connection.getAccessTokenEnc()).thenReturn("old-access-enc");
    when(connection.getRefreshTokenEnc()).thenReturn("old-refresh-enc");
    when(crypto.decrypt("old-access-enc")).thenReturn("access-plaintext");
    when(crypto.decrypt("old-refresh-enc")).thenReturn("refresh-plaintext");
    when(crypto.encrypt("access-plaintext")).thenReturn("new-access-enc");
    when(crypto.encrypt("refresh-plaintext")).thenReturn("new-refresh-enc");

    assertEquals("access-plaintext", rotation.decryptAccess(connection));

    verify(connection).setAccessTokenEnc("new-access-enc");
    verify(connection).setRefreshTokenEnc("new-refresh-enc");
    verify(connection).setKeyVersion(2);
    verify(connections, times(1)).save(connection);
  }

  @Test
  void rotationIsIdempotent() {
    currentKeyIsVersion2();
    when(connection.getKeyVersion()).thenReturn(1, 2);
    when(connection.getAccessTokenEnc()).thenReturn("old-access-enc");
    when(connection.getRefreshTokenEnc()).thenReturn("old-refresh-enc");
    when(crypto.decrypt("old-access-enc")).thenReturn("access-plaintext");
    when(crypto.decrypt("old-refresh-enc")).thenReturn("refresh-plaintext");
    when(crypto.encrypt(anyString())).thenAnswer(inv -> "re-" + inv.getArgument(0));

    rotation.decryptAccess(connection);
    rotation.decryptAccess(connection);

    verify(connections, times(1)).save(connection);
  }

  @Test
  void undecryptableStaleRowIsLeftUntouched() {
    when(connection.getAccessTokenEnc()).thenReturn("corrupt-enc");
    when(crypto.decrypt("corrupt-enc")).thenThrow(new IllegalStateException("Decryption failed"));

    assertNull(rotation.decryptAccess(connection));

    verify(connection, never()).setAccessTokenEnc(anyString());
    verify(connection, never()).setKeyVersion(2);
    verify(connections, never()).save(connection);
  }

  @Test
  void blankCiphertextIsSafe() {
    when(connection.getAccessTokenEnc()).thenReturn(null);

    assertNull(rotation.decryptAccess(connection));

    verify(connections, never()).save(connection);
  }

  @Test
  void staleRefreshRotatesViaRefreshPath() {
    currentKeyIsVersion2();
    when(connection.getKeyVersion()).thenReturn(1);
    when(connection.getAccessTokenEnc()).thenReturn("old-access-enc");
    when(connection.getRefreshTokenEnc()).thenReturn("old-refresh-enc");
    when(crypto.decrypt("old-access-enc")).thenReturn("access-plaintext");
    when(crypto.decrypt("old-refresh-enc")).thenReturn("refresh-plaintext");
    when(crypto.encrypt(anyString())).thenAnswer(inv -> "re-" + inv.getArgument(0));

    assertEquals("refresh-plaintext", rotation.decryptRefresh(connection));

    verify(connection).setKeyVersion(2);
    verify(connections, times(1)).save(connection);
  }
}
