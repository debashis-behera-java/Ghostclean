package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.mailbox.GmailQueryBuilder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider-side query construction: bounded window + targeted subjects. */
class GmailQueryBuilderTests {

  private final GmailQueryBuilder builder = new GmailQueryBuilder();

  @Test
  void buildsLookbackSubjectQuery() {
    String query = builder.buildLookbackQuery(List.of("welcome", "verify", "sign up"), 360);
    assertEquals("newer_than:360d subject:({welcome OR verify OR \"sign up\"})", query);
  }

  @Test
  void lookbackFloorIsOneDay() {
    String query = builder.buildLookbackQuery(List.of("welcome"), 0);
    assertTrue(query.startsWith("newer_than:1d"));
  }

  @Test
  void blankKeywordsAreSkipped() {
    String query = builder.buildLookbackQuery(Arrays.asList("welcome", " ", null, "verify"), 30);
    assertEquals("newer_than:30d subject:({welcome OR verify})", query);
  }
}
