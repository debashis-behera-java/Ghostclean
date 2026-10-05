package com.ghostclean;

import com.ghostclean.detection.ml.BedrockMlScorer;
import com.ghostclean.detection.ml.CompositeScore;
import com.ghostclean.detection.ml.CompositeScorer;
import com.ghostclean.detection.ml.DetectionMlProperties;
import com.ghostclean.detection.ml.MlScoringRequest;
import com.ghostclean.detection.ml.MlScoringResult;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Phase 13 architecture boundary for the AI scorer (R7).
 *
 * <p>The scorer stays isolated: it may depend on {@code detection} itself
 * plus Spring/JDK infrastructure, but never on OAuth internals, deletion or
 * dispatch services, database repositories, credential storage, or any
 * background-execution mechanism. The imports above pin the planned
 * production API; the rules below pin the dependency direction.
 *
 * <p>Expected initial state: RED — the {@code detection.ml} production types
 * do not exist yet.
 */
@AnalyzeClasses(
    packages = "com.ghostclean",
    importOptions = ImportOption.DoNotIncludeTests.class)
class MlArchitectureTests {

  // Compile-time pins: these references fail until Phase D creates the API.
  @SuppressWarnings("unused")
  private static final Class<?>[] ML_API = {
      BedrockMlScorer.class,
      CompositeScorer.class,
      CompositeScore.class,
      DetectionMlProperties.class,
      MlScoringRequest.class,
      MlScoringResult.class,
  };

  @ArchTest
  static final ArchRule mlNeverDependsOnPrivilegedPackages = ArchRuleDefinition.noClasses()
      .that().resideInAPackage("com.ghostclean.detection.ml..")
      .should().dependOnClassesThat()
      .resideInAnyPackage(
          "com.ghostclean.oauth..",
          "com.ghostclean.deletion..",
          "com.ghostclean.privacy..",
          "com.ghostclean.account..",
          "com.ghostclean.workflow..",
          "com.ghostclean.notification..",
          "com.ghostclean.legal..",
          "..repository..",
          "..repositories..");

  @ArchTest
  static final ArchRule mlHasNoBackgroundExecution = ArchRuleDefinition.noClasses()
      .that().resideInAPackage("com.ghostclean.detection.ml..")
      .should().dependOnClassesThat()
      .belongToAnyOf(
          org.springframework.scheduling.annotation.Scheduled.class,
          org.springframework.scheduling.annotation.Async.class,
          org.springframework.context.event.EventListener.class,
          java.util.concurrent.ExecutorService.class,
          java.util.concurrent.Executors.class,
          org.springframework.scheduling.TaskScheduler.class,
          org.springframework.core.task.TaskExecutor.class);

  @ArchTest
  static final ArchRule mlHasNoMailOrAutomationCapability = ArchRuleDefinition.noClasses()
      .that().resideInAPackage("com.ghostclean.detection.ml..")
      .should().dependOnClassesThat()
      .resideInAnyPackage(
          "jakarta.mail..",
          "org.seleniumhq..",
          "com.microsoft.playwright..");
}
