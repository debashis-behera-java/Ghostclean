package com.ghostclean.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;

/**
 * V1 {@link TokenCryptoProvider} backed exclusively by the
 * {@code TOKEN_ENCRYPTION_KEY} environment variable (32 bytes, Base64).
 *
 * <p>Instantiated by {@link CryptoConfig} (default provider). Fail-fast:
 * construction refuses missing or shorter-than-256-bit keys. The key is
 * never logged.
 */
public class EnvAesGcmTokenCrypto implements TokenCryptoProvider {

  private static final int MIN_KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;
  private static final int KEY_VERSION = 1;

  private final byte[] keyBytes;
  private final SecureRandom random = new SecureRandom();

  public EnvAesGcmTokenCrypto(
      @Value("${crypto.token-encryption-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalStateException(
          "Missing required encryption configuration: set TOKEN_ENCRYPTION_KEY "
              + "(32 bytes, Base64). Refusing to start without it.");
    }
    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64Key.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Invalid encryption configuration: TOKEN_ENCRYPTION_KEY is not valid Base64.", ex);
    }
    if (decoded.length < MIN_KEY_BYTES) {
      Arrays.fill(decoded, (byte) 0);
      throw new IllegalStateException(
          "Invalid encryption configuration: key must be at least 256 bits (32 bytes).");
    }
    this.keyBytes = Arrays.copyOf(decoded, decoded.length);
    Arrays.fill(decoded, (byte) 0);
  }

  @Override
  public String encrypt(String plaintext) {
    if (plaintext == null) {
      throw new IllegalArgumentException("plaintext must not be null");
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] cipherBytes =
          cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[IV_BYTES + cipherBytes.length];
      System.arraycopy(iv, 0, out, 0, IV_BYTES);
      System.arraycopy(cipherBytes, 0, out, IV_BYTES, cipherBytes.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception ex) {
      throw new IllegalStateException("Encryption failed", ex);
    }
  }

  @Override
  public String decrypt(String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      throw new IllegalArgumentException("ciphertext must not be null or blank");
    }
    try {
      byte[] raw = Base64.getDecoder().decode(ciphertext.trim());
      if (raw.length <= IV_BYTES) {
        throw new IllegalArgumentException("Malformed ciphertext");
      }
      byte[] iv = Arrays.copyOfRange(raw, 0, IV_BYTES);
      byte[] cipherBytes = Arrays.copyOfRange(raw, IV_BYTES, raw.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
      return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Decryption failed", ex);
    }
  }

  @Override
  public int keyVersion() {
    return KEY_VERSION;
  }
}
