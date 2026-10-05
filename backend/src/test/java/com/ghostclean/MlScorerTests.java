package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.detection.DetectionEngine;
import com.ghostclean.detection.DirectoryHit;
import com.ghostclean.detection.ml.BedrockMlScorer;
import com.ghostclean.detection.ml.CompositeScore;
import com.ghostclean.detection.ml.CompositeScorer;
import com.ghostclean.detection.ml.DetectionMlProperties;
import com.ghostclean.detection.ml.MlScoringRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 13 test-first contracts for the additive ML scorer.
 *
 * <p>Bedrock is mocked with a JDK {@link HttpServer} (no new test
 * dependencies, no live AWS in the suite). Every test constructs the
 * planned production API ({@code detection.ml}): {@link DetectionMlProperties},
 * {@link BedrockMlScorer#score(MlScoringRequest)}, and
 * {@link CompositeScorer#score(DetectionEngine.NormalizedMessage, DirectoryHit)}.
 *
 * <p>Expected initial state: RED — none of the production types exist yet.
 */
class MlScorerTests {

  private HttpServer server;
  private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile Responder responder = Responder.ok("+8");

  private record CapturedRequest(String method, String path, String authHeader, String body) {
  }

  /** Canned Bedrock behavior per test. */
  private sealed interface Responder permits Responder.Ok, Responder.Status, Responder.Hang {
    record Ok(String content) implements Responder {
    }

    record Status(int code, String body) implements Responder {
    }

    record Hang(long millis, String content) implements Responder {
    }

    static Responder ok(String deltaFragment) {
      return new Ok("{\"choices\":[{\"message\":{\"content\":"
          + "\"{\\\"label\\\":\\\"likely-account\\\",\\\"delta\\\":" + deltaFragment
          + ",\\\"rationale\\\":\\\"test rationale\\\"}\"}}]}");
    }
  }

  @BeforeEach
  void startServer() throws IOException {
    requests.clear();
    responder = Responder.ok("+8");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(new CapturedRequest(
          exchange.getRequestMethod(),
          exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          body));
      Responder current = responder;
      int code = 200;
      byte[] payload;
      if (current instanceof Responder.Status status) {
        code = status.code();
        payload = status.body().getBytes(StandardCharsets.UTF_8);
      } else {
        String content = current instanceof Responder.Ok ok ? ok.content()
            : ((Responder.Hang) current).content();
        if (current instanceof Responder.Hang hang) {
          try {
            Thread.sleep(hang.millis());
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
        payload = content.getBytes(StandardCharsets.UTF_8);
      }
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private DetectionMlProperties props(boolean enabled) {
    DetectionMlProperties properties = new DetectionMlProperties();
    properties.setEnabled(enabled);
    properties.setBaseUrl(baseUrl());
    properties.setModel("qwen.qwen3-coder-480b-a35b-instruct");
    properties.setApiKey("test-bedrock-key-only");
    properties.setTimeoutMs(4000);
    return properties;
  }

  private DetectionEngine.NormalizedMessage lowSignal() {
    return new DetectionEngine.NormalizedMessage(
        "unknown-shop.example", "shop@unknown-shop.example", "Your order update",
        System.currentTimeMillis(), false);
  }

  // -- R1: feature flag -------------------------------------------------------

  @Test
  void mlIsDisabledByDefault() {
    assertFalse(new DetectionMlProperties().isEnabled(),
        "detection.ml.enabled must default to false");
  }

  @Test
  void disabledScorerMakesNoHttpCall() {
    CompositeScorer composite =
        new CompositeScorer(new DetectionEngine(), new BedrockMlScorer(props(false)), props(false));
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, requests.size(), "no Bedrock call is allowed when the flag is off");
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void disabledResultIsIdenticalToRuleEngine() {
    DetectionEngine engine = new DetectionEngine();
    CompositeScorer composite =
        new CompositeScorer(engine, new BedrockMlScorer(props(false)), props(false));
    DetectionEngine.NormalizedMessage message = lowSignal();
    DetectionEngine.ScoredEvidence expected = engine.score(message, null);
    CompositeScore actual = composite.score(message, null);
    assertEquals(expected.score(), actual.ruleScore());
    assertEquals(expected.score(), actual.finalScore());
    assertEquals(expected.band(), actual.band());
    assertEquals(expected.matchedRules(), actual.matchedRules());
  }

  // -- R2/R4: bounded additive scoring ----------------------------------------

  @Test
  void validResponseAppliesBoundedDelta() {
    responder = Responder.ok("+8");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    // Rule score for the low fixture is 10; +8 stays LOW at 18.
    assertEquals(10, result.ruleScore());
    assertEquals(8, result.mlDelta());
    assertEquals(18, result.finalScore());
    assertEquals(DetectionEngine.ConfidenceBand.LOW, result.band());
    assertEquals(1, requests.size());
  }

  @Test
  void deltaPlus15IsAccepted() {
    responder = Responder.ok("+15");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(15, result.mlDelta());
    assertEquals(25, result.finalScore());
  }

  @Test
  void deltaMinus15IsAccepted() {
    responder = Responder.ok("-15");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(-15, result.mlDelta());
    assertEquals(0, result.finalScore(), "final score clamps at zero, never negative");
  }

  @Test
  void deltaAbove15FallsBackToRuleScore() {
    responder = Responder.ok("+16");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta(), "out-of-range delta must be rejected, not clamped in");
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void deltaBelowMinus15FallsBackToRuleScore() {
    responder = Responder.ok("-100");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  // -- R4: failure fallback ----------------------------------------------------

  @Test
  void malformedJsonFallsBackToRuleScore() {
    responder = new Responder.Ok("{\"choices\":[{\"message\":{\"content\":\"not json at all\"}}]}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void missingDeltaFieldFallsBackToRuleScore() {
    responder = new Responder.Ok(
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"label\\\":\\\"x\\\"}\"}}]}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void nonNumericDeltaFallsBackToRuleScore() {
    responder = Responder.ok("\"lots\"");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void http500FallsBackToRuleScore() {
    responder = new Responder.Status(500, "{\"error\":\"backend failure\"}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void http401FallsBackToRuleScore() {
    responder = new Responder.Status(401, "{\"error\":\"unauthorized\"}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void expiredSignature401FallsBackToRuleScore() {
    // Production incident shape (2026-10-01): the short-term Bedrock key
    // carries an embedded signature expiry and the Mantle proxy answers
    // 401 invalid_api_key/permission_denied_error. Detection must stand on
    // the rule score; the scan must not fail.
    responder = new Responder.Status(401,
        "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"Signature expired\","
            + "\"param\":null,\"type\":\"permission_denied_error\"}}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
    assertEquals(1, requests.size(), "exactly one attempt, no retries on auth failure");
  }

  @Test
  void http403FallsBackToRuleScore() {
    responder = new Responder.Status(403, "{\"error\":{\"type\":\"permission_denied_error\"}}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
    assertEquals(1, requests.size(), "exactly one attempt, no retries on auth failure");
  }

  @Test
  void emptyChoicesFallsBackToRuleScore() {
    responder = new Responder.Ok("{\"choices\":[]}");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta());
    assertEquals(result.ruleScore(), result.finalScore());
  }

  @Test
  void timeoutFallsBackToRuleScore() throws Exception {
    DetectionMlProperties properties = props(true);
    properties.setTimeoutMs(300);
    String validContent = cannedValidContent();
    responder = new Responder.Hang(5000, validContent);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(0, result.mlDelta(), "a hung Bedrock must never hang the scan");
    assertEquals(result.ruleScore(), result.finalScore());
  }

  // -- R3: sanitized input contract --------------------------------------------

  @Test
  void requestSendsOnlySanitizedFields() {
    responder = Responder.ok("+5");
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    composite.score(lowSignal(), null);

    assertEquals(1, requests.size());
    CapturedRequest request = requests.get(0);
    assertEquals("POST", request.method());
    assertEquals("/chat/completions", request.path());
    assertEquals("Bearer test-bedrock-key-only", request.authHeader());
    assertTrue(request.body().contains("unknown-shop.example"), "domain must be sent");
    assertTrue(request.body().contains("Your order update"), "truncated subject must be sent");
    assertTrue(request.body().contains("qwen.qwen3-coder-480b-a35b-instruct"),
        "configured model must be sent");
    // Secrets that must never appear in the AI request body.
    assertFalse(request.body().contains("acc-"), "no OAuth access token material");
    assertFalse(request.body().contains("ref-"), "no OAuth refresh token material");
    assertFalse(request.body().contains("BEGIN"), "no key material");
    assertFalse(request.body().toLowerCase().contains("password"), "no credentials");
    assertFalse(request.body().toLowerCase().contains("authorization"), "no auth headers");
  }

  @Test
  void apiKeyNeverAppearsInRequestBody() {
    responder = Responder.ok("+5");
    DetectionMlProperties properties = props(true);
    new CompositeScorer(new DetectionEngine(), new BedrockMlScorer(properties), properties)
        .score(lowSignal(), null);
    assertEquals(1, requests.size());
    assertFalse(requests.get(0).body().contains("test-bedrock-key-only"),
        "API key travels in the Authorization header only, never the body");
  }

  @Test
  void scorerBearsNoActionCapabilityMarker() {
    // Structural companion to MlArchitectureTests: the scorer request shape
    // carries evidence only — this test pins the wire contract while the
    // ArchUnit test pins the dependency direction.
    responder = Responder.ok("+5");
    DetectionMlProperties properties = props(true);
    new CompositeScorer(new DetectionEngine(), new BedrockMlScorer(properties), properties)
        .score(lowSignal(), null);
    String body = requests.get(0).body().toLowerCase();
    assertFalse(body.contains("delete"), "scorer must never request deletion");
    assertFalse(body.contains("dispatch"), "scorer must never request dispatch");
  }

  // -- R6: rules remain the source of truth ------------------------------------

  @Test
  void aiCannotOverrideRules() {
    responder = Responder.ok("+15");
    DetectionEngine engine = new DetectionEngine();
    DetectionMlProperties properties = props(true);
    CompositeScorer composite =
        new CompositeScorer(engine, new BedrockMlScorer(properties), properties);
    DetectionEngine.NormalizedMessage message = lowSignal();
    CompositeScore result = composite.score(message, null);
    assertEquals(engine.score(message, null).matchedRules(), result.matchedRules(),
        "explanation still comes from deterministic rules only");
    assertEquals(DetectionEngine.ConfidenceBand.LOW, result.band(),
        "LOW + max delta must stay LOW");
  }

  @Test
  void scorerSurfacesOnlyBoundedEvidence() {
    responder = new Responder.Ok("not-json");
    // The responder above is malformed on purpose; the scorer must treat it
    // as a failure and still report the rule score.
    DetectionMlProperties properties = props(true);
    CompositeScorer composite = new CompositeScorer(
        new DetectionEngine(), new BedrockMlScorer(properties), properties);
    CompositeScore result = composite.score(lowSignal(), null);
    assertEquals(result.ruleScore(), result.finalScore());
    assertEquals(1, requests.size());
  }

  private static String cannedValidContent() {
    return Responder.ok("+8") instanceof Responder.Ok ok ? ok.content() : "{}";
  }
}
