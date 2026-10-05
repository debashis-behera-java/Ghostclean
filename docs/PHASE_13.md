# GhostClean — PHASE_13 (Additive ML/AI Scorer via AWS Bedrock)

> Status: IMPLEMENTED behind a default-OFF feature flag. The deterministic rule
> engine remains the source of truth; Bedrock supplies an optional additive
> score capped at ±15. Local-only posture (ADR-036) otherwise unchanged; Bedrock
> is used ONLY for this scoring request (ADR-037). No AWS production deployment
> is claimed. No privacy approval is claimed. No automatic deletion exists.

## 1. Objective

Add an AI scorer that supplements — never overrides — the Phase 5 rule engine,
per `SERVICE_DETECTION_DESIGN.md` §4 and ADR-005, with prompt-injection
protection per `THREAT_MODEL.md` #12.

## 2. Architecture

- New isolated package `com.ghostclean.detection.ml` (same ArchUnit slice as
  `detection`; ArchUnit-pinned to never depend on OAuth/deletion/dispatch/
  account/repository/scheduler types):
  - `DetectionMlProperties` — `detection.ml.*` configuration (default OFF).
  - `MlScoringRequest` — closed sanitized contract
    `{senderDomain, subjectTruncated, matchedRules}`.
  - `BedrockMlScorer` — single-shot OpenAI-compatible POST to
    `{base-url}/chat/completions` via the existing Spring `RestClient`
    (no new dependencies); strict timeout; one attempt; empty on any failure.
  - `CompositeScorer` — `final = clamp(0..100, ruleScore + mlDelta)`;
    band and explanation derive from rules only.
  - `CompositeScore` — `{ruleScore, mlDelta, finalScore, band, matchedRules,
    mlLabel, mlRationale}`.
- Integration point: `ScanService.processOne` scores via `CompositeScorer`.
  With the flag OFF the outcome is exactly the `DetectionEngine` result with
  zero Bedrock calls. `confidence_contrib` stores the final score;
  `matched_rules` is unchanged; `signal_snapshot` gains `ruleScore`/`mlDelta`
  keys (jsonb — no migration, no schema change).
- The scorer is not in any deletion/send/dispatch/schedule path, holds no
  such dependency, and cannot invoke tools, endpoints, code, or commands.

## 3. Feature flag and configuration (`application.yml`)

```yaml
detection:
  ml:
    enabled: ${DETECTION_ML_ENABLED:false}
    base-url: ${BEDROCK_BASE_URL:https://bedrock-mantle.us-east-1.api.aws/v1}
    model: ${BEDROCK_MODEL:qwen.qwen3-coder-480b-a35b-instruct}
    api-key: ${BEDROCK_API_KEY:}
    timeout-ms: ${BEDROCK_TIMEOUT_MS:8000}
```

Environment template (`.env.example`, placeholders only):

```text
DETECTION_ML_ENABLED=false
BEDROCK_BASE_URL=https://bedrock-mantle.us-east-1.api.aws/v1
BEDROCK_MODEL=qwen.qwen3-coder-480b-a35b-instruct
BEDROCK_API_KEY=REPLACE_ME_with_short_term_bedrock_key
BEDROCK_TIMEOUT_MS=8000
```

## 4. Sanitized input boundary (R3)

The AI receives ONLY `senderDomain`, truncated `subject` (200 chars, as
persisted), and deterministic `matchedRules`. Never: email bodies, OAuth
access/refresh tokens, API keys, credentials, authorization headers, account
identifiers, or database material. The contract is a closed record — widening
it requires code + test changes.

## 5. Output contract and ±15 enforcement (R4/R2)

Expected wire content: `{"label": "...", "delta": number, "rationale": "..."}`
inside the Chat Completions `choices[0].message.content` string. Accepted only
when JSON parses, `delta` is numeric and integral, and `-15 <= delta <= +15`
(a leading `+` is tolerated and re-validated strictly). Everything else —
timeout, HTTP 4xx/5xx, empty/malformed content, missing/non-numeric/fractional/
out-of-range delta — falls back to the rule score. The AI label never drives
the confidence band; rationale is truncated to 500 chars, stored as inert
supplementary text, never logged or executed.

## 6. Prompt-injection protection (R5)

The system prompt grants no capability (scorer identity, no operations,
evidence fields are untrusted data, JSON-only reply) and the user content is
pure data. Guarantees, covered by a 15-prompt adversarial corpus
(`MlPromptInjectionTests`): hostile subjects cannot change the boundary,
trigger deletion/dispatch, override rules, or flip LOW to HIGH (rule-LOW ≤ 39
plus max +15 = 54 < 70 HIGH — mathematically impossible).

## 7. Failure and logging behavior

Single attempt, connect 3s / read `timeout-ms` (default 8s), no retries, no
workers, no schedulers. Logs carry only: enabled flag, model id, latency,
outcome category, bounded delta, rule ids. Never: keys, tokens, prompts,
subjects, bodies, headers.

## 8. Testing

- `MlScorerTests` (20): flag default OFF, no-call-when-OFF, rule identity,
  valid/boundary deltas, out-of-range/malformed/timeout/4xx/5xx/empty
  fallback, sanitized-input wire assertions, rule supremacy. Bedrock mocked
  with JDK `HttpServer`; no live AWS in the suite.
- `MlPromptInjectionTests` (45): 15 hostile subjects × 3 properties.
- `MlArchitectureTests` (3): ArchUnit isolation rules.
- Full regression: `mvn clean test` — **512 tests, 0 failures, 0 errors,
  0 skipped, BUILD SUCCESS** (444 baseline + 68 Phase 13), verified
  2026-09-28, log `backend/phase13-verify.log`.

## 9. Live Bedrock verification (manual, never CI)

Prerequisites: `BEDROCK_API_KEY` set in the local shell only (short-term key;
never committed, never printed). With the app running locally and
`DETECTION_ML_ENABLED=true`, trigger a scan and confirm in logs the
`ML scorer applied` entry with a bounded delta — or verify the endpoint
directly with the previously approved standalone check (expects
`GHOSTCLEAN BEDROCK OK`). Record the dated verification output with the
release checklist; do not paste keys into any artifact.

## 10. Known limitations

- AI scoring quality is unevaluated (no calibration review yet); rules remain
  authoritative by design.
- `signal_snapshot` carries `ruleScore`/`mlDelta` but no distinct
  enabled-vs-failed marker (fallback reasons live in logs only).
- No streaming, batching, or caching of scores; every evidence item scores
  synchronously inside the existing bounded scan budgets.
- Live Bedrock verification is manual and per-environment; CI proves
  fallback/boundary behavior with mocks only.
