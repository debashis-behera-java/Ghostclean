package com.ghostclean.mailbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Targeted Graph {@code $filter} construction (Phase 10). Queries the
 * provider index (lookback window + registration-evidence subject hints)
 * instead of downloading the mailbox and filtering locally. Pure function
 * of its inputs — unit-tested.
 */
@Component
public class GraphQueryBuilder {

  /**
   * Builds e.g. {@code receivedDateTime ge 2025-09-16T07:00:00Z and
   * (contains(subject,'welcome') or ...)}. Single quotes are escaped by
   * doubling per the OData convention; a blank keyword list falls back to
   * the date bound only.
   */
  public String buildLookbackFilter(List<String> keywords, int lookbackMonths) {
    int months = Math.max(1, lookbackMonths);
    String since = Instant.now().minus(months * 30L, ChronoUnit.DAYS).toString();
    String hints = keywords.stream()
        .filter(keyword -> keyword != null && !keyword.isBlank())
        .map(String::trim)
        .map(keyword -> keyword.replace("'", "''"))
        .map(keyword -> "contains(subject,'" + keyword + "')")
        .collect(Collectors.joining(" or "));
    if (hints.isEmpty()) {
      return "receivedDateTime ge " + since;
    }
    return "receivedDateTime ge " + since + " and (" + hints + ")";
  }
}
