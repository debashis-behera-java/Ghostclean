package com.ghostclean;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Module-boundary guards (ADR-001, ADR-004): no dependency cycles, and the
 * OAuth module stays independent of scanner/mailbox/detection.
 */
@AnalyzeClasses(
    packages = "com.ghostclean",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTests {

  @ArchTest
  static final ArchRule noCyclesBetweenModules =
      slices().matching("com.ghostclean.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule oauthIndependentOfScanningPipeline =
      ArchRuleDefinition.noClasses()
          .that().resideInAPackage("com.ghostclean.oauth..")
          .should().dependOnClassesThat()
          .resideInAnyPackage(
              "com.ghostclean.scanner..",
              "com.ghostclean.mailbox..",
              "com.ghostclean.detection..");
}
