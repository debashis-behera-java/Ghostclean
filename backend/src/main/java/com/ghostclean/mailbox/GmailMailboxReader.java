package com.ghostclean.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Gmail read-path adapter. Calls exactly two read endpoints — message
 * listing and per-message metadata (From/Subject/List-Unsubscribe headers
 * only). No other Gmail surface is referenced anywhere in this class, so a
 * write call cannot be added without tripping the build-time capability
 * scan. Only ids, statuses, and provider reason codes are logged.
 */
@Component
@Profile("!gmail-fake")
public class GmailMailboxReader implements MailboxReader {

  private static final Logger log = LoggerFactory.getLogger(GmailMailboxReader.class);
  private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

  private final RestClient http;

  public GmailMailboxReader() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String providerId() {
    return "google";
  }

  @Override
  public MessagePage listMessages(String accessToken, String query,
      String pageToken, int maxResults) {
    try {
      JsonNode body = http.get()
          .uri(listUri(query, pageToken, maxResults))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      List<String> ids = new ArrayList<>();
      JsonNode messages = body != null ? body.get("messages") : null;
      if (messages != null && messages.isArray()) {
        for (JsonNode message : messages) {
          if (message.hasNonNull("id")) {
            ids.add(message.get("id").asText());
          }
        }
      }
      String next = body != null && body.hasNonNull("nextPageToken")
          ? body.get("nextPageToken").asText() : null;
      int estimate = body != null && body.has("resultSizeEstimate")
          ? body.get("resultSizeEstimate").asInt(0) : 0;
      return new MessagePage(List.copyOf(ids), next, estimate);
    } catch (RestClientException ex) {
      throw classify(ex, "list");
    }
  }

  /**
   * List-endpoint URI. The search query travels as a template variable so
   * {@code encode()} percent-encodes provider-query metacharacters
   * ({@code {}"'()} and spaces) instead of leaving them raw: handing the
   * raw query to {@code queryParam} leaves those characters unencoded and
   * the subsequent {@code toUri()} throws IllegalStateException, failing
   * the scan before any Gmail call. Public so the encoding is directly
   * unit-testable.
   */
  public static URI listUri(String query, String pageToken, int maxResults) {
    UriComponentsBuilder builder = UriComponentsBuilder
        .fromUriString(BASE + "/messages")
        .queryParam("q", "{q}")
        .queryParam("maxResults", Math.max(1, Math.min(500, maxResults)))
        .queryParam("includeSpamTrash", false);
    Object[] variables;
    if (pageToken != null && !pageToken.isBlank()) {
      builder.queryParam("pageToken", "{cursor}");
      variables = new Object[]{query, pageToken};
    } else {
      variables = new Object[]{query};
    }
    return builder.buildAndExpand(variables).encode().toUri();
  }

  @Override
  public MessageMetadata getMessage(String accessToken, String messageId) {
    try {
      String uri = UriComponentsBuilder
          .fromUriString(BASE + "/messages/{id}")
          .queryParam("format", "metadata")
          .queryParam("metadataHeaders", "From")
          .queryParam("metadataHeaders", "Subject")
          .queryParam("metadataHeaders", "List-Unsubscribe")
          .buildAndExpand(messageId)
          .encode()
          .toUri()
          .toString();
      JsonNode body = http.get()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      if (body == null || !body.hasNonNull("id")) {
        throw GmailApiException.notFound();
      }
      long internalDate = body.has("internalDate")
          ? parseLong(body.get("internalDate").asText(), 0L) : 0L;
      String from = "";
      String subject = "";
      boolean listUnsubscribe = false;
      JsonNode payload = body.get("payload");
      JsonNode headers = payload != null ? payload.get("headers") : null;
      if (headers != null && headers.isArray()) {
        for (JsonNode header : headers) {
          String name = header.hasNonNull("name") ? header.get("name").asText() : "";
          String value = header.hasNonNull("value") ? header.get("value").asText() : "";
          if (name.equalsIgnoreCase("From")) {
            from = value;
          } else if (name.equalsIgnoreCase("Subject")) {
            subject = value;
          } else if (name.equalsIgnoreCase("List-Unsubscribe")) {
            listUnsubscribe = !value.isBlank();
          }
        }
      }
      return new MessageMetadata(body.get("id").asText(), internalDate, from, subject,
          listUnsubscribe);
    } catch (GmailApiException ex) {
      throw ex;
    } catch (RestClientException ex) {
      throw classify(ex, "get");
    }
  }

  private GmailApiException classify(RestClientException ex, String operation) {
    if (ex instanceof HttpStatusCodeException http) {
      int status = http.getStatusCode().value();
      String reason = providerReason(http);
      log.warn("Gmail {} failed: status={} reason={}", operation, status, reason);
      if (status == 401) {
        return GmailApiException.unauthenticated();
      }
      if (status == 404) {
        return GmailApiException.notFound();
      }
      if (status == 429) {
        return GmailApiException.rateLimited(retryAfterSeconds(http));
      }
      if (status == 403) {
        if (isQuotaReason(reason)) {
          return GmailApiException.rateLimited(retryAfterSeconds(http));
        }
        return GmailApiException.insufficientScope(reason);
      }
      if (status >= 500) {
        return GmailApiException.serverError(status, reason);
      }
      return GmailApiException.serverError(status, reason);
    }
    if (ex instanceof ResourceAccessException) {
      log.warn("Gmail {} timed out", operation);
      return GmailApiException.timeout();
    }
    log.warn("Gmail {} failed: {}", operation, ex.getClass().getSimpleName());
    return GmailApiException.serverError(0, "transport");
  }

  private static String providerReason(HttpStatusCodeException http) {
    try {
      JsonNode body = http.getResponseBodyAs(JsonNode.class);
      JsonNode errors = body != null ? body.path("error").path("errors") : null;
      if (errors != null && errors.isArray() && !errors.isEmpty()
          && errors.get(0).hasNonNull("reason")) {
        return errors.get(0).get("reason").asText();
      }
    } catch (RuntimeException ignored) {
      // Fall through to the status-derived reason.
    }
    return "http-" + http.getStatusCode().value();
  }

  private static boolean isQuotaReason(String reason) {
    String lower = reason.toLowerCase();
    return lower.contains("ratelimitexceeded")
        || lower.contains("userratelimitexceeded")
        || lower.contains("quotaexceeded");
  }

  private static long retryAfterSeconds(HttpStatusCodeException http) {
    try {
      String header = http.getResponseHeaders() != null
          ? http.getResponseHeaders().getFirst("Retry-After") : null;
      if (header != null) {
        return Math.max(0, Long.parseLong(header.trim()));
      }
    } catch (RuntimeException ignored) {
      // Fall through to zero backoff hint.
    }
    return 0;
  }

  private static long parseLong(String raw, long fallback) {
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
