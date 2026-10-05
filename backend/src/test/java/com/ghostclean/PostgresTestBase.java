package com.ghostclean;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL (Testcontainers) + synthetic crypto-key wiring for every
 * Spring integration test. One container per JVM (static, started once).
 *
 * <p>The encryption key below is obviously synthetic and test-only: the ASCII
 * string "12345678901234567890123456789012" (32 bytes), Base64-encoded inline
 * so no key-like literal ever sits in the repository.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresTestBase {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  /** Synthetic 32-byte test key (see {@link TestKeys}); never a stored literal. */
  public static String syntheticTestKey() {
    return TestKeys.syntheticEncryptionKey();
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("crypto.token-encryption-key", PostgresTestBase::syntheticTestKey);
    registry.add("auth.jwt-secret", TestKeys::syntheticJwtSecret);
  }
}
