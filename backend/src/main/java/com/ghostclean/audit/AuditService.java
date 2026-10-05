package com.ghostclean.audit;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single choke point for audit writes. Metadata keys are denylisted against
 * secret-bearing names so a future caller cannot smuggle a credential into
 * the trail by accident; violations fail loudly in development and tests.
 */
@Service
public class AuditService {

  private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
      "password", "token", "secret", "hash", "credential", "authorization", "cookie", "key");

  private final AuditLogRepository repository;

  public AuditService(AuditLogRepository repository) {
    this.repository = repository;
  }

  /** User-attributed event (actor {@code user}, or {@code anonymous} pre-login). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void emit(String event, UUID userId, String actor,
      String entityType, String entityId, Map<String, Object> metadata) {
    if (metadata != null) {
      for (String key : metadata.keySet()) {
        String lower = key.toLowerCase();
        for (String fragment : FORBIDDEN_KEY_FRAGMENTS) {
          if (lower.contains(fragment)) {
            throw new IllegalArgumentException(
                "Audit metadata must never carry secrets (offending key: " + key + ")");
          }
        }
      }
    }
    AuditLog log = new AuditLog(event, actor);
    log.setUserId(userId);
    log.setEntityType(entityType);
    log.setEntityId(entityId);
    log.setMetadata(metadata);
    repository.save(log);
  }

  /** Anonymous event (no user yet, e.g. failed register/login). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void emitAnonymous(String event, Map<String, Object> metadata) {
    emit(event, null, "anonymous", null, null, metadata);
  }
}
