package com.ghostclean.detection.ml;

import com.ghostclean.detection.DetectionEngine;
import com.ghostclean.detection.DirectoryHit;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Rule-authoritative composite scorer (Phase 13, R1/R2/R6).
 *
 * <p>The deterministic {@link DetectionEngine} is always consulted first and
 * its score, band, and matched rules are preserved verbatim in the outcome.
 * The AI contributes at most ±15 and only when
 * {@code detection.ml.enabled=true}; when disabled — or when the AI fails,
 * times out, or answers out of contract — the final score IS the rule score
 * and no Bedrock network call is made (disabled) or retained (failed).
 * Never in any deletion, dispatch, or send path.
 */
@Component
public class CompositeScorer {

  private final DetectionEngine engine;
  private final BedrockMlScorer mlScorer;
  private final DetectionMlProperties properties;

  public CompositeScorer(DetectionEngine engine, BedrockMlScorer mlScorer,
      DetectionMlProperties properties) {
    this.engine = engine;
    this.mlScorer = mlScorer;
    this.properties = properties;
  }

  /** Scores one normalized message: rules first, bounded AI delta second. */
  public CompositeScore score(DetectionEngine.NormalizedMessage message, DirectoryHit hit) {
    DetectionEngine.ScoredEvidence rule = engine.score(message, hit);
    if (!properties.isEnabled()) {
      return new CompositeScore(rule.score(), 0, rule.score(), rule.band(),
          rule.matchedRules(), null, null);
    }
    MlScoringRequest request = new MlScoringRequest(
        message.senderDomain(), message.subject(), rule.matchedRules());
    Optional<MlScoringResult> ml = mlScorer.score(request);
    if (ml.isEmpty()) {
      return new CompositeScore(rule.score(), 0, rule.score(), rule.band(),
          rule.matchedRules(), null, null);
    }
    int finalScore = Math.max(0, Math.min(100, rule.score() + ml.get().delta()));
    return new CompositeScore(rule.score(), ml.get().delta(), finalScore,
        DetectionEngine.ConfidenceBand.of(finalScore),
        rule.matchedRules(), ml.get().label(), ml.get().rationale());
  }
}
