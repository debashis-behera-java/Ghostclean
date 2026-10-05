package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 12 local Gap #8: production monitoring/actuator configuration test
 * (repository configuration only).
 *
 * <p>Verifies the ACTUAL current configuration is safe: only the health
 * actuator is exposed, details are never shown, the edge proxy keeps
 * {@code /actuator/} internal, and the public liveness probe stays
 * available and secret-free.
 *
 * <p>Local-only (ADR-036): no AWS/CloudWatch/KMS/external monitoring is
 * touched, no alerts are created, no production server is started.
 */
@AutoConfigureMockMvc
class ProductionMonitoringConfigurationTests extends PostgresTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private Environment env;

  @Test
  void productionExposesOnlyHealthActuator() {
    assertEquals("health", env.getProperty("management.endpoints.web.exposure.include"),
        "only the health actuator may be exposed");
    assertEquals("never", env.getProperty("management.endpoint.health.show-details"),
        "health details must never be shown publicly");
  }

  @Test
  void sensitiveActuatorEndpointsAreNotPubliclyExposed() throws Exception {
    List<String> sensitive = List.of(
        "env", "beans", "configprops", "mappings", "heapdump", "threaddump");
    for (String endpoint : sensitive) {
      MvcResult result = mockMvc.perform(get("/actuator/" + endpoint)).andReturn();
      int status = result.getResponse().getStatus();
      assertFalse(status == 200,
          "sensitive actuator endpoint must not be publicly exposed with data: " + endpoint
              + " (got HTTP " + status + ")");
    }
  }

  @Test
  void actuatorHealthShowsNoSensitiveDetails() throws Exception {
    MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();
    assertEquals(200, result.getResponse().getStatus(), "actuator health stays available");
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase();
    assertTrue(body.contains("up"), "actuator health reports UP");
    for (String forbidden : new String[]{"password", "secret", "jdbc:", "begin private"}) {
      assertFalse(body.contains(forbidden),
          "actuator health must not leak sensitive detail: " + forbidden);
    }
  }

  @Test
  void managementPortAndProbesAreNotExplicitlyConfigured() throws Exception {
    assertNull(env.getProperty("management.server.port"),
        "no explicit management port is configured (default shared port applies)");
    assertNull(env.getProperty("management.server.address"),
        "no explicit management address is configured");
    String appYml = readClasspath("application.yml").toLowerCase();
    assertFalse(appYml.contains("liveness"), "no liveness probe requirement is invented");
    assertFalse(appYml.contains("readiness"), "no readiness probe requirement is invented");
    assertFalse(appYml.contains("probes"), "no probe block is configured");
  }

  @Test
  void applicationHealthEndpointRemainsAvailable() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/health")).andReturn();
    assertEquals(200, result.getResponse().getStatus(), "public liveness probe stays available");
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase();
    assertTrue(body.contains("\"status\":\"up\"") || body.contains("up"),
        "liveness probe reports UP");
    for (String forbidden : new String[]{"password", "secret", "jdbc:"}) {
      assertFalse(body.contains(forbidden), "liveness probe must stay secret-free: " + forbidden);
    }
  }

  @Test
  void edgeProxyKeepsActuatorInternal() throws Exception {
    String nginx = readRepoFile("infrastructure/nginx.conf", "docker-compose.yml");
    assertTrue(nginx.contains("location /actuator/"),
        "local edge config must carry the actuator block");
    assertTrue(nginx.contains("return 404"),
        "local edge must not proxy actuator (returns 404)");
    String compose = readRepoFile("docker-compose.yml", "infrastructure/nginx.conf");
    assertFalse(compose.toLowerCase().contains("actuator"),
        "local compose must not widen actuator exposure");
    assertFalse(compose.contains("MANAGEMENT_"),
        "local compose must not override management exposure");
  }

  private static String readClasspath(String name) throws Exception {
    ClassPathResource resource = new ClassPathResource(name);
    assertTrue(resource.exists(), "classpath resource must exist: " + name);
    try (var in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String readRepoFile(String first, String second) throws Exception {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
      Path direct = candidate.resolve(first);
      if (Files.isRegularFile(direct)) {
        return Files.readString(direct, StandardCharsets.UTF_8);
      }
      Path sibling = candidate.resolve(second);
      if (Files.isRegularFile(sibling)) {
        Path alternate = candidate.resolve(first);
        if (Files.isRegularFile(alternate)) {
          return Files.readString(alternate, StandardCharsets.UTF_8);
        }
      }
    }
    // Fallback for the Maven module working directory (backend/): repo root is one level up.
    for (String name : new String[]{first, "../" + first}) {
      Path fallback = dir.resolve(name);
      if (Files.isRegularFile(fallback)) {
        return Files.readString(fallback, StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("repository file not found: " + first);
  }
}
