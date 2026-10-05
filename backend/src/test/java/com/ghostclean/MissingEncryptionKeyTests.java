package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ghostclean.common.crypto.EnvAesGcmTokenCrypto;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Fail-fast contract (ADR-016): the crypto bean refuses missing, malformed,
 * or under-strength keys so the app can never boot into encrypting with a
 * weak/absent key.
 */
class MissingEncryptionKeyTests {

  @Test
  void missingKeyFails() {
    assertThrows(IllegalStateException.class, () -> new EnvAesGcmTokenCrypto(null));
    assertThrows(IllegalStateException.class, () -> new EnvAesGcmTokenCrypto(""));
    assertThrows(IllegalStateException.class, () -> new EnvAesGcmTokenCrypto("   "));
  }

  @Test
  void malformedKeyFails() {
    assertThrows(IllegalStateException.class,
        () -> new EnvAesGcmTokenCrypto("not-valid-base64!!!"));
  }

  @Test
  void shortKeyFails() {
    String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
    assertThrows(IllegalStateException.class,
        () -> new EnvAesGcmTokenCrypto(sixteenBytes));
  }

  @Test
  void validKeySucceeds() {
    assertDoesNotThrow(() -> new EnvAesGcmTokenCrypto(TestKeys.syntheticEncryptionKey()));
  }
}
