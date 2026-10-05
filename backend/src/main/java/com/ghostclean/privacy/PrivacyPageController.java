package com.ghostclean.privacy;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 privacy page (human decision 7).
 *
 * <p>Smallest correct publication mechanism until the real frontend
 * exists: the approved content below is drawn exclusively from
 * {@code docs/PRIVACY_MODEL.md} (§2 classification, §4 retention,
 * §5 rights) and the implemented behavior. It makes no certification,
 * guarantee, or legal-advice claims. Content/version/date are updated
 * here when the privacy model changes; the full frontend page replaces
 * this mechanism once the React app lands.
 */
@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyPageController {

  /** Content version; bump with any wording change. */
  public static final String CONTENT_VERSION = "2026-09-19/1";

  /** Approved publication path for this mechanism. */
  public static final String PUBLISHED_PATH = "/api/v1/privacy";

  @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> privacy() {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body("""
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>GhostClean — Privacy</title></head>
            <body>
            <h1>GhostClean Privacy Notice</h1>
            <p>Version %s. GhostClean is a privacy engineering platform, not a law firm \
            and not a legal service. Deletion outcomes depend on the receiving \
            organization and applicable exceptions.</p>
            <h2>What GhostClean stores</h2>
            <ul>
            <li>Login email and password hash, plus email-verification state.</li>
            <li>Encrypted OAuth tokens and provider account identifiers for \
            mailboxes you explicitly connect, with the granted scopes and timestamps.</li>
            <li>Email metadata only (sender domain, truncated subject, date, \
            provider message reference) for candidate detection. \
            GhostClean never stores email bodies or attachments.</li>
            <li>Detection outputs (confidence, risk, first/last seen), deletion \
            request drafts and lifecycle events, and security audit events.</li>
            <li>Settings such as retention windows and reminder preferences.</li>
            </ul>
            <h2>What GhostClean does not collect by default</h2>
            <p>Email bodies, attachments, contacts, non-registration mail \
            content, and precise geolocation.</p>
            <h2>Retention</h2>
            <ul>
            <li>Message metadata: 180 days by default (configurable 30–365 days), \
            then automatically purged.</li>
            <li>Scan run records: 90 days. Revoked connection stubs: 30 days.</li>
            <li>Completed deletion requests: 2 years by default for proof, then anonymized.</li>
            <li>Audit events: rolling 1 year, anonymized on account deletion.</li>
            <li>Operational logs: 30 days, redacted; tokens and bodies never logged.</li>
            </ul>
            <h2>Deletion</h2>
            <p>You can disconnect a provider (tokens wiped immediately), purge \
            evidence per your retention setting, and delete your GhostClean \
            account, which cascade-deletes your data and anonymizes audit \
            records. Account deletion requires confirmation and re-authentication.</p>
            <h2>Data residency</h2>
            <p>Production data is hosted in the EU region. Local and development \
            environments never hold production data.</p>
            </body>
            </html>
            """.formatted(CONTENT_VERSION));
  }
}
