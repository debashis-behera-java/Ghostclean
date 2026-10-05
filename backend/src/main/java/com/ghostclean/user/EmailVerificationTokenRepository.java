package com.ghostclean.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  /** Active (unused) tokens for the single-active invariant. */
  List<EmailVerificationToken> findByUser_IdAndUsedAtIsNull(UUID userId);
}
