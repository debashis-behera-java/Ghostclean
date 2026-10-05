package com.ghostclean.detection.ml;

import java.util.List;

/**
 * Closed sanitized input contract for the AI scorer (Phase 13, R3).
 *
 * <p>Exactly three evidence fields travel to Bedrock — sender domain,
 * truncated subject, and deterministic matched-rule ids. Email bodies, OAuth
 * tokens, credentials, and all other secrets are structurally inexpressible
 * here: widening this contract requires changing this record and its tests.
 * Every field is untrusted DATA, never instructions.
 */
public record MlScoringRequest(
    String senderDomain,
    String subjectTruncated,
    List<String> matchedRules) {
}
