package com.ghostclean.mailbox;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Targeted Gmail search construction. Queries the provider index (lookback
 * window + registration-evidence subject hints) instead of downloading the
 * mailbox and filtering locally. Pure function of its inputs — unit-tested.
 */
@Component
public class GmailQueryBuilder {

  /**
   * Builds e.g. {@code newer_than:365d subject:({welcome OR verify OR ...})}.
   * Multi-word keywords are quoted; the keyword list itself stays small and
   * curated by configuration.
   */
  public String buildLookbackQuery(List<String> keywords, int lookbackDays) {
    int days = Math.max(1, lookbackDays);
    String joined = keywords.stream()
        .filter(keyword -> keyword != null && !keyword.isBlank())
        .map(String::trim)
        .map(keyword -> keyword.contains(" ") ? "\"" + keyword + "\"" : keyword)
        .collect(Collectors.joining(" OR "));
    return "newer_than:" + days + "d subject:({" + joined + "})";
  }
}
