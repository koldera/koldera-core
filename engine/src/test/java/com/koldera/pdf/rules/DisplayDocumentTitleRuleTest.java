package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.junit.jupiter.api.Test;

class DisplayDocumentTitleRuleTest {

  private final DisplayDocumentTitleRule rule = new DisplayDocumentTitleRule();
  private final WorkerRequest safeFix = ExplicitLanguageRuleTest.request(WorkerRequest.Mode.SAFE_FIX, null);

  @Test
  void idIsTheContractRuleId() {
    assertEquals(WorkerResult.RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1, rule.id());
  }

  @Test
  void existingTitleWithDisplayDocTitleFalseIsApplied() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("Quarterly Report").displayDocTitle(false))) {
      RuleDecision decision = rule.inspect(doc, safeFix);
      assertTrue(decision.applicable(), decision.reason());
      assertEquals("false", decision.before());
      assertEquals("true", decision.after());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome());
      assertTrue(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
      assertEquals("Quarterly Report", doc.getDocumentInformation().getTitle(), "title itself is untouched");
      assertFalse(action.reason().contains("Quarterly"), "title text is not echoed into the report");
    }
  }

  @Test
  void existingTitleWithoutViewerPreferencesCreatesMinimalDictionary() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("Quarterly Report"))) {
      assertNull(doc.getDocumentCatalog().getViewerPreferences());
      RuleDecision decision = rule.inspect(doc, safeFix);
      assertTrue(decision.applicable(), decision.reason());
      assertNull(decision.before());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome());
      PDViewerPreferences prefs = doc.getDocumentCatalog().getViewerPreferences();
      assertTrue(prefs.displayDocTitle());
      assertEquals(1, prefs.getCOSObject().size(), "only DisplayDocTitle is added");
    }
  }

  @Test
  void unrelatedViewerPreferencesArePreserved() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("T").displayDocTitle(false).hideToolbar(true))) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, safeFix));

      assertEquals(ActionOutcome.APPLIED, action.outcome());
      PDViewerPreferences prefs = doc.getDocumentCatalog().getViewerPreferences();
      assertTrue(prefs.hideToolbar());
      assertTrue(prefs.displayDocTitle());
      assertEquals(2, prefs.getCOSObject().size());
    }
  }

  @Test
  void alreadyTrueIsIdempotentlySkipped() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("T").displayDocTitle(true))) {
      RuleDecision decision = rule.inspect(doc, safeFix);
      assertFalse(decision.applicable());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertTrue(action.reason().contains("already"), action.reason());
    }
  }

  @Test
  void missingTitleIsSkippedAndNoTitleIsInvented() throws IOException {
    try (PDDocument doc = load(Options.plain().displayDocTitle(false))) {
      RuleDecision decision = rule.inspect(doc, safeFix);
      assertFalse(decision.applicable());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertNull(doc.getDocumentInformation().getTitle());
      assertFalse(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
      assertTrue(action.reason().toLowerCase().contains("title"), action.reason());
    }
  }

  @Test
  void blankTitleIsSkipped() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("   ").displayDocTitle(false))) {
      RuleDecision decision = rule.inspect(doc, safeFix);

      assertFalse(decision.applicable());
      assertFalse(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
    }
  }

  @Test
  void analyzeModeNeverApplies() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("T").displayDocTitle(false))) {
      RuleDecision decision =
          rule.inspect(doc, ExplicitLanguageRuleTest.request(WorkerRequest.Mode.ANALYZE, null));

      assertFalse(decision.applicable());
      assertFalse(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
    }
  }

  @Test
  void nonBooleanDisplayDocTitleValueIsSkippedNotSilentlyReplaced() throws IOException {
    try (PDDocument doc = load(Options.plain().infoTitle("T").displayDocTitle(false))) {
      doc.getDocumentCatalog().getViewerPreferences().getCOSObject().setName(COSName.DISPLAY_DOC_TITLE, "yes");
      RuleDecision decision = rule.inspect(doc, safeFix);

      assertFalse(decision.applicable(), decision.reason());
    }
  }

  @Test
  void inFixModeATitleThatWillBeWrittenIsEnough() throws IOException {
    try (PDDocument doc = load(Options.plain())) {
      RuleDecision decision = rule.inspect(doc, TitleResolverTest.fix(null, "report.pdf"));
      assertTrue(decision.applicable(), decision.reason());
    }
  }

  @Test
  void inSafeFixModeATitleIsStillNeverInvented() throws IOException {
    try (PDDocument doc = load(Options.plain())) {
      RuleDecision decision = rule.inspect(doc, safeFix);
      assertFalse(decision.applicable());
    }
  }

  @Test
  void inFixModeWithNoResolvableTitleDisplayDocTitleIsLeftAlone() throws IOException {
    try (PDDocument doc = load(Options.plain())) {
      RuleDecision decision = rule.inspect(doc, TitleResolverTest.fix(null, null));
      assertFalse(decision.applicable(), decision.reason());
    }
  }

  private static PDDocument load(Options options) throws IOException {
    return Loader.loadPDF(SyntheticPdfFactory.build(options));
  }
}
