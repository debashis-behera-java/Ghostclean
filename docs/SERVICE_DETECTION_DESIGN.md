# GhostClean — Service Detection Design (Rule Engine + Confidence)

> Rule-based first. No LLM in critical path. ML/LLM additive later as optional scorer behind feature flag.
> Phase 0 update (RESOLVED): manual-source (`source=manual`) evidence uses the same engine with a cap — a single manual item can never score HIGH; manual corroboration capped; manual rows always carry `matched_rules` + source label for explanation.

## 1. Signals (per normalized message)

- `sender_domain` exact / subdomain-of known service domain (+30 / +18)
- `sender_address` exact alias match (+20)
- `known_service` in directory (prior) (+10)
- Subject patterns (case-insensitive, multilingual-ready keys):
  - registration: `welcome|thanks for joining|confirm.*account|verify.*email|activate` (+25)
  - verification: `verification code|verify your|confirm your email` (+20)
  - security: `password reset|new sign-in|security alert` (+12, weaker alone)
  - commercial: `receipt|invoice|subscription|trial ending|order` (+10)
- Frequency: ≥3 msgs same service in 90d (+10); single security-only (−15 floor)
- Negative: `newsletter|unsubscribe|marketing|noreply-bulk` without reg-keywords (−20); mailing-list headers (−10)

Weights in `detection-rules.yml` (versioned), tunable without deploy (DB override table roadmap).

## 2. Confidence

`score = clamp(0..100, Σ weights)`. Bands: `0–39 LOW`, `40–69 MEDIUM`, `70–100 HIGH`. Persist per-evidence `confidence_contrib` + per-account max/avg + `matched_rules[]` for explanation. Example: `spotify.com` sender (+30) + known service (+10) + "Welcome to Spotify" (+25) = 65 → MEDIUM (→HIGH with 2nd verify email +10 frequency = 75).

Calibration: seed fixtures (welcome/verify/receipt vs newsletter/spam) with expected bands; CI asserts thresholds; quarterly review of false-positive reports adjusts weights via ADR.

## 3. Rule Representation

```yaml
ruleset: v3
rules:
  - id: SENDER_DOMAIN_EXACT
    weight: 30
    when: { field: sender_domain, op: in, ref: service.domains }
  - id: SUBJECT_REGISTRATION
    weight: 25
    when: { field: subject, op: regex_any, patterns: [$REGISTRATION_PATTERNS] }
```

Engine: pure function `score(NormalizedMsg, DirectoryHit) → {score, matchedRules[]}` — deterministic, no I/O, unit-testable. Directory lookup cached (Redis/Caffeine) by domain.

## 4. ML/LLM Extension (post-v1, non-breaking)

`CompositeScorer { ruleScore, mlScore?(nullable), final = ruleScore (+ mlDelta capped ±15) }`. LLM input: sanitized `{senderDomain, subjectTrunc, matchedRules}` only — never bodies/tokens; JSON-schema output `{label, delta, rationale}`; injection corpus must not flip LOW→HIGH or trigger actions. Flag `detection.ml.enabled=false` default; all decisions remain explainable via rules.

## 5. Outputs & Storage

Candidate → `service_evidence { matched_rules, signal_snapshot, confidence_contrib }`. No auto-account claim; resolution in `ACCOUNT_INTELLIGENCE_DESIGN.md`.

## 6. Acceptance

- [ ] Signals, weights, bands, example calc, config file, calibration tests, LLM-isolation specified.
