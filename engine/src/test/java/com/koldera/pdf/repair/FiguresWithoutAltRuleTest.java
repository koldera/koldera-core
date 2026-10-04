package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.pipeline.PdfProcessor;
import com.koldera.pdf.rules.RuleDecision;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class FiguresWithoutAltRuleTest {

  private final FiguresWithoutAltRule rule = new FiguresWithoutAltRule();

  @Test
  void itTakesPartOnlyInFixForUploadsAndIsAStructureStep() {
    assertEquals(RuleId.PDF_FIGURES_WITHOUT_ALT_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertTrue(PdfProcessor.STRUCTURE_STEPS.contains(RuleId.PDF_FIGURES_WITHOUT_ALT_V1));
    assertFalse(rule.participates(request(Mode.FIX, Origin.UPLOAD), PdfProcessor.STRUCTURE_STEPS),
        "left out of the rerun without the structure steps");
  }

  @Test
  void anUntaggedInputIsInspectedAsApplicableBecauseThisRunMayTagIt() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of());
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("no Figure without alternative text", action.reason());
    }
  }

  @Test
  void theAppliedReasonCountsEveryFigureAndNamesThePages() throws Exception {
    byte[] input = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("Figure", "a"), Block.of("Figure", "b"), new Block("P", "Two", 2), new Block("Figure", "c", 2), new Block("Figure", "d", 2))
        .alt(3, "Map of the region"));
    try (PDDocument doc = Loader.loadPDF(input)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("figures without alternative text marked as artifact 3, retagged as paragraph 0 (they show text), kept with alternative text 1,"
          + " kept 0 (they hold an annotation or other tagged content); pages: 1×2, 2×1", action.reason());
      assertEquals(Optional.of(new TreeMap<>(Map.of(1, 2, 2, 1))), FiguresWithoutAltRule.artifactedPerPage(action));
    }
  }

  @Test
  void aDocumentWhoseFiguresAllHaveAlternativeTextIsSkipped() throws Exception {
    byte[] input = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Intro"), Block.of("Figure", "a")).alt(1, "Chart"));
    try (PDDocument doc = Loader.loadPDF(input)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("no Figure without alternative text", action.reason());
      assertEquals(Optional.empty(), FiguresWithoutAltRule.artifactedPerPage(action));
    }
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
