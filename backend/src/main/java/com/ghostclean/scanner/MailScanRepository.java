package com.ghostclean.scanner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MailScanRepository extends JpaRepository<MailScan, UUID> {

  Optional<MailScan> findByIdAndUser_Id(UUID id, UUID userId);

  List<MailScan> findByUser_IdOrderByCreatedAtDesc(UUID userId);

  Optional<MailScan> findByUser_IdAndIdempotencyKey(UUID userId, String idempotencyKey);

  @Query("select count(s) > 0 from MailScan s where s.user.id = :userId "
      + "and s.status in ('QUEUED', 'RUNNING')")
  boolean existsActiveForUser(UUID userId);
}
