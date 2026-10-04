package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.generated.StructTree;
import com.koldera.pdf.pipeline.JobWorkspace;
import com.koldera.pdf.pipeline.PdfProcessor;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.report.FindingsBuilder;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaggedStructureRepairRuleTest {

  private final TaggedStructureRepairRule rule = new TaggedStructureRepairRule();

  @TempDir Path root;

  @Test
  void itTakesPartOnlyInFixForUploadsAndIsAStructureStep() {
    assertEquals(RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertTrue(PdfProcessor.STRUCTURE_STEPS.contains(RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1));
  }

  @Test
  void anUntaggedDocumentIsSkipped() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("document is not tagged", action.reason());
    }
  }

  @Test
  void aTreeRebuiltAfterInspectionIsSkipped() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("LI", "• Item"))))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of());
      doc.getDocumentCatalog().setStructureTreeRoot(new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("the tags were rebuilt from the page content in this run", action.reason());
    }
  }

  @Test
  void looseListItemsGetAListAndBodies() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("LI", "• First item"), Block.of("LI", "• Second item"))))) {
      WorkerResult.Action action = apply(doc);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().startsWith("list items 2, lists 1"), action.reason());
      assertEquals("list items 2, lists 1, empty cells 0, row spans 0, tables 0, figure boxes 0, empty elements 0, headings 0", action.reason());
      assertEquals("Document(L(LI(LBody), LI(LBody)))", shape(document(doc)));
      assertEquals(Map.of(), com.koldera.pdf.integrity.CopyIntegrity.inspect(doc).problems());
    }
  }

  @Test
  void headingsOfAnUploadAreRenumbered() throws Exception {
    try (PDDocument doc = Loader.loadPDF(headings())) {
      WorkerResult.Action action = apply(doc);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith("; headings renumbered: H2→H1 ×1, H4→H2 ×1"), action.reason());
      assertEquals("Document(H1, P, H2, P)", shape(document(doc)));
      assertEquals("H2→H1 ×1, H4→H2 ×1", TaggedStructureRepairRule.headingsRenumbered(action).orElseThrow());
    }
  }

  @Test
  void theProcessorReportsRenumberedUploadHeadingsForAPerson() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), headings());
    Deque<ValidationOutcome> outcomes = new ArrayDeque<>(List.of(failed(Map.of("ISO 14289-1:2014 7.4.2-1", 1))));
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

    assertEquals(WorkerResult.Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action repair = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, repair.outcome(), repair.reason());
    List<WorkerResult.Finding> renumbered = result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.HEADING_LEVELS_RENUMBERED)).toList();
    assertEquals(1, renumbered.size(), result.findings().toString());
    assertTrue(renumbered.get(0).needsHumanReview());
    assertNull(renumbered.get(0).resolution());
    assertEquals("Heading levels were renumbered so that the first heading is level 1 and no level is skipped (H2→H1 ×1, H4→H2 ×1); the order of the"
        + " headings and their text did not change. Check that the levels still match the document's structure; if not, correct the headings in the"
        + " source document and convert again.", renumbered.get(0).message());
  }

  @Test
  void aTreeThatUsesHIsNotRenumbered() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H", "Title"), Block.of("H3", "Part"))))) {
      WorkerResult.Action action = apply(doc);

      assertTrue(action.reason().endsWith("headings 0"), action.reason());
      assertEquals("Document(H, H3)", shape(document(doc)));
      assertTrue(TaggedStructureRepairRule.headingsRenumbered(action).isEmpty());
    }
  }

  @Test
  void roleMappedHeadingsStopTheRenumbering() throws Exception {
    // InDesign shape: Heading_1 → H1 through the /RoleMap next to raw H2/H3; renumbering the raw ones alone would demote them.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("Heading_1", "Title"), Block.of("H2", "Part"), Block.of("H3", "Detail")).roleMap(Map.of("Heading_1", "Heading", "Heading", "H1"))))) {
      WorkerResult.Action action = apply(doc);

      assertEquals("list items 0, lists 0, empty cells 0, row spans 0, tables 0, figure boxes 0, empty elements 0, headings 0"
          + "; headings not renumbered: role-mapped heading types (Heading_1)", action.reason());
      assertEquals("Document(Heading_1, H2, H3)", shape(document(doc)));
      assertTrue(TaggedStructureRepairRule.headingsRenumbered(action).isEmpty());
    }
  }

  @Test
  void aTypeMappedToHStopsTheRenumbering() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("Title", "Title"), Block.of("H3", "Part")).roleMap(Map.of("Title", "H"))))) {
      WorkerResult.Action action = apply(doc);

      assertTrue(action.reason().endsWith("headings 0; headings not renumbered: role-mapped heading types (Title)"), action.reason());
      assertEquals("Document(Title, H3)", shape(document(doc)));
    }
  }

  @Test
  void aStandardTypeMappedToAHeadingStopsTheRenumbering() throws Exception {
    // The check covers every element type, standard ones included: a P remapped to H1 is a heading a reader sees.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("P", "Title"), Block.of("H3", "Part")).roleMap(Map.of("P", "H1"))))) {
      WorkerResult.Action action = apply(doc);

      assertTrue(action.reason().endsWith("headings 0; headings not renumbered: role-mapped heading types (P)"), action.reason());
      assertEquals("Document(P, H3)", shape(document(doc)));
    }
  }

  @Test
  void aRoleMapCycleDoesNotHang() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("Loop", "Text"), Block.of("H2", "Part")).roleMap(Map.of("Loop", "Other", "Other", "Loop"))))) {
      WorkerResult.Action action = apply(doc);

      assertTrue(action.reason().endsWith("; headings renumbered: H2→H1 ×1"), action.reason());
    }
  }

  @Test
  void aTableWithAHeaderRowGetsTHeadAndTBody() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("TH", "Name"), Block.of("TH", "Value"), Block.of("TD", "a"), Block.of("TD", "1"))))) {
      PDStructureElement document = document(doc);
      List<Object> cells = new ArrayList<>(document.getKids());
      PDStructureElement head = StructTree.wrap(document, cells.subList(0, 2), "TR");
      PDStructureElement body = StructTree.wrap(document, cells.subList(2, 4), "TR");
      StructTree.wrap(document, List.of(head, body), "Table");
      assertEquals("Document(Table(TR(TH, TH), TR(TD, TD)))", shape(document));

      WorkerResult.Action action = apply(doc);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("list items 0, lists 0, empty cells 0, row spans 0, tables 1, figure boxes 0, empty elements 0, headings 0", action.reason());
      assertEquals("Document(Table(THead(TR(TH, TH)), TBody(TR(TD, TD))))", shape(document(doc)));
    }
  }

  @Test
  void aWellFormedTreeIsLeftAsItIs() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Title"), Block.of("P", "Body."))))) {
      WorkerResult.Action action = apply(doc);

      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals("list items 0, lists 0, empty cells 0, row spans 0, tables 0, figure boxes 0, empty elements 0, headings 0", action.reason());
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private WorkerResult.Action apply(PDDocument doc) {
    return rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD), Set.of()));
  }

  private static byte[] headings() throws IOException {
    return SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H2", "Introduction"), Block.of("P", "Text."), Block.of("H4", "Detail"), Block.of("P", "More.")));
  }

  private static PDStructureElement document(PDDocument doc) {
    return (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
  }

  /** Element types only, e.g. {@code Document(L(LI(LBody)))}; marked content and object references are left out. */
  static String shape(PDStructureNode node) {
    StringBuilder out = new StringBuilder(node instanceof PDStructureElement e ? e.getStructureType() : "Root");
    List<String> kids = new ArrayList<>();
    for (Object kid : node.getKids()) {
      if (kid instanceof PDStructureElement element) {
        kids.add(shape(element));
      }
    }
    if (!kids.isEmpty()) {
      out.append('(').append(String.join(", ", kids)).append(')');
    }
    return out.toString();
  }

  private static ValidationOutcome failed(Map<String, Integer> rules) {
    return new ValidationOutcome(ValidationStatus.FAILED, rules, Map.of(), null, null, null, "fake");
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
