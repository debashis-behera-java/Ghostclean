package com.ghostclean.audit;

/**
 * Audit event vocabulary. Authentication events ship in Phase 2; OAuth, scan,
 * detection, and deletion events arrive with their phases. Audit rows carry
 * ids and outcome flags only — never passwords, tokens, or secrets.
 */
public final class AuditEvents {

  private AuditEvents() {
  }

  // Phase 1
  public static final String USER_REGISTERED = "USER_REGISTERED";

  // Phase 2 — authentication
  public static final String EMAIL_VERIFICATION_REQUESTED = "EMAIL_VERIFICATION_REQUESTED";
  public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";
  public static final String EMAIL_VERIFICATION_FAILED = "EMAIL_VERIFICATION_FAILED";
  public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
  public static final String LOGIN_FAILED = "LOGIN_FAILED";
  public static final String LOGOUT = "LOGOUT";
  public static final String REFRESH_TOKEN_ROTATED = "REFRESH_TOKEN_ROTATED";
  public static final String REFRESH_TOKEN_REVOKED = "REFRESH_TOKEN_REVOKED";
  public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
  public static final String PASSWORD_RESET_SUCCEEDED = "PASSWORD_RESET_SUCCEEDED";
  public static final String PASSWORD_RESET_FAILED = "PASSWORD_RESET_FAILED";

  // Phase 3 — Google OAuth connections. Payloads carry provider/status
  // identifiers only — never codes, tokens, secrets, or emails.
  public static final String OAUTH_CONNECT_STARTED = "OAUTH_CONNECT_STARTED";
  public static final String OAUTH_CONNECT_SUCCEEDED = "OAUTH_CONNECT_SUCCEEDED";
  public static final String OAUTH_CONNECT_FAILED = "OAUTH_CONNECT_FAILED";
  public static final String OAUTH_DISCONNECTED = "OAUTH_DISCONNECTED";

  // Google connection approval (GhostClean business gate before OAuth).
  // Payloads carry ids/status only — never codes, tokens, or emails.
  public static final String GOOGLE_CONNECTION_REQUESTED = "GOOGLE_CONNECTION_REQUESTED";
  public static final String GOOGLE_CONNECTION_APPROVED = "GOOGLE_CONNECTION_APPROVED";
  public static final String GOOGLE_CONNECTION_REJECTED = "GOOGLE_CONNECTION_REJECTED";
  public static final String GOOGLE_CONNECTION_CANCELLED = "GOOGLE_CONNECTION_CANCELLED";

  // Phase 4 — scanning and detection. Payloads carry ids and outcome
  // counts only — never bodies, subjects, tokens, or message content.
  public static final String SCAN_STARTED = "SCAN_STARTED";
  public static final String SCAN_COMPLETED = "SCAN_COMPLETED";
  public static final String SCAN_FAILED = "SCAN_FAILED";
  public static final String SCAN_CANCELLED = "SCAN_CANCELLED";
  public static final String ACCOUNT_EVIDENCE_DETECTED = "ACCOUNT_EVIDENCE_DETECTED";
  public static final String ACCOUNT_EVIDENCE_DISMISSED = "ACCOUNT_EVIDENCE_DISMISSED";
  public static final String GOOGLE_REAUTH_REQUIRED = "GOOGLE_REAUTH_REQUIRED";
  public static final String MICROSOFT_REAUTH_REQUIRED = "MICROSOFT_REAUTH_REQUIRED";
  public static final String EVIDENCE_PURGED = "EVIDENCE_PURGED";

  // Phase 5 — service-intelligence vocabulary for the future admin
  // mechanism. No ordinary-user endpoint emits these (the registry has no
  // user mutation paths); payloads will carry service slugs and outcome
  // flags only — never mailbox content or credentials.
  public static final String SERVICE_METADATA_UPDATED = "SERVICE_METADATA_UPDATED";
  public static final String SERVICE_ROUTE_VERIFIED = "SERVICE_ROUTE_VERIFIED";
  public static final String SERVICE_ROUTE_MARKED_UNKNOWN = "SERVICE_ROUTE_MARKED_UNKNOWN";

  // Phase 6 — privacy-request lifecycle. Payloads carry ids, statuses, and
  // versions only — never request text, bodies, tokens, or secrets.
  public static final String PRIVACY_REQUEST_CREATED = "PRIVACY_REQUEST_CREATED";
  public static final String PRIVACY_REQUEST_EDITED = "PRIVACY_REQUEST_EDITED";
  public static final String PRIVACY_REQUEST_APPROVED = "PRIVACY_REQUEST_APPROVED";
  public static final String PRIVACY_REQUEST_CANCELLED = "PRIVACY_REQUEST_CANCELLED";
  public static final String PRIVACY_REQUEST_EXPIRED = "PRIVACY_REQUEST_EXPIRED";

  // Phase 7 — dispatch lifecycle. Payloads carry ids, statuses, versions,
  // and safe failure categories only — never message content or secrets.
  public static final String PRIVACY_REQUEST_DISPATCH_REQUESTED =
      "PRIVACY_REQUEST_DISPATCH_REQUESTED";
  public static final String PRIVACY_REQUEST_DISPATCH_ACCEPTED =
      "PRIVACY_REQUEST_DISPATCH_ACCEPTED";
  public static final String PRIVACY_REQUEST_DISPATCH_FAILED =
      "PRIVACY_REQUEST_DISPATCH_FAILED";
  public static final String PRIVACY_REQUEST_DISPATCH_CANCELLED =
      "PRIVACY_REQUEST_DISPATCH_CANCELLED";

  // Phase 8 — deletion-request engine. Preview/generation/manual-routing
  // only; approval and confirmation reuse the Phase 6/7 events. Payloads
  // carry ids, methods, and types only — never content or secrets.
  public static final String DELETION_REQUEST_PREVIEWED = "DELETION_REQUEST_PREVIEWED";
  public static final String DELETION_REQUEST_GENERATED = "DELETION_REQUEST_GENERATED";
  public static final String DELETION_REQUEST_MANUAL_REVIEW_REQUIRED =
      "DELETION_REQUEST_MANUAL_REVIEW_REQUIRED";

  // Phase 9 — manual response intake. Payloads carry ids and the recorded
  // outcome only — never response text, bodies, tokens, or secrets.
  public static final String PRIVACY_REQUEST_RESPONSE_RECORDED =
      "PRIVACY_REQUEST_RESPONSE_RECORDED";
}
