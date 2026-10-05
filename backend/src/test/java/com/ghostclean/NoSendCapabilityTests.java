package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1 contains no provider mailbox-send capability by construction
 * (ADR-006/ADR-014): no provider send scopes, no mail sender beans, no SMTP
 * usage anywhere under {@code backend/src/main} — except the single approved
 * transactional verification-mail seam ({@code auth/}: Resend or Gmail SMTP
 * senders behind {@code VerificationEmailSender}, selected by provider id).
 * Phase 4 additionally forbids every Gmail write surface
 * (send/compose/modify/delete/trash/batch variants). Fails the build the
 * moment such code appears outside the approved seam.
 */
class NoSendCapabilityTests {

  @Test
  void noSendScopesOrMailSendersInMainSources() throws Exception {
    Path base = Paths.get("src", "main");
    if (!Files.exists(base)) {
      base = Paths.get("backend", "src", "main");
    }
    assertTrue(Files.exists(base), "main sources must exist at " + base.toAbsolutePath());

    // Deliberately narrow: provider send-scope identifiers and mail APIs.
    // (Split into char arrays to keep this test file itself free of the
    // literal scope strings being policed.)
    // OAuth mailbox-write scopes stay banned everywhere (Gmail stays
    // read-only at the API level). SMTP mail APIs are banned everywhere
    // except the approved verification-mail seam (auth/), which carries
    // account-recovery links only under explicit provider selection.
    List<String> forbiddenEverywhere = List.of(
        "gmail" + ".send",
        "gmail" + ".modify",
        "gmail" + ".compose",
        "mail.google.com/",
        "Mail" + ".Send");
    List<String> mailApisOutsideSeam = List.of(
        "JavaMailSender",
        "SimpleMailMessage",
        "jakarta.mail");

    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(base)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        String content = Files.readString(file);
        String relative = base.relativize(file).toString().replace('\\', '/');
        boolean inApprovedSeam = relative.contains("/auth/");
        for (String needle : forbiddenEverywhere) {
          if (content.contains(needle)) {
            offenders.add(relative + " contains " + needle);
          }
        }
        if (!inApprovedSeam) {
          for (String needle : mailApisOutsideSeam) {
            if (content.contains(needle)) {
              offenders.add(relative + " contains " + needle);
            }
          }
        }
      }
    }
    assertTrue(offenders.isEmpty(),
        "V1 must not contain send capability outside the approved verification-mail seam: "
            + offenders);
  }
}
