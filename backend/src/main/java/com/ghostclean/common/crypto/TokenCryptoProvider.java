package com.ghostclean.common.crypto;

/**
 * Encrypts small secrets (future OAuth tokens) at rest.
 *
 * <p>Implementations must never log key material or plaintext. Ciphertext
 * returned by V1 is {@code Base64(iv || ciphertext)} using AES-256-GCM with a
 * fresh 96-bit IV per call.
 */
public interface TokenCryptoProvider {

  /** Encrypts plaintext; never returns or logs the key. */
  String encrypt(String plaintext);

  /** Decrypts ciphertext produced by {@link #encrypt(String)}. */
  String decrypt(String ciphertext);

  /** Key version stamped on rows to support future rotation/KMS cutover. */
  int keyVersion();
}
