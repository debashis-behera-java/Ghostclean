package com.ghostclean.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

  Optional<RefreshSession> findByTokenHash(String tokenHash);

  /** All sessions of a user (reuse-detection chain revocation). */
  List<RefreshSession> findByUser_Id(UUID userId);
}
