package com.ghostclean.privacy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyDispatchRepository extends JpaRepository<PrivacyDispatch, UUID> {

  Optional<PrivacyDispatch> findByRequest_IdAndRequest_User_Id(UUID requestId, UUID userId);

  Optional<PrivacyDispatch> findByRequest_Id(UUID requestId);

  Optional<PrivacyDispatch> findByIdempotencyKey(String idempotencyKey);
}
