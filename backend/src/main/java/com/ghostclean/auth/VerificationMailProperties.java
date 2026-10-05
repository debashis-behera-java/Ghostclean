package com.ghostclean.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Transactional verification-mail configuration (ADR-038).
 *
 * <p>Provider-agnostic: {@code provider} selects the
 * {@link VerificationEmailSender} implementation. Empty (default) preserves
 * the Phase 2 {@code silent}/{@code log} behavior with zero network calls.
 * Every value is overridable from the environment; the API key has no
 * default and the resend sender fails fast at startup when selected
 * without complete configuration.
 */
@Component
@ConfigurationProperties(prefix = "verification.mail")
public class VerificationMailProperties {

  /** Provider id: empty (default, disabled) or {@code resend}. */
  private String provider = "";

  /** Verified sender address, e.g. {@code verify@mail.ghostclean.com}. */
  private String from = "";

  /** Human-readable sender name shown beside the address. */
  private String fromName = "GhostClean";

  /** Provider API key, from the environment only. Never logged. */
  private String apiKey = "";

  /** Provider HTTPS API base URL (no trailing slash). */
  private String baseUrl = "https://api.resend.com";

  /** Gmail SMTP host (used only when provider=gmail). */
  private String smtpHost = "smtp.gmail.com";

  /** Gmail SMTP port (587 with STARTTLS). */
  private int smtpPort = 587;

  /** SMTP login (the Gmail address). From the environment only. Never logged. */
  private String smtpUsername = "";

  /** SMTP credential (Gmail App Password). From the environment only. Never logged. */
  private String smtpPassword = "";

  /**
   * Alternate SMTP login name (bare {@code EMAIL_USERNAME}). Compose always
   * defines every variable (empty when unset), so a YAML nested default can
   * never fall through — first-non-blank resolution happens in
   * {@link #effectiveSmtpUsername()} instead.
   */
  private String smtpUsernameFallback = "";

  /** Alternate SMTP credential name (bare {@code EMAIL_PASSWORD}). See above. */
  private String smtpPasswordFallback = "";

  /** Strict per-request read timeout in milliseconds (connect is 3s fixed). */
  private int timeoutMs = 8000;

  public boolean isResendSelected() {
    return "resend".equalsIgnoreCase(provider == null ? "" : provider.trim());
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getFromName() {
    return fromName;
  }

  public void setFromName(String fromName) {
    this.fromName = fromName;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getSmtpHost() {
    return smtpHost;
  }

  public void setSmtpHost(String smtpHost) {
    this.smtpHost = smtpHost;
  }

  public int getSmtpPort() {
    return smtpPort;
  }

  public void setSmtpPort(int smtpPort) {
    this.smtpPort = smtpPort;
  }

  public String getSmtpUsername() {
    return smtpUsername;
  }

  public void setSmtpUsername(String smtpUsername) {
    this.smtpUsername = smtpUsername;
  }

  public String getSmtpPassword() {
    return smtpPassword;
  }

  public void setSmtpPassword(String smtpPassword) {
    this.smtpPassword = smtpPassword;
  }

  public String getSmtpUsernameFallback() {
    return smtpUsernameFallback;
  }

  public void setSmtpUsernameFallback(String smtpUsernameFallback) {
    this.smtpUsernameFallback = smtpUsernameFallback;
  }

  public String getSmtpPasswordFallback() {
    return smtpPasswordFallback;
  }

  public void setSmtpPasswordFallback(String smtpPasswordFallback) {
    this.smtpPasswordFallback = smtpPasswordFallback;
  }

  /** First non-blank SMTP login across both supported variable names. */
  public String effectiveSmtpUsername() {
    return firstNonBlank(smtpUsername, smtpUsernameFallback);
  }

  /** First non-blank SMTP credential across both supported variable names. */
  public String effectiveSmtpPassword() {
    return firstNonBlank(smtpPassword, smtpPasswordFallback);
  }

  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
    }
    return "";
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int timeoutMs) {
    this.timeoutMs = timeoutMs;
  }
}
