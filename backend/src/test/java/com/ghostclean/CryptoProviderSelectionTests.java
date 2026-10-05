package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.common.crypto.CryptoConfig;
import com.ghostclean.common.crypto.EnvAesGcmTokenCrypto;
import com.ghostclean.common.crypto.TokenCryptoProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Local-only crypto selection (ADR-036): the local default stays env-backed
 * without AWS. Validates env provider selection, fail-fast on missing keys,
 * version stamping, and local round-trips. No KMS client, no AWS/network.
 */
class CryptoProviderSelectionTests {

  private static String readRepoFile(String... parts) throws Exception {
    Path direct = Paths.get(String.join("/", parts));
    if (Files.exists(direct)) {
      return Files.readString(direct);
    }
    Path prefixed = Paths.get("backend/" + String.join("/", parts));
    assertTrue(Files.exists(prefixed), "repo file must exist: " + prefixed);
    return Files.readString(prefixed);
  }

  @Test
  void defaultProviderIsLocalEnvWithoutAws() throws Exception {
    String yml = readRepoFile("src", "main", "resources", "application.yml");
    assertTrue(yml.contains("provider: ${TOKEN_CRYPTO_PROVIDER:env}"),
        "default crypto provider must be env");
    String config = readRepoFile("src", "main", "java", "com", "ghostclean",
        "common", "crypto", "CryptoConfig.java");
    assertTrue(config.contains("havingValue = \"env\"") && config.contains("matchIfMissing = true"),
        "env provider must be the match-if-missing default");

    TokenCryptoProvider env = new CryptoConfig()
        .envTokenCrypto(TestKeys.syntheticEncryptionKey());
    assertEquals(1, env.keyVersion(), "env provider must stamp version 1");
    assertEquals("local-only-value", env.decrypt(env.encrypt("local-only-value")),
        "local env crypto must round-trip without AWS");
  }

  @Test
  void envMisconfigurationFailsFast() {
    assertThrows(IllegalStateException.class,
        () -> new EnvAesGcmTokenCrypto(""),
        "missing env key must fail fast without AWS");
    assertThrows(IllegalStateException.class,
        () -> new EnvAesGcmTokenCrypto(null),
        "null env key must fail fast without AWS");
  }

  @Test
  void envKeyVersionIsOne() {
    EnvAesGcmTokenCrypto env =
        new EnvAesGcmTokenCrypto(TestKeys.syntheticEncryptionKey());
    assertEquals(1, env.keyVersion(), "env provider version must be 1");
  }
}
