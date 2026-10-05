package com.ghostclean.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local-only crypto provider selection (ADR-036).
 *
 * <p>{@code crypto.provider=env} (default): the env-key AES-256-GCM
 * provider. Fail-fast when TOKEN_ENCRYPTION_KEY is missing or weak.
 * No AWS/KMS dependency.
 */
@Configuration
public class CryptoConfig {

  @Bean
  @ConditionalOnProperty(name = "crypto.provider", havingValue = "env", matchIfMissing = true)
  public TokenCryptoProvider envTokenCrypto(
      @Value("${crypto.token-encryption-key:}") String base64Key) {
    return new EnvAesGcmTokenCrypto(base64Key);
  }
}
