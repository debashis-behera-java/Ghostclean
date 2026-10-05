package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostclean.mailbox.GraphMailboxReader;
import com.ghostclean.mailbox.MailboxReader.MessageMetadata;
import org.junit.jupiter.api.Test;

/**
 * Graph response-shaping unit tests (no Spring, no network): pagination-URL
 * trust boundary and metadata parsing, including malformed payloads.
 */
class GraphReaderUnitTests {

  private static final String BASE = "https://graph.microsoft.com";
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sameOriginNextLinkIsTrusted() {
    assertTrue(GraphMailboxReader.isTrustedNextLink(BASE,
        "https://graph.microsoft.com/v1.0/me/messages?$skip=50"));
  }

  @Test
  void foreignNextLinkIsRejected() {
    assertFalse(GraphMailboxReader.isTrustedNextLink(BASE,
        "https://evil.example/v1.0/me/messages?$skip=50"));
    assertFalse(GraphMailboxReader.isTrustedNextLink(BASE, null));
    assertFalse(GraphMailboxReader.isTrustedNextLink(BASE, "  "));
    assertFalse(GraphMailboxReader.isTrustedNextLink(BASE, "http://graph.microsoft.com/x"));
    assertFalse(GraphMailboxReader.isTrustedNextLink(BASE, "::::not-a-uri"));
  }

  @Test
  void messageParsesMinimumMetadata() throws Exception {
    JsonNode body = mapper.readTree("""
        {"id":"msg-1","receivedDateTime":"2026-09-01T10:00:00Z",
         "from":{"emailAddress":{"address":"welcome@spotify.com"}},
         "subject":"Welcome to Spotify",
         "internetMessageHeaders":[{"name":"List-Unsubscribe","value":"<mailto:u@x>"}]}""");
    MessageMetadata meta = GraphMailboxReader.parseMessage(body);
    assertEquals("msg-1", meta.id());
    assertTrue(meta.internalDateMillis() > 0);
    assertEquals("welcome@spotify.com", meta.from());
    assertEquals("Welcome to Spotify", meta.subject());
    assertTrue(meta.listUnsubscribePresent());
  }

  @Test
  void malformedPayloadsYieldNoMetadata() throws Exception {
    assertNull(GraphMailboxReader.parseMessage(null));
    assertNull(GraphMailboxReader.parseMessage(
        mapper.readTree("{\"subject\":\"no id\"}")));
    MessageMetadata noHeaders = GraphMailboxReader.parseMessage(
        mapper.readTree("{\"id\":\"m2\"}"));
    assertEquals("m2", noHeaders.id());
    assertEquals(0L, noHeaders.internalDateMillis());
    assertEquals("", noHeaders.from());
    assertEquals("", noHeaders.subject());
    assertFalse(noHeaders.listUnsubscribePresent());
  }
}
