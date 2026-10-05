package com.ghostclean.privacy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Outbound-mail configuration (Phase 7). Everything sensitive comes from
 * the environment and is never logged; {@code .env.example} carries
 * placeholders only. The default provider is the in-memory sink, so a
 * stock checkout can never send real email by accident.
 */
@Component
@ConfigurationProperties(prefix = "outbound.mail")
public class OutboundMailProperties {

  /** Provider selector. {@code "sink"} (default) records instead of sending. */
  private String provider = "sink";
  /** Controlled From address for outbound privacy requests. */
  private String from = "privacy-requests@example.invalid";
  /**
   * Reserved for a future real provider (API key / SMTP secret). Empty by
   * default; never logged, never audited, never returned by any API.
   */
  private String apiKey = "";
  /** Max provider attempts per dispatch (1 initial + retries), bounded. */
  private int maxAttempts = 3;

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }
}
