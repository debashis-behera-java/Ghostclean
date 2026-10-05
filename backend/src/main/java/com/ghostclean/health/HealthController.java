package com.ghostclean.health;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness probe. Returns static service facts only — never secrets,
 * connection strings, tokens, or infrastructure details.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

  private final String version;
  private final String region;

  public HealthController(
      @Value("${app.version:0.1.0-phase1}") String version,
      @Value("${app.region:eu}") String region) {
    this.version = version;
    this.region = region;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of(
        "status", "UP",
        "service", "ghostclean-api",
        "version", version,
        "region", region);
  }
}
