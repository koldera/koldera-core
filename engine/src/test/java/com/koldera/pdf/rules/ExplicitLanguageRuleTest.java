package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.IOException;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

public class ExplicitLanguageRuleTest {

  private final ExplicitLanguageRule rule = new ExplicitLanguageRule();

  @Test
  void idIsTheContractRuleId() {
    assertEquals(WorkerResult.RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, rule.id());
  }

  @Test
  void missingLangWithExplicitLanguageIsApplied() throws IOException {
    try (PDDocument doc = load(SyntheticPdfFactory.simpleUntagged())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX, "en-US"));
      assertTrue(decision.applicable(), decision.reason());
      assertNull(decision.before());
      assertEquals("en-US", decision.after());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome());
      assertEquals(WorkerResult.RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, action.ruleId());
      assertNull(action.before());
      assertEquals("en-US", action.after());
      assertEquals("en-US", doc.getDocumentCatalog().getLanguage());
      assertTrue(action.reason().toLowerCase().contains("human review"), "must not assert semantic correctness: " + action.reason());
    }
  }

  @Test
  void missingLangWithoutExplicitLanguageIsSkippedAndNothingIsGuessed() throws IOException {
    try (PDDocument doc = load(SyntheticPdfFactory.simpleUntagged())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX, null));
      assertFalse(decision.applicable());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertNull(doc.getDocumentCatalog().getLanguage());
      assertTrue(action.reason().contains("explicit"), action.reason());
    }
  }

  @Test
  void existingConflictingLanguageIsPreservedAndConflictExplained() throws IOException {
    byte[] bytes = SyntheticPdfFactory.build(SyntheticPdfFactory.Options.plain().language("pl-PL"));
    try (PDDocument doc = load(bytes)) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX, "en-US"));
      assertFalse(decision.applicable());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("pl-PL", action.before());
      assertEquals("pl-PL", doc.getDocumentCatalog().getLanguage());
      assertTrue(action.reason().contains("pl-PL") && action.reason().contains("en-US"), action.reason());
    }
  }

  @Test
  void alreadyFixedDocumentIsIdempotentlySkipped() throws IOException {
    byte[] bytes = SyntheticPdfFactory.build(SyntheticPdfFactory.Options.plain().language("en-US"));
    try (PDDocument doc = load(bytes)) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX, "en-US"));
      assertFalse(decision.applicable());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("en-US", doc.getDocumentCatalog().getLanguage());
      assertTrue(action.reason().contains("already"), action.reason());
    }
  }

  @Test
  void analyzeModeNeverApplies() throws IOException {
    try (PDDocument doc = load(SyntheticPdfFactory.simpleUntagged())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.ANALYZE, "en-US"));

      assertFalse(decision.applicable());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertNull(doc.getDocumentCatalog().getLanguage());
    }
  }

  @Test
  void blankExistingLangIsNotOverwritten() throws IOException {
    byte[] bytes = SyntheticPdfFactory.build(SyntheticPdfFactory.Options.plain().language(""));
    try (PDDocument doc = load(bytes)) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX, "en-US"));

      assertFalse(decision.applicable(), "an existing (even blank) /Lang is a conflict for humans to resolve");
      assertEquals("", doc.getDocumentCatalog().getLanguage());
    }
  }

  @Test
  void applyingANonApplicableDecisionDoesNotMutate() throws IOException {
    try (PDDocument doc = load(SyntheticPdfFactory.simpleUntagged())) {
      RuleDecision decision = RuleDecision.skip("test", null, null);

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertNull(doc.getDocumentCatalog().getLanguage());
    }
  }

  private static PDDocument load(byte[] bytes) throws IOException {
    return Loader.loadPDF(bytes);
  }

  public static WorkerRequest request(WorkerRequest.Mode mode, String explicitLanguage) {
    return new WorkerRequest(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        explicitLanguage,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
