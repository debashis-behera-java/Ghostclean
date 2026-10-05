package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.auth.PasswordConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Hashing infrastructure is wired and one-way (no container needed). */
class PasswordEncoderTests {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PasswordConfig.class);

  @Test
  void encoderHashesAndVerifies() {
    runner.run(context -> {
      PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
      String hash = encoder.encode("phase1-test-only-password");
      assertNotEquals("phase1-test-only-password", hash);
      assertTrue(encoder.matches("phase1-test-only-password", hash));
    });
  }
}
