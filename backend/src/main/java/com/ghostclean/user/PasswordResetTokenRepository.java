package com.ghostclean.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository
    extends JpaRepository<PasswordResetToken, UUID> {

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  /** Active (unused) tokens for the single-active invariant. */
  List<PasswordResetToken> findByUser_IdAndUsedAtIsNull(UUID userId);
}
