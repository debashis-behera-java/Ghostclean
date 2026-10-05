package com.ghostclean.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthStateRepository extends JpaRepository<OAuthState, UUID> {

  Optional<OAuthState> findByStateHash(String stateHash);

  /**
   * Atomic single-use gate: marks an unused state row consumed. Concurrent
   * callbacks presenting the same state serialize on the row lock; exactly
   * one update affects a row, losers observe zero affected rows. Callers
   * must run inside a transaction.
   */
  @Modifying
  @Query("UPDATE OAuthState s SET s.usedAt = :usedAt"
      + " WHERE s.stateHash = :stateHash AND s.usedAt IS NULL")
  int claimUnusedByStateHash(
      @Param("stateHash") String stateHash, @Param("usedAt") Instant usedAt);
}
