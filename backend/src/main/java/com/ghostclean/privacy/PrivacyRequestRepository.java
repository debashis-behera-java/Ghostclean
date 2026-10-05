package com.ghostclean.privacy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrivacyRequestRepository extends JpaRepository<PrivacyRequest, UUID> {

  Optional<PrivacyRequest> findByIdAndUser_Id(UUID id, UUID userId);

  List<PrivacyRequest> findByUser_IdOrderByCreatedAtDesc(UUID userId);

  List<PrivacyRequest> findByUser_IdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

  Optional<PrivacyRequest> findByUser_IdAndIdempotencyKey(UUID userId, String idempotencyKey);

  @Query("select r from PrivacyRequest r where r.user.id = :userId "
      + "and r.discoveredServiceId = :accountId and r.requestType = :type "
      + "and r.status in ('DRAFT', 'READY_FOR_REVIEW')")
  Optional<PrivacyRequest> findActiveForAccount(
      @Param("userId") UUID userId,
      @Param("accountId") UUID accountId,
      @Param("type") String type);
}
