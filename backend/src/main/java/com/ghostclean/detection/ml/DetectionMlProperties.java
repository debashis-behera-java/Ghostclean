package com.ghostclean.detection.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 13 additive ML scorer configuration (ADR-037).
 *
 * <p>Disabled by default: {@code detection.ml.enabled=false} preserves the
 * deterministic rule-engine behavior exactly, with no Bedrock network call.
 * The Bedrock API key comes ONLY from the {@code BEDROCK_API_KEY} environment
 * variable — never hard-coded, never committed, never logged.
 */
@Component
@ConfigurationProperties(prefix = "detection.ml")
public class DetectionMlProperties {

  /** Master switch. Default OFF; rules remain the source of truth. */
  private boolean enabled = false;

  /** Bedrock Mantle OpenAI-compatible base URL (no trailing slash). */
  private String baseUrl = "https://bedrock-mantle.us-east-1.api.aws/v1";

  /** Scoring model identifier. */
  private String model = "qwen.qwen3-coder-480b-a35b-instruct";

  /** Short-term Bedrock API key, from the environment only. */
  private String apiKey = "";

  /** Strict per-request read timeout in milliseconds (connect is 3s fixed). */
  private int timeoutMs = 8000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int timeoutMs) {
    this.timeoutMs = timeoutMs;
  }
}
