package com.ghostclean.mailbox;

import java.util.List;

/**
 * Read-only mailbox boundary (Phase 4). This interface exposes message
 * listing and metadata retrieval ONLY — there is deliberately no write
 * surface, so provider write operations are structurally impossible through
 * this seam. Implementations must never log bodies, tokens, or subjects.
 */
public interface MailboxReader {

  /** Stable provider key, e.g. {@code "google"}. */
  String providerId();

  /**
   * One bounded page of message ids matching the query. Never returns bodies.
   *
   * @param pageToken opaque provider cursor, null for the first page
   */
  MessagePage listMessages(String accessToken, String query, String pageToken, int maxResults);

  /**
   * Metadata envelope for one message: ids, timestamp, sender, subject, and
   * mailing-list signal only. No bodies, no attachments, no other headers.
   */
  MessageMetadata getMessage(String accessToken, String messageId);

  record MessagePage(List<String> messageIds, String nextPageToken, int resultSizeEstimate) {
  }

  record MessageMetadata(
      String id,
      long internalDateMillis,
      String from,
      String subject,
      boolean listUnsubscribePresent) {
  }
}
