package com.ghostclean.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resend-backed account-mail sender (ADR-038).
 *
 * <p>Active only when {@code verification.mail.provider=resend}; otherwise
 * the Phase 2 {@code silent}/{@code log} sender remains the sole bean and
 * behavior is unchanged. Sends GhostClean account mail (verification and
 * password-reset links) through the Resend HTTPS API with the key from the
 * environment only.
 *
 * <p>Single attempt, strict timeout, no retries: the resend/forget
 * endpoints are the retry paths. Any failure throws
 * {@link VerificationMailException} carrying only a coarse category;
 * callers keep the anti-enumeration contract.
 *
 * <p>Logging discipline: outcome category only. Never the API key, the
 * recipient address, the account link, or provider response bodies.
 * Carries account-recovery links only — never deletion-request
 * content (V1 stays manual-draft-only).
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "verification.mail", name = "provider", havingValue = "resend")
public class ResendVerificationEmailSender implements VerificationEmailSender {

  private static final Logger log = LoggerFactory.getLogger(ResendVerificationEmailSender.class);

  private final VerificationMailProperties properties;
  private final RestClient http;

  @Autowired
  public ResendVerificationEmailSender(VerificationMailProperties properties) {
    this(properties, defaultClient(properties));
  }

  ResendVerificationEmailSender(VerificationMailProperties properties, RestClient http) {
    this.properties = properties;
    this.http = http;
  }

  private static RestClient defaultClient(VerificationMailProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())));
    return RestClient.builder().requestFactory(factory).build();
  }

  /** Fail fast on incomplete provider configuration (never at first signup). */
  @PostConstruct
  public void requireCompleteConfiguration() {
    if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
      throw new IllegalStateException(
          "verification.mail.api-key is required when verification.mail.provider=resend");
    }
    if (properties.getFrom() == null || properties.getFrom().isBlank()) {
      throw new IllegalStateException(
          "verification.mail.from is required when verification.mail.provider=resend");
    }
    log.info("Verification mail provider=resend configured");
  }

  @Override
  public void sendVerificationEmail(String toEmail, String verificationLink) {
    VerificationEmailContent.EmailBody body = VerificationEmailContent.build(verificationLink);
    postAccountEmail(toEmail, body.subject(), body.html(), body.text(), "verification");
  }

  @Override
  public void sendPasswordResetEmail(String toEmail, String resetLink) {
    PasswordResetEmailContent.EmailBody body = PasswordResetEmailContent.build(resetLink);
    postAccountEmail(toEmail, body.subject(), body.html(), body.text(), "password-reset");
  }

  @Override
  public void sendAdminNotification(String toEmail, String subject, String textBody) {
    String safeHtml = "<pre>" + VerificationEmailContent.escapeHtml(textBody) + "</pre>";
    postAccountEmail(toEmail, subject, safeHtml, textBody, "admin-notice");
  }

  private void postAccountEmail(
      String toEmail, String subject, String html, String text, String kind) {
    long started = System.currentTimeMillis();
    try {
      JsonNode response = http.post()
          .uri(properties.getBaseUrl() + "/emails")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(Map.of(
              "from", sender(),
              "to", List.of(toEmail),
              "subject", subject,
              "html", html,
              "text", text))
          .retrieve()
          .body(JsonNode.class);
      String id = response != null && response.hasNonNull("id") ? response.get("id").asText() : "";
      if (id.isBlank()) {
        log.warn("Account email delivery failed kind={} reason=malformed latencyMs={}",
            kind, System.currentTimeMillis() - started);
        throw new VerificationMailException("http-error");
      }
      log.info("Account email delivery succeeded kind={} latencyMs={}",
          kind, System.currentTimeMillis() - started);
    } catch (VerificationMailException ex) {
      throw ex;
    } catch (RestClientException ex) {
      String category = classify(ex);
      // Diagnostic fields only: numeric status plus the provider's own
      // error name and a sanitized message. Never the key, recipient,
      // link, bodies, or raw provider payload (see safeProviderError).
      log.warn("Account email delivery failed kind={} reason={} httpStatus={} providerError={} latencyMs={}",
          kind, category, httpStatusOf(ex), safeProviderError(ex),
          System.currentTimeMillis() - started);
      throw new VerificationMailException(category);
    } catch (RuntimeException ex) {
      log.warn("Account email delivery failed kind={} reason=error latencyMs={}",
          kind, System.currentTimeMillis() - started);
      throw new VerificationMailException("transport");
    }
  }

  private String sender() {
    String name = properties.getFromName() == null ? "" : properties.getFromName().trim();
    if (name.isEmpty()) {
      return properties.getFrom();
    }
    return name + " <" + properties.getFrom() + ">";
  }

  /** Outcome category for safe logging — never leaks response content. */
  private static String classify(RestClientException ex) {
    String name = ex.getClass().getSimpleName().toLowerCase();
    if (name.contains("timeout") || name.contains("resourceaccess")) {
      return "timeout";
    }
    if (name.contains("unauthorized") || name.contains("forbidden")) {
      return "http-auth";
    }
    if (name.contains("status")) {
      return "http-error";
    }
    return "transport";
  }

  private static final ObjectMapper DIAGNOSTIC_JSON = new ObjectMapper();
  private static final Pattern EMAIL_LIKE =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final int MAX_DIAGNOSTIC_CHARS = 200;

  /**
   * Numeric provider HTTP status for diagnostics; -1 when the failure has
   * no HTTP status (DNS/TLS/connect).
   */
  public static int httpStatusOf(RestClientException ex) {
    if (ex instanceof HttpStatusCodeException statusEx) {
      return statusEx.getStatusCode().value();
    }
    return -1;
  }

  /**
   * Sanitized provider error for diagnostics: the provider's own error
   * {@code name} plus a redacted {@code message} (email-like values
   * masked, length-bounded). Empty when the body carries nothing usable.
   * Never returns raw bodies, keys, recipients, links, or mail content.
   */
  public static String safeProviderError(RestClientException ex) {
    if (!(ex instanceof HttpStatusCodeException statusEx)) {
      return "";
    }
    String body = statusEx.getResponseBodyAsString();
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      JsonNode node = DIAGNOSTIC_JSON.readTree(body);
      String name = node.has("name") ? node.get("name").asText("") : "";
      String message = node.has("message") ? node.get("message").asText("") : "";
      String safe = EMAIL_LIKE.matcher(message).replaceAll("[redacted-email]");
      if (safe.length() > MAX_DIAGNOSTIC_CHARS) {
        safe = safe.substring(0, MAX_DIAGNOSTIC_CHARS);
      }
      return (name + (safe.isEmpty() ? "" : ": " + safe)).trim();
    } catch (Exception ignored) {
      return "";
    }
  }
}
