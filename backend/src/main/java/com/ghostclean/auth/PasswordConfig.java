package com.ghostclean.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password-hashing infrastructure (Phase 1).
 *
 * <p>BCrypt with the framework default strength: salted, adaptive, never
 * reversible. No plaintext passwords anywhere. Full registration/login flows
 * arrive in Phase 2.
 */
@Configuration
public class PasswordConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
