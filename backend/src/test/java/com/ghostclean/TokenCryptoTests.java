package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.common.crypto.EnvAesGcmTokenCrypto;
import org.junit.jupiter.api.Test;

/** AES-256-GCM round-trips, IV freshness, and tamper resistance (no Spring). */
class TokenCryptoTests {

  private EnvAesGcmTokenCrypto crypto() {
    return new EnvAesGcmTokenCrypto(TestKeys.syntheticEncryptionKey());
  }

  @Test
  void roundTrip() {
    EnvAesGcmTokenCrypto crypto = crypto();
    String ciphertext = crypto.encrypt("future-oauth-refresh-value");
    assertNotEquals("future-oauth-refresh-value", ciphertext);
    assertEquals("future-oauth-refresh-value", crypto.decrypt(ciphertext));
  }

  @Test
  void roundTripUnicodeAndEmpty() {
    EnvAesGcmTokenCrypto crypto = crypto();
    assertEquals("", crypto.decrypt(crypto.encrypt("")));
    assertEquals("späcïal ünïcode ✓", crypto.decrypt(crypto.encrypt("späcïal ünïcode ✓")));
  }

  @Test
  void freshIvPerEncryption() {
    EnvAesGcmTokenCrypto crypto = crypto();
    assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"));
  }

  @Test
  void tamperedCiphertextFails() {
    EnvAesGcmTokenCrypto crypto = crypto();
    String ciphertext = crypto.encrypt("sensitive");
    String tampered = ciphertext.substring(0, ciphertext.length() - 2) + "AA";
    assertThrows(RuntimeException.class, () -> crypto.decrypt(tampered));
  }

  @Test
  void nullInputsRejected() {
    EnvAesGcmTokenCrypto crypto = crypto();
    assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(null));
    assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(null));
  }

  @Test
  void keyVersionIsOne() {
    assertEquals(1, crypto().keyVersion());
  }

  @Test
  void ciphertextNeverContainsPlaintext() {
    EnvAesGcmTokenCrypto crypto = crypto();
    String plaintext = "oauth-plaintext-marker-xyz";
    assertTrue(!crypto.encrypt(plaintext).contains(plaintext));
  }
}
