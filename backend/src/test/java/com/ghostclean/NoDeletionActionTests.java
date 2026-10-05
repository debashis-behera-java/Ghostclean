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
 * Phase 5 executes zero deletions by construction; Phase 6 adds the
 * request/approval model with the same guarantee (describe, never
 * execute). These structural guards fail the build the moment the
 * intelligence/account/privacy surfaces gain: (a) mutation endpoints in
 * the trusted-registry package, (b) outbound HTTP clients capable of
 * submitting third-party requests, (c) mail-sending or browser-automation
 * capability. Legitimate HTTP adapters live in {@code oauth}/{@code mailbox}
 * and are out of scope by package.
 */
class NoDeletionActionTests {

  private static Path base(String... parts) throws Exception {
    Path base = Paths.get(String.join("/", parts));
    if (!Files.exists(base)) {
      base = Paths.get("backend/" + String.join("/", parts));
    }
    assertTrue(Files.exists(base), "sources must exist at " + base.toAbsolutePath());
    return base;
  }

  private static List<String> scan(Path root, String subpackage, List<String> needles)
      throws Exception {
    List<String> offenders = new ArrayList<>();
    Path start = root.resolve(subpackage);
    if (!Files.exists(start)) {
      return offenders;
    }
    try (Stream<Path> files = Files.walk(start)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        if (!file.toString().endsWith(".java")) {
          continue;
        }
        String content = Files.readString(file);
        for (String needle : needles) {
          if (content.contains(needle)) {
            offenders.add(root.relativize(file) + " contains " + needle);
          }
        }
      }
    }
    return offenders;
  }

  @Test
  void serviceRegistryPackageHasNoMutationEndpoints() throws Exception {
    Path main = base("src", "main", "java", "com", "ghostclean");
    List<String> offenders = scan(main, "services",
        List.of("@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping"));
    assertTrue(offenders.isEmpty(),
        "service registry must stay read-only: " + offenders);
  }

  @Test
  void intelligenceAndAccountCodeMakesNoOutboundHttpCalls() throws Exception {
    Path main = base("src", "main", "java", "com", "ghostclean");
    List<String> needles = List.of("RestClient", "RestTemplate", "WebClient",
        "HttpClient", "HttpURLConnection", "okhttp", "apache.http");
    List<String> offenders = new ArrayList<>();
    offenders.addAll(scan(main, "services", needles));
    offenders.addAll(scan(main, "account", needles));
    offenders.addAll(scan(main, "privacy", needles));
    assertTrue(offenders.isEmpty(),
        "Phase 5 must not gain outbound HTTP capability: " + offenders);
  }

  @Test
  void intelligenceAndAccountCodeHasNoMailOrAutomationCapability() throws Exception {
    Path main = base("src", "main", "java", "com", "ghostclean");
    List<String> needles = List.of("JavaMailSender", "SimpleMailMessage",
        "jakarta.mail", "selenium", "playwright", "HtmlUnit");
    List<String> offenders = new ArrayList<>();
    offenders.addAll(scan(main, "services", needles));
    offenders.addAll(scan(main, "account", needles));
    offenders.addAll(scan(main, "privacy", needles));
    assertTrue(offenders.isEmpty(),
        "Phase 5 must not gain send/automation capability: " + offenders);
  }

  @Test
  void noBackgroundDispatchMechanismExists() throws Exception {
    // Gate 2 must stay an explicit user action: no scheduler, async
    // executor, or event listener anywhere in main sources that could
    // auto-send after approval, scans, or enrichment.
    Path main = base("src", "main", "java", "com", "ghostclean");
    List<String> needles = List.of("@Scheduled", "@Async", "@EventListener",
        "TaskScheduler", "TaskExecutor", "EnableScheduling", "EnableAsync");
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(main)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        if (!file.toString().endsWith(".java")) {
          continue;
        }
        String content = Files.readString(file);
        for (String needle : needles) {
          if (content.contains(needle)) {
            offenders.add(main.relativize(file) + " contains " + needle);
          }
        }
      }
    }
    assertTrue(offenders.isEmpty(),
        "Approval must never auto-send via background mechanisms: " + offenders);
  }
}
