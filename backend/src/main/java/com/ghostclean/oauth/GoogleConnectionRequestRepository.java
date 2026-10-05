package com.ghostclean.oauth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoogleConnectionRequestRepository
    extends JpaRepository<GoogleConnectionRequest, UUID> {

  List<GoogleConnectionRequest> findByUser_IdOrderByRequestedAtDesc(UUID userId);

  Optional<GoogleConnectionRequest> findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
      UUID userId, String status);

  List<GoogleConnectionRequest> findAllByOrderByRequestedAtDesc();

  List<GoogleConnectionRequest> findByStatusOrderByRequestedAtDesc(String status);
}
