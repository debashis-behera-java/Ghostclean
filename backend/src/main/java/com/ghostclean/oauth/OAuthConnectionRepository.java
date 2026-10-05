package com.ghostclean.oauth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthConnectionRepository extends JpaRepository<OAuthConnection, UUID> {

  List<OAuthConnection> findByUser_Id(UUID userId);

  Optional<OAuthConnection> findByIdAndUser_Id(UUID id, UUID userId);

  Optional<OAuthConnection> findByUser_IdAndProviderAndProviderAccountId(
      UUID userId, String provider, String providerAccountId);

  /** Rotation-CLI sweep set: rows not yet on the active key version. */
  List<OAuthConnection> findByKeyVersionNot(int keyVersion);

  /** Bounded sweep page honoring the operator-supplied row cap. */
  org.springframework.data.domain.Page<OAuthConnection> findByKeyVersionNot(
      int keyVersion, org.springframework.data.domain.Pageable pageable);
}
