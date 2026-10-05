package com.ghostclean.account;

import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.common.RlsContext;
import com.ghostclean.detection.DetectionEngine;
import com.ghostclean.detection.ServiceEvidence;
import com.ghostclean.detection.ServiceEvidenceRepository;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;

/**
 * Account rollup + review lifecycle. Many evidence rows become one
 * {@link DiscoveredService} per (user, service key); confidence recomputes
 * as max evidence score plus corroboration (+5 per additional distinct
 * matched rule, cap +15) plus frequency (+10 at ≥3 evidences within 90
 * days), clamped 0–100. Dismissed accounts keep collecting evidence rows
 * but freeze status and confidence — the user's decision wins until they
 * change it.
 */
@Service
public class AccountService {

  private final DiscoveredServiceRepository accounts;
  private final ServiceEvidenceRepository evidence;
  private final UserRepository users;
  private final AuditService audit;
  private final RlsContext rls;

  public AccountService(
      DiscoveredServiceRepository accounts,
      ServiceEvidenceRepository evidence,
      UserRepository users,
      AuditService audit,
      RlsContext rls) {
    this.accounts = accounts;
    this.evidence = evidence;
    this.users = users;
    this.audit = audit;
    this.rls = rls;
  }

  /** Applies one scored evidence row to its account, creating it if needed. */
  @Transactional
  public ApplyResult applyEvidence(UUID userId, ServiceDirectory hit, String serviceKey,
      String displayName, String category, ServiceEvidence row) {
    rls.apply();
    User user = users.findById(userId).orElseThrow(
        () -> new IllegalStateException("Evidence owner does not exist"));
    DiscoveredService account = accounts.findByUser_IdAndServiceKey(userId, serviceKey)
        .orElseGet(() -> {
          DiscoveredService created = new DiscoveredService(user, serviceKey, displayName);
          created.setCategory(category);
          if (hit != null) {
            created.setServiceDirectory(hit);
          }
          boolean isNew = true;
          audit.emit(AuditEvents.ACCOUNT_EVIDENCE_DETECTED, userId, "system",
              "discovered_service", serviceKey, Map.of("isNew", isNew));
          return accounts.save(created);
        });
    boolean created = account.getEvidenceCount() == 0 && account.getFirstSeenAt() == null;
    row.setDiscoveredServiceId(account.getId());
    evidence.save(row);

    if (!DiscoveredService.STATUS_DISMISSED.equals(account.getStatus())) {
      recompute(account);
      accounts.save(account);
    }
    return new ApplyResult(account.getId(), created);
  }

  /** Recomputes confidence from all stored evidence of the account. */
  @Transactional
  public void recompute(UUID userId, UUID accountId) {
    rls.apply();
    DiscoveredService account = ownedAccount(userId, accountId);
    recompute(account);
    accounts.save(account);
  }

  /** keep → REVIEWED, dismiss → DISMISSED (+reason). Unknown decisions 400. */
  @Transactional
  public DiscoveredService review(UUID userId, UUID accountId, String decision, String reason) {
    rls.apply();
    DiscoveredService account = ownedAccount(userId, accountId);
    if ("dismiss".equalsIgnoreCase(decision)) {
      account.setStatus(DiscoveredService.STATUS_DISMISSED);
      account.setDismissedReason(reason != null && reason.length() > 300
          ? reason.substring(0, 300) : reason);
      audit.emit(AuditEvents.ACCOUNT_EVIDENCE_DISMISSED, userId, "user",
          "discovered_service", accountId.toString(), Map.of("status", "DISMISSED"));
    } else if ("keep".equalsIgnoreCase(decision)) {
      account.setStatus(DiscoveredService.STATUS_REVIEWED);
      account.setDismissedReason(null);
    } else {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Decision must be keep or dismiss");
    }
    return accounts.save(account);
  }

  /**
   * Deletes an account's evidence and recomputes; removes the account when
   * nothing remains. Returns true when the account itself was removed.
   */
  @Transactional
  public boolean purgeEvidence(UUID userId, UUID accountId) {
    rls.apply();
    DiscoveredService account = ownedAccount(userId, accountId);
    List<ServiceEvidence> rows =
        evidence.findByUser_IdAndDiscoveredServiceIdOrderByReceivedAtDesc(userId, accountId);
    int purged = rows.size();
    evidence.deleteByUser_IdAndDiscoveredServiceId(userId, accountId);
    if (purged == 0) {
      recompute(account);
      accounts.save(account);
      return false;
    }
    List<ServiceEvidence> remaining =
        evidence.findByUser_IdAndDiscoveredServiceIdOrderByReceivedAtDesc(userId, accountId);
    if (remaining.isEmpty()) {
      accounts.delete(account);
      audit.emit(AuditEvents.EVIDENCE_PURGED, userId, "user",
          "discovered_service", accountId.toString(), Map.of("purged", purged));
      return true;
    }
    recompute(account);
    accounts.save(account);
    audit.emit(AuditEvents.EVIDENCE_PURGED, userId, "user",
        "discovered_service", accountId.toString(), Map.of("purged", purged));
    return false;
  }

  @Transactional(readOnly = true)
  public List<DiscoveredService> list(UUID userId, String status) {
    rls.apply();
    if (status == null || status.isBlank()) {
      return accounts.findByUser_IdOrderByConfidenceDesc(userId);
    }
    String normalized = status.trim().toUpperCase();
    if (!DiscoveredService.STATUS_DETECTED.equals(normalized)
        && !DiscoveredService.STATUS_REVIEWED.equals(normalized)
        && !DiscoveredService.STATUS_DISMISSED.equals(normalized)) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Unknown status filter");
    }
    return accounts.findByUser_IdAndStatusOrderByConfidenceDesc(userId, normalized);
  }

  @Transactional(readOnly = true)
  public DiscoveredService ownedAccount(UUID userId, UUID accountId) {
    rls.apply();
    return accounts.findByIdAndUser_Id(accountId, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Detected account not found"));
  }

  @Transactional(readOnly = true)
  public List<ServiceEvidence> evidenceOf(UUID userId, UUID accountId) {
    rls.apply();
    ownedAccount(userId, accountId);
    return evidence.findByUser_IdAndDiscoveredServiceIdOrderByReceivedAtDesc(userId, accountId);
  }

  private void recompute(DiscoveredService account) {
    List<ServiceEvidence> rows = evidence.findByUser_IdAndDiscoveredServiceIdOrderByReceivedAtDesc(
        account.getUser().getId(), account.getId());
    if (rows.isEmpty()) {
      account.setEvidenceCount(0);
      account.setConfidence(0);
      account.setConfidenceBand(DetectionEngine.ConfidenceBand.LOW.name());
      account.setFirstSeenAt(null);
      account.setLastSeenAt(null);
      return;
    }
    int max = 0;
    Set<String> distinctRules = new HashSet<>();
    Instant first = null;
    Instant last = null;
    int recentCount = 0;
    Instant ninetyDaysAgo = Instant.now().minusSeconds(90L * 24 * 3600);
    for (ServiceEvidence row : rows) {
      max = Math.max(max, row.getConfidenceContrib());
      if (row.getMatchedRules() != null && row.getMatchedRules().get("rules") instanceof List<?> list) {
        for (Object rule : list) {
          distinctRules.add(String.valueOf(rule));
        }
      }
      if (row.getReceivedAt() != null) {
        if (first == null || row.getReceivedAt().isBefore(first)) {
          first = row.getReceivedAt();
        }
        if (last == null || row.getReceivedAt().isAfter(last)) {
          last = row.getReceivedAt();
        }
        if (!row.getReceivedAt().isBefore(ninetyDaysAgo)) {
          recentCount++;
        }
      }
    }
    int score = max + Math.min(15, Math.max(0, (distinctRules.size() - 1) * 5));
    if (recentCount >= 3) {
      score += 10;
    }
    score = Math.max(0, Math.min(100, score));
    account.setEvidenceCount(rows.size());
    account.setConfidence(score);
    account.setConfidenceBand(DetectionEngine.ConfidenceBand.of(score).name());
    account.setFirstSeenAt(first);
    account.setLastSeenAt(last);
  }

  public record ApplyResult(UUID accountId, boolean created) {
  }
}
