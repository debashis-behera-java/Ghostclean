package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.mailbox.GraphQueryBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Graph $filter construction unit tests (no Spring, no network): lookback
 * window plus registration-evidence subject hints, safely escaped.
 */
class GraphQueryBuilderTests {

  private final GraphQueryBuilder queries = new GraphQueryBuilder();

  @Test
  void filterCarriesLookbackWindowAndSubjectHints() {
    String filter = queries.buildLookbackFilter(List.of("welcome", "verify", "sign up"), 12);
    assertTrue(filter.contains("receivedDateTime ge "));
    assertTrue(filter.contains("contains(subject,'welcome')"));
    assertTrue(filter.contains("contains(subject,'verify')"));
    assertTrue(filter.contains("contains(subject,'sign up')"));
  }

  @Test
  void singleQuotesAreEscapedByDoubling() {
    String filter = queries.buildLookbackFilter(List.of("o'brien"), 1);
    assertTrue(filter.contains("o''brien"));
    assertFalse(filter.contains("o'brien'") && !filter.contains("o''brien"));
  }

  @Test
  void blankKeywordsFallBackToDateOnly() {
    String filter = queries.buildLookbackFilter(
        java.util.Arrays.asList("  ", null, ""), 6);
    assertTrue(filter.contains("receivedDateTime ge "));
    assertFalse(filter.contains("contains("));
  }
}
