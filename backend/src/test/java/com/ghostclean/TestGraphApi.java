package com.ghostclean;

import com.ghostclean.mailbox.GraphApiException;
import com.ghostclean.mailbox.MailboxReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Scripted Graph double: no network, no real mailbox, synthetic fixtures
 * only. Pages are served from a scripted id list; list/get failures are
 * queued per call. Mirrors {@code TestGmailApi}.
 */
@TestConfiguration
public class TestGraphApi {

  @Bean
  @Primary
  public FakeGraphReader fakeGraphReader() {
    return new FakeGraphReader();
  }

  public static class FakeGraphReader implements MailboxReader {

    private final Map<String, MessageMetadata> messages = new ConcurrentHashMap<>();
    private final List<List<String>> pages = new ArrayList<>();
    private final Queue<GraphApiException> listFailures = new ConcurrentLinkedQueue<>();
    private final Queue<GraphApiException> getFailures = new ConcurrentLinkedQueue<>();
    public final AtomicInteger listCalls = new AtomicInteger();
    public final AtomicInteger getCalls = new AtomicInteger();
    public volatile String capturedQuery;
    public volatile int capturedTop;

    @Override
    public String providerId() {
      return "microsoft";
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

    public void enqueueListFailure(GraphApiException failure) {
      listFailures.add(failure);
    }

    public void enqueueGetFailure(GraphApiException failure) {
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
      capturedTop = 0;
    }

    @Override
    public MessagePage listMessages(String accessToken, String query, String pageToken,
        int maxResults) {
      listCalls.incrementAndGet();
      capturedQuery = query;
      capturedTop = maxResults;
      GraphApiException failure = listFailures.poll();
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
      GraphApiException failure = getFailures.poll();
      if (failure != null) {
        throw failure;
      }
      MessageMetadata meta = messages.get(messageId);
      if (meta == null) {
        throw GraphApiException.notFound();
      }
      return meta;
    }

    public static Map<String, MessageMetadata> linked(Map<String, MessageMetadata> input) {
      return new LinkedHashMap<>(input);
    }
  }
}
