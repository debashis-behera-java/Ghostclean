package com.ghostclean.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
 * Microsoft Graph read-path adapter (Phase 10). Calls exactly two read
 * endpoints — message listing and per-message metadata (From/Subject/
 * List-Unsubscribe signal only, via {@code $select}). No other Graph
 * surface is referenced anywhere in this class, so a send/modify/delete
 * call cannot be added without tripping the build-time capability scan.
 *
 * <p>Pagination follows {@code @odata.nextLink} only when it points back at
 * the configured Graph host over HTTPS; anything else ends the walk instead
 * of being fetched. Only ids, statuses, and provider reason codes are
 * logged — never bodies, tokens, or subjects.
 */
@Component
@Profile("!graph-fake")
public class GraphMailboxReader implements MailboxReader {

  private static final Logger log = LoggerFactory.getLogger(GraphMailboxReader.class);

  private final GraphProperties properties;
  private final RestClient http;

  public GraphMailboxReader(GraphProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String providerId() {
    return "microsoft";
  }

  @Override
  public MessagePage listMessages(String accessToken, String query,
      String pageToken, int maxResults) {
    String uri;
    if (pageToken == null || pageToken.isBlank()) {
      uri = listUri(base(), query, maxResults);
    } else if (isTrustedNextLink(base(), pageToken)) {
      uri = pageToken;
    } else {
      // Untrusted or malformed cursor: end the walk rather than fetch it.
      log.warn("Ignoring untrusted Graph page cursor");
      return new MessagePage(List.of(), null, 0);
    }
    try {
      JsonNode body = http.get()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .header("ConsistencyLevel", "eventual")
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      List<String> ids = new ArrayList<>();
      JsonNode value = body != null ? body.get("value") : null;
      if (value != null && value.isArray()) {
        for (JsonNode message : value) {
          if (message.hasNonNull("id")) {
            ids.add(message.get("id").asText());
          }
        }
      }
      String next = body != null && body.hasNonNull("@odata.nextLink")
          ? body.get("@odata.nextLink").asText() : null;
      if (!isTrustedNextLink(base(), next)) {
        next = null;
      }
      int estimate = body != null && body.has("@odata.count")
          ? body.get("@odata.count").asInt(0) : 0;
      return new MessagePage(List.copyOf(ids), next, estimate);
    } catch (RestClientException ex) {
      throw classify(ex, "list");
    }
  }

  @Override
  public MessageMetadata getMessage(String accessToken, String messageId) {
    String uri = UriComponentsBuilder
        .fromUriString(base() + "/v1.0/me/messages/{id}")
        .queryParam("$select", "id,receivedDateTime,from,subject,internetMessageHeaders")
        .buildAndExpand(messageId)
        .encode()
        .toUri()
        .toString();
    try {
      JsonNode body = http.get()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      MessageMetadata parsed = parseMessage(body);
      if (parsed == null) {
        throw GraphApiException.notFound();
      }
      return parsed;
    } catch (GraphApiException ex) {
      throw ex;
    } catch (RestClientException ex) {
      throw classify(ex, "get");
    }
  }

  /**
   * First-page list URL. The OData filter travels as a template variable so
   * {@code encode()} percent-encodes filter metacharacters (quotes, spaces,
   * parentheses) instead of leaving them raw in the request target.
   * Public so the encoding is directly unit-testable.
   */
  public static String listUri(String baseUrl, String filter, int maxResults) {
    return UriComponentsBuilder
        .fromUriString(baseUrl + "/v1.0/me/messages")
        .queryParam("$select", "id")
        .queryParam("$top", Math.max(1, Math.min(999, maxResults)))
        .queryParam("$filter", "{filter}")
        .queryParam("$orderby", "receivedDateTime desc")
        .queryParam("$count", "true")
        .buildAndExpand(filter)
        .encode()
        .toUriString();
  }

  /**
   * Trust boundary for server-driven pagination: only same-host HTTPS URLs
   * derived from the configured Graph base are ever followed. Public so the
   * boundary is directly unit-testable.
   */
  public static boolean isTrustedNextLink(String baseUrl, String nextLink) {
    if (nextLink == null || nextLink.isBlank()) {
      return false;
    }
    try {
      URI base = URI.create(baseUrl.strip());
      URI next = URI.create(nextLink.strip());
      return "https".equalsIgnoreCase(next.getScheme())
          && next.getHost() != null
          && next.getHost().equalsIgnoreCase(base.getHost());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /**
   * Minimum metadata shaping: ids, timestamp, sender, subject, and the
   * List-Unsubscribe signal only. Returns null when the payload carries no
   * stable message id (deleted or malformed upstream row). Public so the
   * shaping is directly unit-testable.
   */
  public static MessageMetadata parseMessage(JsonNode body) {
    if (body == null || !body.hasNonNull("id")) {
      return null;
    }
    long receivedAt = 0L;
    if (body.hasNonNull("receivedDateTime")) {
      try {
        receivedAt = Instant.parse(body.get("receivedDateTime").asText()).toEpochMilli();
      } catch (RuntimeException ignored) {
        // Fall through to the zero timestamp (treated as oldest).
      }
    }
    String from = "";
    JsonNode fromNode = body.path("from").path("emailAddress");
    if (fromNode.hasNonNull("address")) {
      from = fromNode.get("address").asText();
    }
    String subject = body.hasNonNull("subject") ? body.get("subject").asText() : "";
    boolean listUnsubscribe = false;
    JsonNode headers = body.get("internetMessageHeaders");
    if (headers != null && headers.isArray()) {
      for (JsonNode header : headers) {
        String name = header.hasNonNull("name") ? header.get("name").asText() : "";
        String value = header.hasNonNull("value") ? header.get("value").asText() : "";
        if (name.equalsIgnoreCase("List-Unsubscribe") && !value.isBlank()) {
          listUnsubscribe = true;
          break;
        }
      }
    }
    return new MessageMetadata(body.get("id").asText(), receivedAt, from, subject,
        listUnsubscribe);
  }

  private String base() {
    String base = properties.getBaseUrl();
    if (base == null || base.isBlank()) {
      return "https://graph.microsoft.com";
    }
    String stripped = base.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  private GraphApiException classify(RestClientException ex, String operation) {
    if (ex instanceof HttpStatusCodeException http) {
      int status = http.getStatusCode().value();
      String reason = providerReason(http);
      log.warn("Graph {} failed: status={} reason={}", operation, status, reason);
      if (status == 401) {
        return GraphApiException.unauthenticated();
      }
      if (status == 404) {
        return GraphApiException.notFound();
      }
      if (status == 429) {
        return GraphApiException.rateLimited(retryAfterSeconds(http));
      }
      if (status == 403) {
        return GraphApiException.insufficientScope(reason);
      }
      if (status >= 500) {
        return GraphApiException.serverError(status, reason);
      }
      return GraphApiException.serverError(status, reason);
    }
    if (ex instanceof ResourceAccessException) {
      log.warn("Graph {} timed out", operation);
      return GraphApiException.timeout();
    }
    log.warn("Graph {} failed: {}", operation, ex.getClass().getSimpleName());
    return GraphApiException.serverError(0, "transport");
  }

  private static String providerReason(HttpStatusCodeException http) {
    try {
      JsonNode body = http.getResponseBodyAs(JsonNode.class);
      JsonNode code = body != null ? body.path("error").path("code") : null;
      if (code != null && code.isTextual() && !code.asText().isBlank()) {
        return code.asText();
      }
    } catch (RuntimeException ignored) {
      // Fall through to the status-derived reason.
    }
    return "http-" + http.getStatusCode().value();
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
}
