package com.ghostclean;

import com.ghostclean.mailbox.GmailApiException;
import com.ghostclean.mailbox.MailboxReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Scripted Gmail double: no network, no real mailbox, synthetic fixtures
 * only. Pages are served from a scripted id list; list/get failures are
 * queued per call; an optional latch blocks message fetches to exercise
 * cancellation and concurrency deterministically.
 */
@TestConfiguration
public class TestGmailApi {

  @Bean
  @Primary
  public FakeGmailReader fakeGmailReader() {
    return new FakeGmailReader();
  }

  public static class FakeGmailReader implements MailboxReader {

    private final Map<String, MessageMetadata> messages = new ConcurrentHashMap<>();
    private final List<List<String>> pages = new ArrayList<>();
    private final Queue<GmailApiException> listFailures = new ConcurrentLinkedQueue<>();
    private final Queue<GmailApiException> getFailures = new ConcurrentLinkedQueue<>();
    public final AtomicInteger listCalls = new AtomicInteger();
    public final AtomicInteger getCalls = new AtomicInteger();
    public volatile String capturedQuery;
    public volatile CountDownLatch blockGets;

    @Override
    public String providerId() {
      return "google";
    }

    /** Script pages; each inner list is one page of message ids. */
    public synchronized void scriptPages(List<String>... pageIdLists) {
      pages.clear();
      for (List<String> page : pageIdLists) {
        pages.add(List.copyOf(page));
      }
    }

    public void addMessage(String id, String from, String subject, long internalDateMillis,
        boolean listUnsubscribe) {
      messages.put(id, new MessageMetadata(id, internalDateMillis, from, subject,
          listUnsubscribe));
    }

    public void enqueueListFailure(GmailApiException failure) {
      listFailures.add(failure);
    }

    public void enqueueGetFailure(GmailApiException failure) {
      getFailures.add(failure);
    }

    public void reset() {
      messages.clear();
      pages.clear();
      listFailures.clear();
      getFailures.clear();
      listCalls.set(0);
      getCalls.set(0);
      capturedQuery = null;
      blockGets = null;
    }

    @Override
    public MessagePage listMessages(String accessToken, String query, String pageToken,
        int maxResults) {
      listCalls.incrementAndGet();
      capturedQuery = query;
      GmailApiException failure = listFailures.poll();
      if (failure != null) {
        throw failure;
      }
      int index = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
      if (index >= pages.size()) {
        return new MessagePage(List.of(), null, 0);
      }
      List<String> ids = pages.get(index).stream()
          .limit(Math.max(1, maxResults))
          .toList();
      String next = index + 1 < pages.size() ? String.valueOf(index + 1) : null;
      int estimate = pages.stream().mapToInt(List::size).sum();
      return new MessagePage(ids, next, estimate);
    }

    @Override
    public MessageMetadata getMessage(String accessToken, String messageId) {
      getCalls.incrementAndGet();
      GmailApiException failure = getFailures.poll();
      if (failure != null) {
        throw failure;
      }
      CountDownLatch latch = blockGets;
      if (latch != null) {
        try {
          if (!latch.await(30, TimeUnit.SECONDS)) {
            throw GmailApiException.timeout();
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw GmailApiException.timeout();
        }
      }
      MessageMetadata meta = messages.get(messageId);
      if (meta == null) {
        throw GmailApiException.notFound();
      }
      return meta;
    }

    public static Map<String, MessageMetadata> linked(Map<String, MessageMetadata> input) {
      return new LinkedHashMap<>(input);
    }
  }
}
