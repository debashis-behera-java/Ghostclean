package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 production image shape (file-content only): the Dockerfile stays a
 * minimal multi-stage runtime run as non-root with no secrets baked in, an
 * exposed application port, and a health probe against the application's
 * health endpoint. Never builds or starts Docker here.
 */
class DockerfilePropertiesTests {

  private static String dockerfile() throws Exception {
    Path dockerfile = Paths.get("Dockerfile");
    if (!Files.exists(dockerfile)) {
      dockerfile = Paths.get("backend", "Dockerfile");
    }
    assertTrue(Files.exists(dockerfile), "backend/Dockerfile must exist");
    return Files.readString(dockerfile);
  }

  private static String finalStage(String content) {
    int lastFrom = content.toLowerCase().lastIndexOf("\nfrom ");
    if (lastFrom < 0 && content.toLowerCase().startsWith("from ")) {
      return content;
    }
    return lastFrom < 0 ? content : content.substring(lastFrom);
  }

  @Test
  void multiStageBuildWithNonRootRuntime() throws Exception {
    String content = dockerfile();
    List<String> fromLines = content.lines()
        .map(String::trim)
        .filter(line -> line.toUpperCase().startsWith("FROM "))
        .toList();
    assertTrue(fromLines.size() >= 2, "Dockerfile must use a multi-stage build");
    assertTrue(content.contains("AS builder"), "builder stage must be named");
    assertTrue(content.contains("--from=builder"), "runtime must copy from the builder stage");
    assertTrue(content.contains("USER ghostclean"),
        "final runtime image must run as USER ghostclean");
  }

  @Test
  void copiesNoSecretsOrCredentials() throws Exception {
    String content = dockerfile();
    for (String line : content.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (!trimmed.toUpperCase().startsWith("COPY")) {
        continue;
      }
      String upper = trimmed.toUpperCase();
      assertFalse(upper.contains(".ENV"), "Dockerfile must not COPY .env files: " + trimmed);
      assertFalse(upper.contains("ID_RSA"), "Dockerfile must not COPY private keys: " + trimmed);
      assertFalse(upper.contains(".PEM"), "Dockerfile must not COPY private keys: " + trimmed);
      assertFalse(upper.contains(".KEY"), "Dockerfile must not COPY private keys: " + trimmed);
      assertFalse(upper.contains(".AWS"), "Dockerfile must not COPY AWS credentials: " + trimmed);
      assertFalse(upper.contains("CREDENTIALS"),
          "Dockerfile must not COPY credential files: " + trimmed);
      assertFalse(upper.contains("SECRETS"), "Dockerfile must not COPY secrets: " + trimmed);
    }
  }

  @Test
  void exposesAppPortAndHealthcheck() throws Exception {
    String content = dockerfile();
    assertTrue(content.contains("EXPOSE 8080"), "Dockerfile must expose port 8080");
    assertTrue(content.contains("HEALTHCHECK"), "Dockerfile must define a HEALTHCHECK");
    assertTrue(content.contains("/api/v1/health"),
        "HEALTHCHECK must probe the application health endpoint");
  }

  @Test
  void finalStageHasNoBuildTooling() throws Exception {
    String runtime = finalStage(dockerfile()).toLowerCase();
    assertFalse(runtime.contains("maven"), "runtime stage must not contain Maven");
    assertFalse(runtime.contains("mvn "), "runtime stage must not invoke Maven");
    assertFalse(runtime.contains("gradle"), "runtime stage must not contain Gradle");
    assertFalse(runtime.contains("copy pom"), "runtime stage must not copy build sources");
  }
}
