package com.ghostclean;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * GhostClean API entry point (Phase 1 foundation).
 *
 * <p>Modular monolith: business modules live under {@code com.ghostclean.*}.
 * Phase 1 wires only the foundation (health, security defaults, crypto seam,
 * persistence baseline). No OAuth, scanning, detection, or deletion logic.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GhostCleanApplication {

  public static void main(String[] args) {
    SpringApplication.run(GhostCleanApplication.class, args);
  }
}
