package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostclean.auth.ResendVerificationEmailSender;
import com.ghostclean.auth.VerificationEmailContent;
import com.ghostclean.auth.VerificationMailException;
import com.ghostclean.auth.VerificationMailProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verification-mail provider tests (ADR-038). A JDK stub HTTP server
 * stands in for Resend — live delivery is a documented manual step,
 * never CI.
 */
class VerificationMailProviderTests {

  private HttpServer stub;

  @AfterEach
  void stopStub() {
    if (stub != null) {
      stub.stop(0);
    }
  }

  // -- content ---------------------------------------------------------------

  @Test
  void contentCarriesBrandActionExpiryAndNoticeWithoutCredentials() {
    String link = "http://localhost:8082/verify-email?token=abc123&x=1\"<b>";
    VerificationEmailContent.EmailBody body = VerificationEmailContent.build(link);

    assertTrue(body.subject().contains("GhostClean"));
    assertTrue(body.text().contains(link));
    assertTrue(body.text().contains("24 hours"));
    assertTrue(body.text().contains("only once"));
    assertTrue(body.text().contains("did not create"));
    assertTrue(body.html().contains("Verify your email"));
    assertTrue(body.html().contains("&quot;"));
    assertTrue(body.html().contains("&lt;b&gt;"));
    assertFalse(body.text().toLowerCase().contains("password"));
    assertFalse(body.html().toLowerCase().contains("password"));
  }

  @Test
  void propertiesDefaultToDisabledWithResendTransport() {
    VerificationMailProperties properties = new VerificationMailProperties();
    assertEquals("", properties.getProvider());
    assertFalse(properties.isResendSelected());
    assertEquals("https://api.resend.com", properties.getBaseUrl());
    assertEquals(8000, properties.getTimeoutMs());
    assertEquals("GhostClean", properties.getFromName());
  }

  // -- sender ----------------------------------------------------------------

  @Test
  void successPostsBearerAuthAndFullPayload() throws Exception {
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> payload = new AtomicReference<>();
    startStub(200, "{\"id\":\"mail_123\"}", auth, payload);

    VerificationMailProperties properties = baseProperties();
    new ResendVerificationEmailSender(properties)
        .sendVerificationEmail("user@example.com",
            "http://localhost:8082/verify-email?token=tok");

    assertEquals("Bearer test-key", auth.get());
    JsonNode json = new ObjectMapper().readTree(payload.get());
    assertEquals("GhostClean <verify@ghostclean.test>", json.get("from").asText());
    assertEquals("user@example.com", json.get("to").get(0).asText());
    assertTrue(json.get("subject").asText().contains("GhostClean"));
    assertTrue(json.get("html").asText().contains("tok"));
    assertTrue(json.get("text").asText().contains("tok"));
  }

  @Test
  void blankFromNameSendsAddressOnly() throws Exception {
    AtomicReference<String> payload = new AtomicReference<>();
    startStub(200, "{\"id\":\"mail_1\"}", new AtomicReference<>(), payload);

    VerificationMailProperties properties = baseProperties();
    properties.setFromName("  ");
    new ResendVerificationEmailSender(properties)
        .sendVerificationEmail("user@example.com", "http://x/verify-email?token=t");

    JsonNode json = new ObjectMapper().readTree(payload.get());
    assertEquals("verify@ghostclean.test", json.get("from").asText());
  }

  @Test
  void providerErrorThrowsWithoutLeakingKeyOrLink() throws Exception {
    startStub(500, "{\"message\":\"boom\"}",
        new AtomicReference<>(), new AtomicReference<>());

    VerificationMailProperties properties = baseProperties();
    ResendVerificationEmailSender sender = new ResendVerificationEmailSender(properties);
    String link = "http://localhost:8082/verify-email?token=secret-tok";
    VerificationMailException ex = assertThrows(VerificationMailException.class,
        () -> sender.sendVerificationEmail("user@example.com", link));
    assertFalse(ex.getMessage().contains("test-key"));
    assertFalse(ex.getMessage().contains("secret-tok"));
    assertFalse(ex.getMessage().contains("user@example.com"));
  }

  @Test
  void unreachableProviderThrowsTransportCategory() {
    VerificationMailProperties properties = baseProperties();
    properties.setBaseUrl("http://127.0.0.1:1");
    properties.setTimeoutMs(500);
    ResendVerificationEmailSender sender = new ResendVerificationEmailSender(properties);
    VerificationMailException ex = assertThrows(VerificationMailException.class,
        () -> sender.sendVerificationEmail("user@example.com", "http://x?token=t"));
    assertEquals("timeout", ex.getCategory());
  }

  @Test
  void providerAuthFailureClassifiedAsHttpAuthWithoutLeaks() throws Exception {
    // Pins the production signature: Resend 401/403 (bad or revoked key)
    // must surface as http-auth carrying no key, link, or recipient.
    for (int status : new int[]{401, 403}) {
      startStub(status, "{\"message\":\"forbidden\"}",
          new AtomicReference<>(), new AtomicReference<>());

      VerificationMailProperties properties = baseProperties();
      ResendVerificationEmailSender sender = new ResendVerificationEmailSender(properties);
      String link = "http://localhost:8082/verify-email?token=secret-tok";
      VerificationMailException ex = assertThrows(VerificationMailException.class,
          () -> sender.sendVerificationEmail("user@example.com", link));
      assertEquals("http-auth", ex.getCategory());
      assertFalse(ex.getMessage().contains("test-key"));
      assertFalse(ex.getMessage().contains("secret-tok"));
      assertFalse(ex.getMessage().contains("user@example.com"));
      stopStub();
      stub = null;
    }
  }

  @Test
  void missingApiKeyFailsFast() {    VerificationMailProperties properties = baseProperties();
    properties.setApiKey("  ");
    assertThrows(IllegalStateException.class,
        () -> new ResendVerificationEmailSender(properties).requireCompleteConfiguration());
  }

  @Test
  void missingSenderFailsFast() {
    VerificationMailProperties properties = baseProperties();
    properties.setFrom(" ");
    assertThrows(IllegalStateException.class,
        () -> new ResendVerificationEmailSender(properties).requireCompleteConfiguration());
  }

  // -- diagnostics -----------------------------------------------------------

  @Test
  void providerErrorDiagnosticsExposeStatusAndSanitizedName() {
    org.springframework.web.client.HttpStatusCodeException unauthorized =
        org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized",
            org.springframework.http.HttpHeaders.EMPTY,
            "{\"statusCode\":401,\"name\":\"unauthorized\",\"message\":\"Invalid API key\"}"
                .getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    assertEquals(401, ResendVerificationEmailSender.httpStatusOf(unauthorized));
    assertEquals("unauthorized: Invalid API key",
        ResendVerificationEmailSender.safeProviderError(unauthorized));
  }

  @Test
  void providerErrorDiagnosticsRedactEmailLikeContent() {
    org.springframework.web.client.HttpStatusCodeException forbidden =
        org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden",
            org.springframework.http.HttpHeaders.EMPTY,
            "{\"name\":\"forbidden\",\"message\":\"The somebody@example.com address is restricted\"}"
                .getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    String diagnostic = ResendVerificationEmailSender.safeProviderError(forbidden);
    assertTrue(diagnostic.startsWith("forbidden:"));
    assertFalse(diagnostic.contains("somebody@example.com"));
    assertTrue(diagnostic.contains("[redacted-email]"));
  }

  @Test
  void providerErrorDiagnosticsEmptyWhenNothingUsable() {
    org.springframework.web.client.HttpStatusCodeException empty =
        org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.BAD_GATEWAY, "Bad Gateway",
            org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
    assertEquals(502, ResendVerificationEmailSender.httpStatusOf(empty));
    assertEquals("", ResendVerificationEmailSender.safeProviderError(empty));

    org.springframework.web.client.ResourceAccessException transport =
        new org.springframework.web.client.ResourceAccessException("I/O error");
    assertEquals(-1, ResendVerificationEmailSender.httpStatusOf(transport));
    assertEquals("", ResendVerificationEmailSender.safeProviderError(transport));
  }

  // -- helpers ---------------------------------------------------------------

  private VerificationMailProperties baseProperties() {
    VerificationMailProperties properties = new VerificationMailProperties();
    properties.setProvider("resend");
    properties.setFrom("verify@ghostclean.test");
    properties.setFromName("GhostClean");
    properties.setApiKey("test-key");
    properties.setBaseUrl(stub == null
        ? "http://127.0.0.1:9"
        : "http://127.0.0.1:" + stub.getAddress().getPort());
    return properties;
  }

  private void startStub(int status, String response,
      AtomicReference<String> auth, AtomicReference<String> payload) throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/emails", exchange -> {
      try {
        auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] request = exchange.getRequestBody().readAllBytes();
        payload.set(new String(request, StandardCharsets.UTF_8));
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(body);
        }
      } finally {
        exchange.close();
      }
    });
    stub.start();
  }
}
