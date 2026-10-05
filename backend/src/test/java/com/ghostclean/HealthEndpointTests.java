package com.ghostclean;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Health probe is public and secret-free; protected routes reject anonymous
 * callers; correlation ids are echoed.
 */
@AutoConfigureMockMvc
class HealthEndpointTests extends PostgresTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void healthIsPublicAndWellFormed() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.service").value("ghostclean-api"))
        .andExpect(jsonPath("$.version").exists())
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void healthResponseExposesNoSecrets() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString().toLowerCase();
    for (String forbidden : new String[]{
        "password", "token", "secret", "jdbc:", "begin private", "encryption"}) {
      org.junit.jupiter.api.Assertions.assertFalse(
          body.contains(forbidden), "health body must not contain: " + forbidden);
    }
  }

  @Test
  void actuatorHealthIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void protectedEndpointRejectsAnonymousCallers() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void inboundRequestIdIsEchoed() throws Exception {
    mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "phase1-probe-123"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "phase1-probe-123"));
  }

  @Test
  void responseMentionsNothingSensitive() throws Exception {
    // Hamcrest variant of the leak check, kept as an independent assertion path.
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
            .string(not(containsString("DATABASE"))));
  }
}
