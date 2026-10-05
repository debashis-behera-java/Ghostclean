package com.ghostclean.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiscoveredServiceRepository extends JpaRepository<DiscoveredService, UUID> {

  /**
   * Read paths fetch the linked directory row eagerly so Phase 5
   * enrichment can run outside the service transaction without lazy
   * loading. Method names and ordering are unchanged (Phase 4).
   */
  @Query("select d from DiscoveredService d left join fetch d.serviceDirectory "
      + "where d.user.id = :userId order by d.confidence desc")
  List<DiscoveredService> findByUser_IdOrderByConfidenceDesc(@Param("userId") UUID userId);

  @Query("select d from DiscoveredService d left join fetch d.serviceDirectory "
      + "where d.user.id = :userId and d.status = :status order by d.confidence desc")
  List<DiscoveredService> findByUser_IdAndStatusOrderByConfidenceDesc(
      @Param("userId") UUID userId, @Param("status") String status);

  @Query("select d from DiscoveredService d left join fetch d.serviceDirectory "
      + "where d.id = :id and d.user.id = :userId")
  Optional<DiscoveredService> findByIdAndUser_Id(
      @Param("id") UUID id, @Param("userId") UUID userId);

  Optional<DiscoveredService> findByUser_IdAndServiceKey(UUID userId, String serviceKey);
}
