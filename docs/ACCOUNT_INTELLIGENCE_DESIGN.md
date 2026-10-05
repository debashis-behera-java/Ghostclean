# GhostClean — Account Intelligence Design (Entity Resolution)

## 1. Goal

Many evidence rows → one `discovered_services` row per (user, service). Never assert "Active account"; display **"Detected account evidence"** + first/last seen + count + confidence + risk. Rows with `source=manual` display an additional "Manual/degraded — not mailbox-verified" badge and inherit the detection confidence cap.

Example: 3 Spotify mails (welcome/verify/subscription) → one card `Spotify · Confidence HIGH · Evidence 3 · First 2023-04-01 · Last 2026-02-11`.

## 2. Grouping Key

1. Directory match on `sender_domain ∈ service.domains/aliases` → `service_directory_id`.
2. Else normalized key: `eTLD+1(sender_domain)` stripped of `mail./noreply.` prefixes → `service_key` (e.g. `spotify`); alias table maps variants (`spotify.com`, `email.spotify.com` → one).
3. Fuzzy fallback (same eTLD+1 + subject service-name mention) flagged `needs_review`, never auto-merged across distinct eTLD+1.

Upsert: `ON CONFLICT (user_id, service_directory_id|service_key) DO UPDATE evidence_count+1, last_seen=greatest, confidence=greatest blend, first_seen=least`.

## 3. Evidence Record

`service_evidence { sender_domain, subject_trunc, received_at, matched_rules, confidence_contrib }`. Dedupe on `(user,provider,messageId)`; re-scan never duplicates. Evidence list paged, sanitized, sortable by date.

## 4. Confidence Aggregation

Account confidence = `max(evidence scores)` boosted +5 per corroborating distinct pattern (cap +15), capped 100; band recomputed. Show breakdown: top-3 contributing evidence + rule hits. Downgrade path: user dismiss → `dismissed` (excluded from counts, restorable); contradictory (unsubscribe-only, no reg keywords) caps at MEDIUM with note.

## 5. Status & Timestamps

`status: discovered → review_required (auto on HIGH or ≥2 evidence) → approved/requested/completed | dismissed`. `first_seen_at` (min evidence date), `last_seen_at` (max), `evidence_count`. Staleness note if `last_seen > 12m ago`: "No recent evidence — account may be inactive or mail filtered. Not proof of closure."

## 6. Risk Hook

On upsert, `risk` module recomputes score/band (see `DELETION_WORKFLOW.md` risk appendix + `PRODUCT_REQUIREMENTS.md`); factors displayed, no breach claims.

## 7. Acceptance

- [ ] Grouping keys, dedupe, upsert, confidence rollup, status, timestamps, language guard specified + tested with Spotify-3-mail fixture.
