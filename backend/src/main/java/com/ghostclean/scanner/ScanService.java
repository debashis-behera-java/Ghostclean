package com.ghostclean.scanner;

import com.ghostclean.account.AccountService;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.RlsContext;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.OAuthException;
import com.ghostclean.detection.DetectionEngine;
import com.ghostclean.detection.DomainNormalizer;
import com.ghostclean.detection.ml.CompositeScore;
import com.ghostclean.detection.ml.CompositeScorer;
import com.ghostclean.detection.ServiceEvidence;
import com.ghostclean.detection.ServiceEvidenceRepository;
import com.ghostclean.mailbox.GmailProperties;
import com.ghostclean.mailbox.GmailQueryBuilder;
import com.ghostclean.mailbox.GraphProperties;
import com.ghostclean.mailbox.GraphQueryBuilder;
import com.ghostclean.mailbox.MailboxReader;
import com.ghostclean.mailbox.MailboxApiException;
import com.ghostclean.oauth.GoogleOAuthProperties;
import com.ghostclean.oauth.MicrosoftOAuthProperties;
import com.ghostclean.oauth.OAuthConnection;
import com.ghostclean.oauth.OAuthConnectionRepository;
import com.ghostclean.oauth.OAuthService;
import com.ghostclean.oauth.OAuthTokenRotation;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryService;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded synchronous mailbox scan orchestration (Phase 4: Gmail; Phase 10:
 * + Microsoft Graph over the same pipeline).
 *
 * <p>Setup commits first; the page loop then runs outside any long
 * transaction, persisting progress per page so polling and cooperative
 * cancellation observe live state. One active scan per user (app check +
 * partial unique index). Token refresh reuses the Phase 3 connection
 * infrastructure exactly: proactive inside the 5-minute skew, reactive once
 * more on a mid-scan 401; anything harder fails safe to reconnect. Provider
 * failures map to bounded retries, then terminal scan states with stable
 * codes — never raw provider detail.
 */
@Service
public class ScanService {

  private static final Logger log = LoggerFactory.getLogger(ScanService.class);

  private final MailScanRepository scans;
  private final OAuthConnectionRepository connections;
  private final UserRepository users;
  private final Map<String, MailboxReader> readers;
  private final GmailProperties gmail;
  private final GmailQueryBuilder queries;
  private final GoogleOAuthProperties google;
  private final GraphProperties graph;
  private final GraphQueryBuilder graphQueries;
  private final MicrosoftOAuthProperties microsoft;
  private final CompositeScorer composite;
  private final ServiceDirectoryService directory;
  private final ServiceEvidenceRepository evidence;
  private final AccountService accounts;
  private final OAuthService oauth;
  private final OAuthTokenRotation rotation;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;
  private final ScanService self;

  public ScanService(
      MailScanRepository scans,
      OAuthConnectionRepository connections,
      UserRepository users,
      List<MailboxReader> mailboxReaders,
      GmailProperties gmail,
      GmailQueryBuilder queries,
      GoogleOAuthProperties google,
      GraphProperties graph,
      GraphQueryBuilder graphQueries,
      MicrosoftOAuthProperties microsoft,
      CompositeScorer composite,
      ServiceDirectoryService directory,
      ServiceEvidenceRepository evidence,
      AccountService accounts,
      OAuthService oauth,
      OAuthTokenRotation rotation,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls,
      @Lazy ScanService self) {
    this.scans = scans;
    this.connections = connections;
    this.users = users;
    this.readers = mailboxReaders.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            MailboxReader::providerId, reader -> reader));
    this.gmail = gmail;
    this.queries = queries;
    this.google = google;
    this.graph = graph;
    this.graphQueries = graphQueries;
    this.microsoft = microsoft;
    this.composite = composite;
    this.directory = directory;
    this.evidence = evidence;
    this.accounts = accounts;
    this.oauth = oauth;
    this.rotation = rotation;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
    this.self = self;
  }

  /** Validates, records, and synchronously executes a bounded scan. */
  public MailScan startScan(UUID userId, UUID connectionId, String idempotencyKey, String ip) {
    UUID scanId = self.beginScan(userId, connectionId, idempotencyKey, ip);
    MailScan existing = scans.findById(scanId).orElseThrow();
    if (existing.isTerminal()) {
      return existing;
    }
    executeScan(scanId, userId, connectionId, ip);
    return scans.findById(scanId).orElseThrow();
  }

  /** Setup transaction: gates, connection checks, idempotency, row creation. */
  @Transactional
  public UUID beginScan(UUID userId, UUID connectionId, String idempotencyKey, String ip) {
    rls.apply();
    User user = users.findById(userId).orElseThrow(
        () -> new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
            "Authentication required"));
    requireVerified(user);
    rateLimiter.check("scan:start:user:" + userId, 5, Duration.ofHours(1));
    OAuthConnection connection = connections.findByIdAndUser_Id(connectionId, userId)
        .orElseThrow(() -> new OAuthException(HttpStatus.BAD_REQUEST,
            ApiError.GOOGLE_CONNECTION_REQUIRED, "Google connection required"));
    String provider = providerKey(connection.getProvider());
    MailboxReader activeReader = readers.get(provider);
    if (activeReader == null) {
      throw new OAuthException(HttpStatus.BAD_REQUEST, connectionRequiredCode(provider),
          providerLabel(provider) + " connection required");
    }
    if (!OAuthConnection.STATUS_ACTIVE.equals(connection.getStatus())) {
      throw new OAuthException(HttpStatus.BAD_REQUEST, reauthRequiredCode(provider),
          providerLabel(provider) + " authorization expired; please reconnect");
    }
    String requiredScope = requiredMailboxScope(connection);
    List<String> granted = connection.getScopes() == null
        ? List.of() : List.of(connection.getScopes());
    if (!granted.contains(requiredScope)) {
      throw new OAuthException(HttpStatus.FORBIDDEN, ApiError.SCOPE_INSUFFICIENT,
          "Mailbox permission not granted; please reconnect");
    }
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<MailScan> existing = scans.findByUser_IdAndIdempotencyKey(userId, idempotencyKey);
      if (existing.isPresent()) {
        return existing.get().getId();
      }
    }
    if (scans.existsActiveForUser(userId)) {
      throw new OAuthException(HttpStatus.CONFLICT, ApiError.SCAN_CONFLICT,
          "A scan is already running");
    }
    MailScan scan = new MailScan(user, connection);
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      scan.setIdempotencyKey(idempotencyKey);
    }
    scan.setQueryUsed(queryFor(provider));
    scans.save(scan);
    audit.emit(AuditEvents.SCAN_STARTED, userId, "user",
        "mailbox_scan", scan.getId().toString(), Map.of("provider", provider));
    log.info("Scan started {} for user {}", scan.getId(), userId);
    return scan.getId();
  }

  /** Page loop: runs outside transactions; every page commits independently. */
  void executeScan(UUID scanId, UUID userId, UUID connectionId, String ip) {
    MailScan scan = scans.findById(scanId).orElseThrow();
    OAuthConnection connection = connections.findById(connectionId)
        .orElseThrow(ProviderFailedException::new);
    String provider = providerKey(connection.getProvider());
    ProviderScanConfig config = configFor(provider);
    RetryContext ctx = new RetryContext(scanId, userId, connectionId, ip, provider, config,
        new ApiCallBudget(config.maxApiCalls()), new ScanCursor());
    Instant deadline = Instant.now().plusSeconds(config.maxDurationSeconds());

    try {
      ctx.cursor.accessToken = ensureFreshAccess(ctx);
      String pageToken = null;
      while (true) {
        throwIfCancelled(scanId);
        if (ctx.cursor.messages >= config.maxMessagesPerScan()
            || ctx.cursor.pages >= config.maxPages()
            || Instant.now().isAfter(deadline)) {
          break;
        }
        final String cursor = pageToken;
        MailboxReader.MessagePage page = withAuthRetry(
            () -> config.reader().listMessages(ctx.cursor.accessToken, scan.getQueryUsed(),
                cursor, config.pageSize()),
            ctx);
        if (page.resultSizeEstimate() > 0 && ctx.cursor.pages == 0) {
          self.recordProgress(scanId, page.resultSizeEstimate(), 0, 0, 0, 0);
        }
        ctx.cursor.pages++;
        if (page.messageIds().isEmpty()) {
          break;
        }
        for (String messageId : page.messageIds()) {
          throwIfCancelled(scanId);
          if (ctx.cursor.messages >= config.maxMessagesPerScan()
              || Instant.now().isAfter(deadline)) {
            break;
          }
          self.processOne(scanId, userId, messageId, ctx);
        }
        self.recordProgress(scanId, null, ctx.cursor.messages, ctx.cursor.messages,
            ctx.cursor.candidates(), ctx.cursor.errors);
        pageToken = page.nextPageToken();
        if (pageToken == null || pageToken.isBlank()) {
          break;
        }
      }
      complete(ctx);
    } catch (RefreshedOnce e) {
      fail(ctx, reauthRequiredCode(ctx.provider), "reauth");
    } catch (ScopeMissingException e) {
      fail(ctx, ApiError.SCOPE_INSUFFICIENT, "scope");
    } catch (CancelledException e) {
      complete(ctx);
    } catch (ProviderFailedException e) {
      fail(ctx, ApiError.PROVIDER_ERROR, "provider");
    }
  }

  /** One message: fetch metadata, normalize, score, persist, roll up. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void processOne(UUID scanId, UUID userId, String messageId, RetryContext ctx) {
    rls.apply();
    MailboxReader.MessageMetadata meta;
    try {
      meta = withAuthRetry(
          () -> ctx.config.reader().getMessage(ctx.cursor.accessToken, messageId), ctx);
    } catch (MailboxApiException ex) {
      if (ex.getStatus() == 404) {
        return; // deleted between list and get; not an error
      }
      throw ex;
    }
    ctx.cursor.messages++;
    ctx.cursor.newestSeen = Math.max(ctx.cursor.newestSeen, meta.internalDateMillis());

    String domain = DomainNormalizer.extractDomain(meta.from());
    if (domain.isEmpty()) {
      return;
    }
    String subject = truncateSubject(meta.subject());
    ServiceDirectory hit = directory.lookup(domain).orElse(null);
    DetectionEngine.NormalizedMessage normalized = new DetectionEngine.NormalizedMessage(
        domain, meta.from(), subject, meta.internalDateMillis(), meta.listUnsubscribePresent());
    // Phase 13: rule-authoritative composite score. With detection.ml.enabled=false
    // (default) this is exactly the DetectionEngine result with no network call.
    CompositeScore scored = composite.score(normalized, hit);
    if (scored.finalScore() < ctx.config.persistThreshold()) {
      return;
    }
    if (evidence.existsByUser_IdAndProviderAndProviderMessageId(
        userId, ctx.provider, meta.id())) {
      return;
    }
    String serviceKey;
    String displayName;
    String category;
    UUID directoryId = null;
    if (hit != null) {
      serviceKey = hit.getSlug();
      displayName = hit.getName();
      category = hit.getCategory();
      directoryId = hit.getId();
    } else {
      serviceKey = DomainNormalizer.registrableDomain(domain);
      displayName = DomainNormalizer.displayName(domain);
      category = "other";
    }
    if (serviceKey == null || serviceKey.isBlank()) {
      return;
    }
    User user = users.findById(userId).orElseThrow();
    ServiceEvidence row = ServiceEvidence.create();
    row.setUser(user);
    row.setScanId(scanId);
    row.setSource("oauth");
    row.setProvider(ctx.provider);
    row.setProviderMessageId(meta.id());
    row.setSenderDomain(DomainNormalizer.asciiLower(domain));
    row.setSenderAddressHash(sha256Hex(meta.from().trim().toLowerCase()));
    row.setSubjectTruncated(subject);
    row.setReceivedAt(meta.internalDateMillis() > 0
        ? Instant.ofEpochMilli(meta.internalDateMillis()) : Instant.now());
    row.setMatchedRules(Map.of("rules", new ArrayList<>(scored.matchedRules())));
    row.setSignalSnapshot(Map.of(
        "band", scored.band().name(),
        "score", scored.finalScore(),
        "ruleScore", scored.ruleScore(),
        "mlDelta", scored.mlDelta(),
        "directory", directoryId != null));
    row.setConfidenceContrib(scored.finalScore());
    try {
      AccountService.ApplyResult applied = accounts.applyEvidence(
          userId, hit, serviceKey, displayName, category, row);
      ctx.cursor.touchedAccounts.add(applied.accountId());
    } catch (DataIntegrityViolationException ex) {
      // Lost a dedupe race with a concurrent write; the evidence exists.
    }
  }

  // -- reads / cancel / delete ---------------------------------------------------

  @Transactional(readOnly = true)
  public MailScan ownedScan(UUID userId, UUID scanId) {
    rls.apply();
    return scans.findByIdAndUser_Id(scanId, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Scan not found"));
  }

  @Transactional(readOnly = true)
  public List<MailScan> listScans(UUID userId) {
    rls.apply();
    return scans.findByUser_IdOrderByCreatedAtDesc(userId);
  }

  @Transactional
  public MailScan cancelScan(UUID userId, UUID scanId, String ip) {
    rls.apply();
    rateLimiter.check("scan:cancel:user:" + userId, 30, Duration.ofMinutes(1));
    MailScan scan = ownedScan(userId, scanId);
    if (scan.isTerminal()) {
      throw new OAuthException(HttpStatus.CONFLICT, ApiError.SCAN_CONFLICT,
          "Scan already finished");
    }
    scan.setCancelRequested(true);
    scan.setStatus(MailScan.STATUS_CANCELLED);
    scan.setEndedAt(Instant.now());
    scans.save(scan);
    audit.emit(AuditEvents.SCAN_CANCELLED, userId, "user",
        "mailbox_scan", scanId.toString(),
        Map.of("provider", providerKey(scan.getConnection().getProvider())));
    log.info("Scan cancelled {} for user {}", scanId, userId);
    return scan;
  }

  @Transactional
  public void deleteScan(UUID userId, UUID scanId) {
    rls.apply();
    MailScan scan = ownedScan(userId, scanId);
    if (!scan.isTerminal()) {
      throw new OAuthException(HttpStatus.CONFLICT, ApiError.SCAN_CONFLICT,
          "Only finished scans can be deleted");
    }
    scans.delete(scan);
  }

  // -- internals -----------------------------------------------------------------

  private String ensureFreshAccess(RetryContext ctx) {
    OAuthConnection connection = connections.findById(ctx.connectionId).orElseThrow(
        () -> new OAuthException(HttpStatus.BAD_REQUEST, ApiError.GOOGLE_CONNECTION_REQUIRED,
            "Google connection required"));
    Instant skew = Instant.now().plusSeconds(300);
    if (connection.getExpiresAt() == null || !connection.getExpiresAt().isAfter(skew)) {
      doRefresh(ctx);
      connection = connections.findById(ctx.connectionId).orElseThrow(RefreshedOnce::new);
    }
    String access = decryptCurrent(connection);
    if (access == null) {
      throw new RefreshedOnce();
    }
    return access;
  }

  private void doRefresh(RetryContext ctx) {
    try {
      oauth.refreshConnection(ctx.userId, ctx.connectionId, ctx.ip);
    } catch (OAuthException ex) {
      throw new RefreshedOnce();
    }
  }

  private String decryptCurrent(OAuthConnection connection) {
    return rotation.decryptAccess(connection);
  }

  private void throwIfCancelled(UUID scanId) {
    MailScan fresh = scans.findById(scanId).orElseThrow();
    if (fresh.isCancelRequested() || MailScan.STATUS_CANCELLED.equals(fresh.getStatus())) {
      throw new CancelledException();
    }
  }

  /** Retries transient Gmail failures with backoff; 401 refreshes once per scan. */
  private <T> T withAuthRetry(ThrowingSupplier<T> call, RetryContext ctx) {
    try {
      return withRetry(call, ctx);
    } catch (UnauthorizedException e) {
      if (ctx.refreshed) {
        throw new RefreshedOnce();
      }
      ctx.refreshed = true;
      doRefresh(ctx);
      OAuthConnection reloaded = connections.findById(ctx.connectionId)
          .orElseThrow(RefreshedOnce::new);
      String access = decryptCurrent(reloaded);
      if (access == null) {
        throw new RefreshedOnce();
      }
      ctx.cursor.accessToken = access;
      return withRetry(call, ctx);
    }
  }

  private <T> T withRetry(ThrowingSupplier<T> call, RetryContext ctx) {
    int attempts = Math.max(1, ctx.config.maxAttempts());
    for (int attempt = 1; ; attempt++) {
      ctx.budget.spend();
      try {
        return call.get();
      } catch (MailboxApiException ex) {
        if (ex.getStatus() == 401) {
          throw new UnauthorizedException();
        }
        if (ex.getStatus() == 403 && !ex.isRetryable()) {
          throw new ScopeMissingException();
        }
        if (ex.getStatus() == 404) {
          throw ex;
        }
        if (attempt >= attempts || !ex.isRetryable()) {
          ctx.cursor.errors++;
          throw new ProviderFailedException();
        }
        backoff(attempt, ex.getRetryAfterSeconds(), ctx);
      }
    }
  }

  private void backoff(int attempt, long retryAfterSeconds, RetryContext ctx) {
    long wait = retryAfterSeconds > 0
        ? retryAfterSeconds * 1000L
        : Math.min(ctx.config.maxBackoffMillis(),
            ctx.config.backoffBaseMillis() * (1L << Math.min(attempt, 6)));
    long jitter = ThreadLocalRandom.current().nextLong(0, 250);
    try {
      Thread.sleep(Math.min(wait + jitter, ctx.config.maxBackoffMillis() + 1000L));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ProviderFailedException();
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordProgress(UUID scanId, Integer total, int done, int messages,
      int candidates, int errors) {
    rls.apply();
    MailScan scan = scans.findById(scanId).orElseThrow();
    if (total != null) {
      scan.setProgressTotal(total);
    }
    scan.setProgressDone(done);
    scan.setMessagesScanned(messages);
    scan.setCandidatesDetected(candidates);
    scan.setErrorsCount(errors);
    scans.save(scan);
  }

  private void complete(RetryContext ctx) {
    ScanCursor cursor = ctx.cursor;
    MailScan scan = scans.findById(ctx.scanId).orElseThrow();
    boolean cancelled = scan.isCancelRequested()
        || MailScan.STATUS_CANCELLED.equals(scan.getStatus());
    if (!cancelled) {
      scan.setStatus(MailScan.STATUS_COMPLETED);
      audit.emit(AuditEvents.SCAN_COMPLETED, ctx.userId, "system",
          "mailbox_scan", ctx.scanId.toString(), Map.of(
              "messagesScanned", cursor.messages,
              "candidatesDetected", cursor.candidates(),
              "errors", cursor.errors,
              "provider", ctx.provider));
    }
    scan.setEndedAt(Instant.now());
    scan.setMessagesScanned(cursor.messages);
    scan.setCandidatesDetected(cursor.candidates());
    scan.setErrorsCount(cursor.errors);
    if (cursor.newestSeen > 0) {
      scan.setCheckpoint(Instant.ofEpochMilli(cursor.newestSeen).toString());
    }
    scans.save(scan);
    log.info("Scan {} finished for user {}: {} messages, {} candidates",
        ctx.scanId, ctx.userId, cursor.messages, cursor.candidates());
  }

  private void fail(RetryContext ctx, String errorCode, String reason) {
    MailScan scan = scans.findById(ctx.scanId).orElseThrow();
    scan.setStatus(MailScan.STATUS_FAILED);
    scan.setEndedAt(Instant.now());
    scan.setErrorCode(errorCode);
    scans.save(scan);
    if (reauthRequiredCode(ctx.provider).equals(errorCode)) {
      audit.emit(isMicrosoft(ctx.provider)
              ? AuditEvents.MICROSOFT_REAUTH_REQUIRED
              : AuditEvents.GOOGLE_REAUTH_REQUIRED,
          ctx.userId, "system",
          "mailbox_scan", ctx.scanId.toString(), Map.of("provider", ctx.provider));
    } else {
      audit.emit(AuditEvents.SCAN_FAILED, ctx.userId, "system",
          "mailbox_scan", ctx.scanId.toString(),
          Map.of("provider", ctx.provider, "reason", reason));
    }
    log.info("Scan {} failed for user {}: {}", ctx.scanId, ctx.userId, errorCode);
  }

  private String requiredMailboxScope(OAuthConnection connection) {
    String scope = isMicrosoft(connection.getProvider())
        ? microsoft.getMailboxScope()
        : google.getMailboxScope();
    if (scope == null || scope.isBlank()) {
      throw new OAuthException(HttpStatus.BAD_GATEWAY, ApiError.PROVIDER_ERROR,
          "Mailbox access is not configured");
    }
    return scope.trim();
  }

  // -- provider dispatch (Phase 10: readers, budgets, queries, codes) ----------

  /** Normalized provider key; unknown values stay Google-shaped (legacy). */
  static String providerKey(String provider) {
    return provider == null ? "" : provider.trim().toLowerCase();
  }

  private static boolean isMicrosoft(String provider) {
    return "microsoft".equals(providerKey(provider));
  }

  private static String providerLabel(String provider) {
    return isMicrosoft(provider) ? "Microsoft" : "Google";
  }

  private static String connectionRequiredCode(String provider) {
    return isMicrosoft(provider)
        ? ApiError.MICROSOFT_CONNECTION_REQUIRED
        : ApiError.GOOGLE_CONNECTION_REQUIRED;
  }

  private static String reauthRequiredCode(String provider) {
    return isMicrosoft(provider)
        ? ApiError.MICROSOFT_REAUTH_REQUIRED
        : ApiError.GOOGLE_REAUTH_REQUIRED;
  }

  private String queryFor(String provider) {
    if (isMicrosoft(provider)) {
      return graphQueries.buildLookbackFilter(
          graph.getQueryKeywords(), graph.getLookbackMonths());
    }
    return queries.buildLookbackQuery(
        gmail.getQueryKeywords(), gmail.getLookbackMonths() * 30);
  }

  /** Per-provider scan budgets; unknown readers fail safe before use. */
  private ProviderScanConfig configFor(String provider) {
    MailboxReader activeReader = readers.get(providerKey(provider));
    if (activeReader == null) {
      throw new ProviderFailedException();
    }
    if (isMicrosoft(provider)) {
      return new ProviderScanConfig(activeReader,
          graph.getMaxAttempts(), graph.getBackoffBaseMillis(), graph.getMaxBackoffMillis(),
          graph.getPersistThreshold(), graph.getMaxApiCalls(), graph.getMaxDurationSeconds(),
          graph.getMaxMessagesPerScan(), graph.getMaxPages(), graph.getPageSize());
    }
    return new ProviderScanConfig(activeReader,
        gmail.getMaxAttempts(), gmail.getBackoffBaseMillis(), gmail.getMaxBackoffMillis(),
        gmail.getPersistThreshold(), gmail.getMaxApiCalls(), gmail.getMaxDurationSeconds(),
        gmail.getMaxMessagesPerScan(), gmail.getMaxPages(), gmail.getPageSize());
  }

  /** Per-scan provider budgets resolved once at scan start. */
  record ProviderScanConfig(
      MailboxReader reader,
      int maxAttempts,
      long backoffBaseMillis,
      long maxBackoffMillis,
      int persistThreshold,
      int maxApiCalls,
      int maxDurationSeconds,
      int maxMessagesPerScan,
      int maxPages,
      int pageSize) {
  }

  private void requireVerified(User user) {
    if (!user.isEmailVerified()) {
      throw new AuthException(HttpStatus.FORBIDDEN, ApiError.EMAIL_NOT_VERIFIED,
          "Email verification required");
    }
  }

  private static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  /** Truncates subjects to the evidence cap with control-char stripping. */
  static String truncateSubject(String subject) {
    if (subject == null) {
      return "";
    }
    StringBuilder clean = new StringBuilder(subject.length());
    for (int i = 0; i < subject.length(); i++) {
      char c = subject.charAt(i);
      if (c == '<' || c == '>') {
        continue;
      }
      if (!Character.isISOControl(c)) {
        clean.append(c);
      }
    }
    String trimmed = clean.toString().trim();
    return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get();
  }

  /** Mutable per-scan loop state (single-threaded; never shared). */
  static class RetryContext {
    final UUID scanId;
    final UUID userId;
    final UUID connectionId;
    final String ip;
    final String provider;
    final ProviderScanConfig config;
    final ApiCallBudget budget;
    final ScanCursor cursor;
    boolean refreshed;

    RetryContext(UUID scanId, UUID userId, UUID connectionId, String ip, String provider,
        ProviderScanConfig config, ApiCallBudget budget, ScanCursor cursor) {
      this.scanId = scanId;
      this.userId = userId;
      this.connectionId = connectionId;
      this.ip = ip;
      this.provider = provider;
      this.config = config;
      this.budget = budget;
      this.cursor = cursor;
    }
  }

  static class ScanCursor {
    String accessToken;
    int messages;
    int errors;
    int pages;
    long newestSeen;
    final Set<UUID> touchedAccounts = new HashSet<>();

    int candidates() {
      return touchedAccounts.size();
    }
  }

  /** Hard ceiling on provider calls per scan. */
  static class ApiCallBudget {
    private final int max;
    private int used;

    ApiCallBudget(int max) {
      this.max = max;
    }

    void spend() {
      if (++used > max) {
        throw new ProviderFailedException();
      }
    }
  }

  /** Control-flow signals (never cross the API boundary). */
  static class UnauthorizedException extends RuntimeException {
  }

  static class ScopeMissingException extends RuntimeException {
  }

  static class ProviderFailedException extends RuntimeException {
  }

  static class CancelledException extends RuntimeException {
  }

  static class RefreshedOnce extends RuntimeException {
  }
}
