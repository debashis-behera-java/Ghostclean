package com.ghostclean.detection;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceEvidenceRepository extends JpaRepository<ServiceEvidence, UUID> {

  boolean existsByUser_IdAndProviderAndProviderMessageId(
      UUID userId, String provider, String providerMessageId);

  List<ServiceEvidence> findByUser_IdAndDiscoveredServiceIdOrderByReceivedAtDesc(
      UUID userId, UUID discoveredServiceId);

  long countByUser_IdAndDiscoveredServiceId(UUID userId, UUID discoveredServiceId);

  void deleteByUser_IdAndDiscoveredServiceId(UUID userId, UUID discoveredServiceId);
}
