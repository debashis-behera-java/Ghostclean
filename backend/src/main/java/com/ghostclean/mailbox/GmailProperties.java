package com.ghostclean.mailbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounded-scan budgets (Phase 4). Every bound is conservative by default and
 * overridable from the environment; scans execute synchronously in-request,
 * so the message cap also bounds HTTP time. Async execution is roadmap, not
 * Phase 4 scope.
 */
@Component
@ConfigurationProperties(prefix = "gmail")
public class GmailProperties {

  /** Messages listed per Gmail page (1-500). */
  private int pageSize = 100;
  /** Pages fetched per scan at most. */
  private int maxPages = 5;
  /** Hard ceiling on messages examined per scan. */
  private int maxMessagesPerScan = 500;
  /** Wall-clock budget per scan; the loop stops cleanly when exceeded. */
  private int maxDurationSeconds = 120;
  /** Ceiling on Gmail API calls per scan (list + get + slack). */
  private int maxApiCalls = 1200;
  /** Lookback window for the initial scan; broader rescans are future config. */
  private int lookbackMonths = 12;
  /** Total attempts per Gmail call (1 initial + retries). */
  private int maxAttempts = 4;
  private int backoffBaseMillis = 1000;
  private int maxBackoffMillis = 15000;
  /** Evidence persisted only at/above this score (0-100); noise stays out. */
  private int persistThreshold = 10;

  /**
   * Targeted registration-evidence keywords (English bundle; locale bundles
   * plug in here later). Small and curated by design — never hundreds.
   */
  private List<String> queryKeywords = List.of(
      "welcome", "verify", "confirm", "sign up", "registration",
      "account created", "get started", "activate", "receipt",
      "invoice", "subscription", "password reset");

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
  }

  public int getMaxPages() {
    return maxPages;
  }

  public void setMaxPages(int maxPages) {
    this.maxPages = maxPages;
  }

  public int getMaxMessagesPerScan() {
    return maxMessagesPerScan;
  }

  public void setMaxMessagesPerScan(int maxMessagesPerScan) {
    this.maxMessagesPerScan = maxMessagesPerScan;
  }

  public int getMaxDurationSeconds() {
    return maxDurationSeconds;
  }

  public void setMaxDurationSeconds(int maxDurationSeconds) {
    this.maxDurationSeconds = maxDurationSeconds;
  }

  public int getMaxApiCalls() {
    return maxApiCalls;
  }

  public void setMaxApiCalls(int maxApiCalls) {
    this.maxApiCalls = maxApiCalls;
  }

  public int getLookbackMonths() {
    return lookbackMonths;
  }

  public void setLookbackMonths(int lookbackMonths) {
    this.lookbackMonths = lookbackMonths;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public int getBackoffBaseMillis() {
    return backoffBaseMillis;
  }

  public void setBackoffBaseMillis(int backoffBaseMillis) {
    this.backoffBaseMillis = backoffBaseMillis;
  }

  public int getMaxBackoffMillis() {
    return maxBackoffMillis;
  }

  public void setMaxBackoffMillis(int maxBackoffMillis) {
    this.maxBackoffMillis = maxBackoffMillis;
  }

  public int getPersistThreshold() {
    return persistThreshold;
  }

  public void setPersistThreshold(int persistThreshold) {
    this.persistThreshold = persistThreshold;
  }

  public List<String> getQueryKeywords() {
    return queryKeywords;
  }

  public void setQueryKeywords(List<String> queryKeywords) {
    this.queryKeywords = queryKeywords;
  }
}
