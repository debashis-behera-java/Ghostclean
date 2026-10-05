package com.ghostclean.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

  List<AuditLog> findByUserIdOrderByIdAsc(UUID userId);
}
