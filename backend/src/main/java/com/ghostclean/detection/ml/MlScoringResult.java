package com.ghostclean.detection.ml;

/**
 * Validated AI scorer output (Phase 13, R4).
 *
 * <p>Instances exist only after {@code BedrockMlScorer} has verified the
 * wire response: JSON shape, numeric integral {@code delta} within
 * [-15, +15], and a truncated rationale. The label is supplementary evidence
 * only — bands are always derived from the composite score, never from it.
 * The rationale is inert text: stored, never executed, never logged raw.
 */
public record MlScoringResult(
    String label,
    int delta,
    String rationale) {
}
