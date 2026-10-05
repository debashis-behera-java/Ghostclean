package com.ghostclean;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 12 privacy page (ADR decision 7): publicly reachable approved
 * content drawn from the privacy model — what is stored, retention,
 * deletion — versioned and dated, with no invented certifications or
 * legal guarantees.
 */
@AutoConfigureMockMvc
class PrivacyPageTests extends PostgresTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void privacyPageIsPublicAndVersioned() throws Exception {
    mockMvc.perform(get("/api/v1/privacy"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", containsString("text/html")))
        .andExpect(content().string(containsString("What GhostClean stores")))
        .andExpect(content().string(containsString("Retention")))
        .andExpect(content().string(containsString("Deletion")))
        .andExpect(content().string(containsString("Version")));
  }

  @Test
  void privacyPageMakesNoInventedClaims() throws Exception {
    String body = mockMvc.perform(get("/api/v1/privacy"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString().toLowerCase();
    for (String forbidden : new String[]{
        // Affirmative certification/guarantee claims. The required
        // "not a law firm / not a legal service" disclaimer below is
        // allowed: negations are checked separately.
        "certif", "guarantee", "fully compliant", "we warrant", "iso 27001",
        "soc 2", "absolute deletion", "deletion guaranteed"}) {
      org.junit.jupiter.api.Assertions.assertFalse(
          body.contains(forbidden), "privacy page must not claim: " + forbidden);
    }
    for (String requiredNegation : new String[]{
        "not a law firm", "not a legal service"}) {
      org.junit.jupiter.api.Assertions.assertTrue(
          body.contains(requiredNegation),
          "privacy page must carry the disclaimer: " + requiredNegation);
    }
  }

  @Test
  void privacyPageStatesMetadataOnlyHandling() throws Exception {
    mockMvc.perform(get("/api/v1/privacy"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("never stores email bodies")));
  }
}
