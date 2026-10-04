package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.pipeline.JobWorkspace;
import com.koldera.pdf.pipeline.PdfProcessor;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UntaggedContentRuleTest {

  private final UntaggedContentRule rule = new UntaggedContentRule();

  @TempDir Path root;

  @Test
  void itTakesPartOnlyInFixForUploadsAndIsAStructureStep() {
    assertEquals(RuleId.PDF_UNTAGGED_CONTENT_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertTrue(PdfProcessor.STRUCTURE_STEPS.contains(RuleId.PDF_UNTAGGED_CONTENT_V1));
    assertFalse(rule.participates(request(Mode.FIX, Origin.UPLOAD), PdfProcessor.STRUCTURE_STEPS));
  }

  @Test
  void anUntaggedDocumentIsSkipped() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of());
      assertFalse(decision.applicable());
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("document is not tagged; untagged content is handled by the structure rule", action.reason());
    }
  }

  @Test
  void untaggedRulingLinesBecomeArtifacts() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedRules())) {
      String treeBefore = tree(doc);

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().startsWith("untagged painting marked as artifact 4, untagged text left as it is 0"), action.reason());
      assertEquals("untagged painting marked as artifact 4, untagged text left as it is 0, artifacts split out of tagged content 0,"
          + " artifacts split around tagged content 0, unreferenced MCIDs marked as artifact 0, streams left alone 0", action.reason());
      assertEquals(List.of("Tj:H1", "Tj:P", "S:Artifact", "S:Artifact", "S:Artifact", "f:Artifact"), painted(doc));
      assertEquals(treeBefore, tree(doc), "tree unchanged");
    }
  }

  @Test
  void untaggedTextIsLeftAsItIs() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedLines(9, 1))) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));

      assertTrue(action.reason().contains("untagged text left as it is 1"), action.reason());
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), "nothing was changed");
      List<String> painted = painted(doc);
      assertEquals("Tj:-", painted.get(painted.size() - 1), "the untagged Tj has no wrapper");
    }
  }

  @Test
  void nestingAndOrphansAreCountedInTheReason() throws Exception {
    byte[] input = ContentFixtures.page("/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Before) Tj ET /Artifact BMC 72 690 m 300 690 l S EMC"
        + " BT /F1 12 Tf 72 670 Td (After) Tj ET EMC /Span <</MCID 5>> BDC 72 650 m 300 650 l S EMC 100 100 50 50 re f", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("untagged painting marked as artifact 1, untagged text left as it is 0, artifacts split out of tagged content 1,"
          + " artifacts split around tagged content 0, unreferenced MCIDs marked as artifact 1, streams left alone 0", action.reason());
      assertEquals(List.of("Tj:P", "S:Artifact", "Tj:P", "S:Artifact", "f:Artifact"), painted(doc));
    }
  }

  @Test
  void anUnreferencedMcidInsideAnArtifactIsCountedAsUnreferenced() throws Exception {
    byte[] input = ContentFixtures.page("/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Tagged) Tj ET EMC"
        + " /Artifact BMC 72 690 m 300 690 l S /Span <</MCID 1>> BDC 72 650 m 300 650 l S EMC EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("untagged painting marked as artifact 0, untagged text left as it is 0, artifacts split out of tagged content 0,"
          + " artifacts split around tagged content 0, unreferenced MCIDs marked as artifact 1, streams left alone 0", action.reason());
    }
  }

  @Test
  void aRebuiltDocumentIsSkipped() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.taggedWithUntaggedLines(1, 9));
    Deque<ValidationOutcome> outcomes = new ArrayDeque<>(List.of(failed(Map.of("ISO 14289-1:2014 7.1-3", 9))));
    PdfProcessor processor = new PdfProcessor(new PdfPreflight(), (pdf, report, timeout) -> {
      try {
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"fake\":true}", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      ValidationOutcome scripted = outcomes.isEmpty() ? new ValidationOutcome(ValidationStatus.PASSED, Map.of(), Map.of(), null, null, null, "fake") : outcomes.poll();
      return new ValidationOutcome(scripted.status(), scripted.failedRuleCounts(), Map.of(), report, null, null, "fake");
    }, Main.productionRules());

    WorkerResult result = processor.process(request(Mode.FIX, Origin.UPLOAD), workspace);

    assertEquals(com.koldera.pdf.contracts.WorkerResult.Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertTrue(inferred.reason().startsWith("strategy tagged_unusable"), inferred.reason());
    WorkerResult.Action untagged = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_UNTAGGED_CONTENT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.SKIPPED, untagged.outcome(), untagged.reason());
    assertEquals("the tags were rebuilt from the page content in this run", untagged.reason());
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static ValidationOutcome failed(Map<String, Integer> rules) {
    return new ValidationOutcome(ValidationStatus.FAILED, rules, Map.of(), null, null, null, "fake");
  }

  /** Each painting/text operator of page 1 with the innermost marked-content tag around it ({@code -} for none). */
  static List<String> painted(PDDocument doc) throws IOException {
    Deque<String> tags = new ArrayDeque<>();
    List<Object> operands = new ArrayList<>();
    List<String> out = new ArrayList<>();
    for (Object token : new PDFStreamParser(doc.getPage(0)).parse()) {
      if (!(token instanceof Operator op)) {
        operands.add(token);
        continue;
      }
      switch (op.getName()) {
        case "BMC", "BDC" -> tags.push(((COSName) operands.get(0)).getName());
        case "EMC" -> tags.pop();
        case "f", "S", "Tj", "TJ", "Do" -> out.add(op.getName() + ":" + (tags.isEmpty() ? "-" : tags.peek()));
        default -> { }
      }
      operands.clear();
    }
    return out;
  }

  /** The structure tree as a string: element types with their /K, and the ParentTree slots. */
  private static String tree(PDDocument doc) {
    return ContentFixtures.parentTreeTypes(doc) + " " +((org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)
            doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0)).getKids().stream()
            .map(k -> k instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement e
                ? e.getStructureType() + e.getCOSObject().getDictionaryObject(COSName.K) : String.valueOf(k))
            .toList();
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
