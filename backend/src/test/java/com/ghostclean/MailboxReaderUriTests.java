package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.mailbox.GmailMailboxReader;
import com.ghostclean.mailbox.GmailProperties;
import com.ghostclean.mailbox.GmailQueryBuilder;
import com.ghostclean.mailbox.GraphMailboxReader;
import com.ghostclean.mailbox.GraphProperties;
import com.ghostclean.mailbox.GraphQueryBuilder;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Read-path URI regression tests (no Spring, no network): provider search
 * queries carry metacharacters (braces, quotes, spaces, parentheses) that
 * must be percent-encoded in the request target. Handing the raw query to
 * the builder used to leave those characters unencoded, so the subsequent
 * {@code toUri()} threw IllegalStateException inside {@code listMessages}
 * and failed every real scan before any provider call. These tests pin the
 * encoding using the real default query shapes.
 */
class MailboxReaderUriTests {

  @Test
  void gmailListUriEncodesRealProductionQuery() {
    String query = new GmailQueryBuilder().buildLookbackQuery(
        new GmailProperties().getQueryKeywords(), 12 * 30);
    // Sanity: the production query really does carry the hostile characters.
    assertTrue(query.contains("{") && query.contains("\""),
        "expected braces/quotes in production query: " + query);

    URI uri = GmailMailboxReader.listUri(query, null, 100);
    String raw = uri.toString();
    assertTrue(raw.startsWith("https://gmail.googleapis.com/gmail/v1/users/me/messages?q="),
        "unexpected list target: " + raw);
    assertFalse(raw.contains("{"), "raw brace must be encoded");
    assertFalse(raw.contains("}"), "raw brace must be encoded");
    assertFalse(raw.contains("\""), "raw quote must be encoded");
    assertTrue(raw.contains("%7B") && raw.contains("%22"),
        "braces/quotes must be percent-encoded: " + raw);
    // The encoded form must decode back to the exact production query.
    assertEquals(query, decodedQueryParam(raw));
  }

  @Test
  void gmailListUriCarriesOpaquePageToken() {
    String query = "newer_than:30d";
    URI uri = GmailMailboxReader.listUri(query, "CAEQ9abc-_0123", 50);
    assertTrue(uri.toString().contains("pageToken=CAEQ9abc-_0123"));
    assertEquals(query, decodedQueryParam(uri.toString()));
  }

  @Test
  void graphListUriEncodesRealFilter() {
    String filter = new GraphQueryBuilder().buildLookbackFilter(
        new GraphProperties().getQueryKeywords(), 12);
    String url = GraphMailboxReader.listUri("https://graph.microsoft.com", filter, 50);
    // Must be a legal URI target (the pre-fix builder left raw quotes/spaces).
    URI.create(url);
    assertFalse(url.contains("\""), "raw quote must be encoded: " + url);
    assertTrue(url.contains("%22") || url.contains("%20"),
        "filter must be percent-encoded: " + url);
    assertTrue(url.contains("$filter="));
  }

  private static String decodedQueryParam(String url) {
    String query = URI.create(url).getRawQuery();
    for (String part : query.split("&")) {
      if (part.startsWith("q=")) {
        return URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("no q param in request target");
  }
}
