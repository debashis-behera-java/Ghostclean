package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.oauth.OAuthConnection;
import com.ghostclean.oauth.OAuthConnectionRepository;
import com.ghostclean.oauth.RotationCli;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Phase 12 offline rotation CLI (ADR-031): bounded, idempotent, resumable
 * by key_version, safe on partial failure — and never a background job
 * (plain runner behind the {@code rotation-cli} profile, invoked once by
 * an operator).
 */
@ExtendWith(MockitoExtension.class)
class RotationCliTests {

  @Mock
  private OAuthConnectionRepository connections;

  @Mock
  private TokenCryptoProvider crypto;

  @Mock
  private ApplicationArguments args;

  @Mock
  private OAuthConnection stale;

  @Mock
  private OAuthConnection current;

  @Mock
  private OAuthConnection corrupt;

  private RotationCli cli;

  @BeforeEach
  void setUp() {
    cli = new RotationCli(connections, crypto, 500);
    when(crypto.keyVersion()).thenReturn(2);
  }

  private void givenStale() {
    when(stale.getKeyVersion()).thenReturn(1);
    when(stale.getAccessTokenEnc()).thenReturn("old-access");
    when(stale.getRefreshTokenEnc()).thenReturn("old-refresh");
    when(crypto.decrypt("old-access")).thenReturn("access-plain");
    when(crypto.decrypt("old-refresh")).thenReturn("refresh-plain");
    when(crypto.encrypt("access-plain")).thenReturn("new-access");
    when(crypto.encrypt("refresh-plain")).thenReturn("new-refresh");
  }

  private void givenCurrent() {
    when(current.getKeyVersion()).thenReturn(2);
  }

  private void givenCorrupt() {
    when(corrupt.getKeyVersion()).thenReturn(1);
    when(corrupt.getAccessTokenEnc()).thenReturn("corrupt");
    when(corrupt.getRefreshTokenEnc()).thenReturn(null);
    when(crypto.decrypt("corrupt")).thenThrow(new IllegalStateException("Decryption failed"));
  }

  @Test
  void convergesStaleRowsAndSkipsCurrent() {
    givenStale();
    givenCurrent();
    when(connections.findByKeyVersionNot(eq(2), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(stale, current)));

    RotationCli.Summary summary = cli.rotateOnce();

    assertEquals(1, summary.rotated());
    assertEquals(1, summary.skipped());
    assertEquals(0, summary.failed());
    verify(stale).setKeyVersion(2);
    verify(connections, times(1)).save(stale);
    verify(connections, never()).save(current);
  }

  @Test
  void corruptRowsAreCountedNotRewritten() {
    givenCorrupt();
    when(connections.findByKeyVersionNot(eq(2), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(corrupt)));

    RotationCli.Summary summary = cli.rotateOnce();

    assertEquals(0, summary.rotated());
    assertEquals(1, summary.failed());
    verify(connections, never()).save(any());
  }

  @Test
  void rerunIsIdempotent() {
    givenStale();
    givenCurrent();
    when(connections.findByKeyVersionNot(eq(2), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(stale, current)));
    cli.rotateOnce();
    when(connections.findByKeyVersionNot(eq(2), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(current)));

    RotationCli.Summary second = cli.rotateOnce();

    assertEquals(0, second.rotated());
    assertEquals(1, second.skipped());
  }

  @Test
  void maxRowsBoundIsRespected() {
    givenStale();
    RotationCli bounded = new RotationCli(connections, crypto, 1);
    when(connections.findByKeyVersionNot(eq(2), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(stale)));

    RotationCli.Summary summary = bounded.rotateOnce();

    assertEquals(1, summary.rotated());
    ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
    verify(connections).findByKeyVersionNot(eq(2), pages.capture());
    assertEquals(1, pages.getValue().getPageSize());
  }
}
