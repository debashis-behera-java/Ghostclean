package com.ghostclean.privacy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyRequestResponseRepository extends JpaRepository<PrivacyRequestResponse, UUID> {

  List<PrivacyRequestResponse> findByRequest_IdOrderByRecordedAtDesc(UUID requestId);

  long countByRequest_Id(UUID requestId);
}
