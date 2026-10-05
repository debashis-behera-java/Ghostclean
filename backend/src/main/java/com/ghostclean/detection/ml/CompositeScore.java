package com.ghostclean.detection.ml;

import com.ghostclean.detection.DetectionEngine;
import java.util.List;

/**
 * Combined rule + additive-AI scoring outcome (Phase 13, R2/R6).
 *
 * <p>{@code ruleScore} is authoritative and always present;
 * {@code finalScore} equals the rule score plus the bounded AI delta
 * ({@code mlDelta}, zero when AI is disabled or failed), clamped to 0–100.
 * The band and explanation ({@code matchedRules}) derive from deterministic
 * rules only; the AI label/rationale are supplementary evidence.
 */
public record CompositeScore(
    int ruleScore,
    int mlDelta,
    int finalScore,
    DetectionEngine.ConfidenceBand band,
    List<String> matchedRules,
    String mlLabel,
    String mlRationale) {
}
