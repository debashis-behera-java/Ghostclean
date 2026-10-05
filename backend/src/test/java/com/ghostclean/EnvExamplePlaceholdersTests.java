package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** Local-only {@code .env.example} ships placeholders only — never real secrets. */
class EnvExamplePlaceholdersTests {

  @Test
  void envExampleHasPlaceholdersOnly() throws Exception {
    Path env = Paths.get("..", ".env.example");
    if (!Files.exists(env)) {
      env = Paths.get(".env.example");
    }
    assertTrue(Files.exists(env), ".env.example must exist");

    String content = Files.readString(env);
    assertTrue(content.contains("APP_REGION=eu"), "APP_REGION=eu must be present");
    assertTrue(content.contains("TOKEN_ENCRYPTION_KEY="),
        "TOKEN_ENCRYPTION_KEY placeholder must be present");
    assertTrue(content.contains("JWT_SIGNING_KEY="),
        "JWT_SIGNING_KEY placeholder must be present");
    assertTrue(content.contains("VERIFICATION_MAIL_MODE="),
        "VERIFICATION_MAIL_MODE placeholder must be present");
    assertFalse(content.contains("BEGIN PRIVATE KEY"), "no PEM material allowed");
    assertFalse(content.contains("AKIA"), "no cloud access-key material allowed");
  }
}
