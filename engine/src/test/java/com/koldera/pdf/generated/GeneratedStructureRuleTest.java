package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import com.koldera.pdf.rules.RuleDecision;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class GeneratedStructureRuleTest {

  private final GeneratedStructureRule rule = new GeneratedStructureRule();

  @Test
  void participatesOnlyInFixRunsOverPdfsThisPlatformGenerated() {
    assertEquals(RuleId.PDF_GENERATED_STRUCTURE_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.HTML_GENERATED)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
  }

  @Test
  void anUntaggedDocumentIsSkippedAndNotTouched() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED));
      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("not tagged"), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals(null, doc.getDocumentCatalog().getStructureTreeRoot());
    }
  }

  @Test
  void appliesListTableFigureBoxAndLinkNormalizersInOrderAndReportsTheirCounts() throws IOException {
    for (String fixture : new String[] {"generated-lists.pdf", "generated-tables.pdf", "generated-figures.pdf", "generated-links.pdf", "generated-forms.pdf",
        "generated-inline-blocks.pdf", "generated-irregular-tables.pdf", "generated-containers.pdf",
        "generated-headings.pdf", "generated-shaded-tables.pdf", "generated-link-runs.pdf", "generated-controls.pdf"}) {
      int[] expected = normalizeByHand(fixture);
      try (PDDocument doc = GeneratedStructure.fixture(fixture)) {
        RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED));
        assertTrue(decision.applicable(), decision.reason());
        WorkerResult.Action action = rule.apply(doc, decision);

        assertEquals(RuleId.PDF_GENERATED_STRUCTURE_V1, action.ruleId());
        assertEquals(ActionOutcome.APPLIED, action.outcome(), fixture + ": " + action.reason());
        String counts = "list items " + expected[0] + ", tables " + expected[1] + ", figures " + expected[2] + " (+" + expected[3] + " boxes), links " + expected[4]
            + ", artifacts " + expected[5] + ", table borders " + expected[6] + ", untagged text " + expected[7] + ", form fields " + expected[8]
            + ", form values " + expected[9] + ", paragraphs " + expected[10] + ", figure placements " + expected[11] + ", empty cells " + expected[12] + ", table attributes " + expected[13] + ", decorations " + expected[14] + ", lists " + expected[15] + ", ruby " + expected[16] + ", spans " + expected[17] + ", headings " + expected[18] + ", link paragraphs " + expected[19];
        if (expected[18] > 0) {
          assertTrue(action.reason().startsWith(counts + "; headings renumbered: "), fixture + ": " + action.reason());
          assertTrue(GeneratedStructureRule.headingsRenumbered(action).isPresent(), fixture);
        } else if (expected[7] == 0) {
          assertEquals(counts, action.reason(), fixture);
          assertTrue(GeneratedStructureRule.untaggedText(action).isEmpty(), fixture);
        } else if ("generated-controls.pdf".equals(fixture)) {
          // Only white space is painted outside the tags there (the spaces between the controls): no sample to quote.
          assertEquals(counts, action.reason(), fixture);
        } else {
          assertTrue(action.reason().startsWith(counts + "; untagged text sample: \""), fixture + ": " + action.reason());
        }
        // Every normalizer ran: a second pass has nothing left to change.
        assertEquals(0, ListNormalizer.normalize(doc), fixture);
        assertEquals(0, ListNormalizer.wrapOrphans(doc), fixture);
        assertEquals(0, TableDecoration.rowsAndLists(doc), fixture);
        assertEquals(0, TableDecoration.containers(doc), fixture);
        assertEquals(0, RubyNormalizer.normalize(doc), fixture);
        assertEquals(0, InlineBlocks.spans(doc), fixture);
        assertEquals(new HeadingLevels.Outcome(0, null), HeadingLevels.renumber(doc), fixture);
        assertEquals(new TableDecoration.Outcome(0, 0), TableDecoration.apply(doc), fixture);
        assertEquals(0, TableAttributes.merge(doc), fixture);
        assertEquals(0, TablePadding.clampRowSpans(doc), fixture);
        assertEquals(0, TablePadding.apply(doc), fixture);
        assertEquals(0, TableNormalizer.normalize(doc), fixture);
        assertEquals(0, FigureNormalizer.normalize(doc), fixture);
        assertEquals(0, LinkContents.apply(doc), fixture);
        assertEquals(new WidgetlessForms.Outcome(0, 0), WidgetlessForms.apply(doc), fixture);
        assertEquals(0, InlineBlocks.paragraphs(doc), fixture);
        assertEquals(0, LinkParagraphs.apply(doc), fixture);
        assertEquals(0, InlineBlocks.figurePlacement(doc), fixture);
        assertEquals(new UntaggedArtifacts.Outcome(0, 0, null), UntaggedArtifacts.apply(doc), fixture);
        // Nothing the PAC-only structure checks (and so the identifier) would object to is left.
        assertEquals(Map.of(), StructureRules.check(doc), fixture);
      }
    }
    assertEquals(7, normalizeByHand("generated-inline-blocks.pdf")[10], "inline-blocks fixture: seven NonStruct blocks of inline content");
    assertEquals(1, normalizeByHand("generated-inline-blocks.pdf")[11], "inline-blocks fixture: the <figure> is a block");
    assertEquals(4, normalizeByHand("generated-irregular-tables.pdf")[12], "irregular-tables fixture: four slots left free by the HTML");
    assertEquals(29, normalizeByHand("generated-irregular-tables.pdf")[13], "irregular-tables fixture: every one of its 29 cells had three Table attribute objects");
    int[] containers = normalizeByHand("generated-containers.pdf");
    assertEquals(List.of(3, 1, 2, 1, 0), List.of(containers[14], containers[15], containers[16], containers[17], containers[10]),
        "containers fixture: list, row and button backgrounds; one list around three items; two rubies; the button in a paragraph is a Span, no paragraph added");
    assertEquals(4, normalizeByHand("generated-headings.pdf")[18], "headings fixture: four of six headings renumbered");
    assertTrue(normalizeByHand("generated-lists.pdf")[0] > 0, "lists fixture has list items to normalize");
    assertTrue(normalizeByHand("generated-tables.pdf")[1] > 0, "tables fixture has tables to normalize");
    assertEquals(3, normalizeByHand("generated-tables.pdf")[1], "the three tables with a header row, the collapsed-border one included");
    assertEquals(1, normalizeByHand("generated-tables.pdf")[6], "tables fixture has one collapsed-border sequence");
    assertTrue(normalizeByHand("generated-figures.pdf")[2] > 0, "figures fixture has figures to normalize");
    assertTrue(normalizeByHand("generated-figures.pdf")[3] > 0, "figures fixture has figures to box");
    assertEquals(10, normalizeByHand("generated-links.pdf")[4], "links fixture has ten link annotations");
    assertEquals(2, normalizeByHand("generated-links.pdf")[5], "links fixture paints two decorative images untagged");
    assertEquals(6, normalizeByHand("generated-forms.pdf")[8], "forms fixture has six Form elements without widgets");
    assertEquals(1, normalizeByHand("generated-forms.pdf")[9], "forms fixture: the email placeholder is attributed to its field");
    assertTrue(normalizeByHand("generated-forms.pdf")[7] > 0, "forms fixture: the select's value and spaces stay untagged, then artifact");
  }

  @Test
  void untaggedTextIsCountedAndItsSampleCanBeReadBackFromTheReason() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-untagged-text.pdf")) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      GeneratedStructureRule.UntaggedText text = GeneratedStructureRule.untaggedText(action).orElseThrow();
      assertTrue(text.runs() >= 10, action.reason());
      assertEquals("Example Co.", text.sample());
      assertTrue(action.reason().endsWith("; untagged text sample: \"Example Co.\""), action.reason());
    }
  }

  @Test
  void shadedRowsLoseTheirBackgroundBeforeTheTableIsPaddedAndGrouped() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-shaded-tables.pdf")) {
      COSDictionary table = GeneratedStructure.elements(doc, "Table").get(0);
      assertEquals(List.of("TR", "TR", "TR", "TR", "TR"), GeneratedStructure.kidTypes(table), "fixture shape");
      assertEquals("mcid", GeneratedStructure.kidTypes((COSDictionary) GeneratedStructure.kids(table).get(0)).get(0), "fixture shape: the header row's background");

      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(List.of("THead", "TBody"), GeneratedStructure.kidTypes(table), "the shaded header row is recognised as a header row");
      for (COSDictionary row : GeneratedStructure.elements(doc, "TR")) {
        List<String> cells = GeneratedStructure.kidTypes(row);
        assertEquals(3, cells.size(), "every row padded to three cells: " + cells);
        assertTrue(cells.stream().allMatch(t -> t.equals("TD") || t.equals("TH")), cells.toString());
      }
    }
  }

  @Test
  void theHeadingChangesAreReadBackFromTheReasonBeforeTheSample() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-headings.pdf")) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("H2→H1 ×1, H3→H2 ×1, H4→H3 ×2", GeneratedStructureRule.headingsRenumbered(action).orElseThrow());
    }
    WorkerResult.Action withSample = new WorkerResult.Action(RuleId.PDF_GENERATED_STRUCTURE_V1, ActionOutcome.APPLIED,
        "list items 0, tables 0, figures 0 (+0 boxes), links 0, artifacts 0, table borders 0, untagged text 1, form fields 0, form values 0, headings 1;"
            + " headings renumbered: H2→H1 ×1; untagged text sample: \"; headings renumbered: fake\"", null, null);
    assertEquals("H2→H1 ×1", GeneratedStructureRule.headingsRenumbered(withSample).orElseThrow());
    WorkerResult.Action sampleOnly = new WorkerResult.Action(RuleId.PDF_GENERATED_STRUCTURE_V1, ActionOutcome.APPLIED,
        "list items 0, tables 0, figures 0 (+0 boxes), links 0, artifacts 0, table borders 0, untagged text 1, form fields 0, form values 0;"
            + " untagged text sample: \"; headings renumbered: H9→H1 ×1\"", null, null);
    assertTrue(GeneratedStructureRule.headingsRenumbered(sampleOnly).isEmpty(), "document text in the sample is never read as a change");
  }

  @Test
  void theSampleIsReadBackVerbatimEvenWhenItContainsQuotesOrSemicolons() {
    WorkerResult.Action action = new WorkerResult.Action(RuleId.PDF_GENERATED_STRUCTURE_V1, ActionOutcome.APPLIED,
        "list items 0, tables 0, figures 0 (+0 boxes), links 0, artifacts 0, table borders 0, untagged text 3, form fields 0, form values 0;"
            + " 1 table left unchanged: its own marked content could not be shown to be border painting only; untagged text sample: \"a \"b\"; c\"",
        null, null);
    assertEquals(new GeneratedStructureRule.UntaggedText(3, "a \"b\"; c"), GeneratedStructureRule.untaggedText(action).orElseThrow());
    WorkerResult.Action rolledBack = new WorkerResult.Action(RuleId.PDF_GENERATED_STRUCTURE_V1, ActionOutcome.ROLLED_BACK, "rolled back: " + action.reason(), null, null);
    assertTrue(GeneratedStructureRule.untaggedText(rolledBack).isEmpty(), "only an applied action counts");
    WorkerResult.Action noSample = new WorkerResult.Action(RuleId.PDF_GENERATED_STRUCTURE_V1, ActionOutcome.APPLIED,
        "list items 0, tables 0, figures 0 (+0 boxes), links 0, artifacts 0, table borders 0, untagged text 2, form fields 0, form values 0", null, null);
    assertEquals(new GeneratedStructureRule.UntaggedText(2, null), GeneratedStructureRule.untaggedText(noSample).orElseThrow());
  }

  @Test
  void theFullRuleLeavesEveryPageOfEveryGeneratedFixtureRenderingIdentically() throws IOException {
    // The rule rewrites content streams (table borders and untagged painting become /Artifact, a
    // retagged BDC loses its MCID); none of that may change what is painted.
    for (String fixture : new String[] {
      "generated-lists.pdf", "generated-tables.pdf", "generated-figures.pdf", "generated-links.pdf", "generated-annual-report.pdf", "generated-untagged-text.pdf",
      "generated-forms.pdf", "generated-inline-blocks.pdf", "generated-irregular-tables.pdf", "generated-containers.pdf",
      "generated-headings.pdf", "generated-shaded-tables.pdf", "generated-link-runs.pdf", "generated-controls.pdf"
    }) {
      try (PDDocument doc = GeneratedStructure.fixture(fixture)) {
        List<int[]> before = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
          before.add(render(doc, i));
        }
        WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));
        assertEquals(ActionOutcome.APPLIED, action.outcome(), fixture + ": " + action.reason());
        try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
          assertEquals(before.size(), after.getNumberOfPages(), fixture);
          for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(before.get(i), render(after, i), fixture + " page " + (i + 1) + " raster at 72 dpi unchanged");
          }
        }
      }
    }
  }

  @Test
  void aTaggedDocumentWithNothingToNormalizeIsSkippedNotApplied() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals(
          "nothing to normalize: list items 0, tables 0, figures 0 (+0 boxes), links 0, artifacts 0, table borders 0, untagged text 0, form fields 0, form values 0,"
              + " paragraphs 0, figure placements 0, empty cells 0, table attributes 0, decorations 0, lists 0, ruby 0, spans 0, headings 0, link paragraphs 0",
          action.reason());
    }
  }

  @Test
  void aTableWhoseOwnMarkedContentShowsTextIsLeftUnchangedAndTheReasonSaysSo() throws IOException {
    try (PDDocument doc = TableDecorationTest.syntheticTable("/Table <</MCID 0>> BDC BT /F1 12 Tf 10 10 Td (x) Tj ET EMC /TD <</MCID 1>> BDC 0 0 5 5 re f EMC", false)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.HTML_GENERATED)));
      assertEquals(
          "nothing to normalize: list items 0, tables 0, figures 0 (+0 boxes), links 0, artifacts 0, table borders 0, untagged text 0, form fields 0,"
              + " form values 0, paragraphs 0, figure placements 0, empty cells 0, table attributes 0, decorations 0, lists 0, ruby 0, spans 0, headings 0, link paragraphs 0; 1 table left unchanged: its own marked content could not be shown to be border painting only",
          action.reason());
      assertEquals(List.of("mcid", "TR"), GeneratedStructure.kidTypes(GeneratedStructure.elements(doc, "Table").get(0)));
    }
  }

  /**
   * The steps in the documented order on a fresh copy (list items, table borders, tables, figures, boxes, links, form fields,
   * paragraphs, figure placement, artifacts, ...); returns the per-step counts in the order the applied reason lists them.
   */
  private static int[] normalizeByHand(String fixture) throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture(fixture)) {
      int items = ListNormalizer.normalize(doc);
      int lists = ListNormalizer.wrapOrphans(doc);
      int borders = TableDecoration.apply(doc).sequences();
      int rowDecorations = TableDecoration.rowsAndLists(doc);
      int tableAttributes = TableAttributes.merge(doc) + TablePadding.clampRowSpans(doc);
      int cells = TablePadding.apply(doc);
      int tables = TableNormalizer.normalize(doc);
      int figures = FigureNormalizer.normalize(doc);
      int boxes = FigureBBox.apply(doc);
      int links = LinkContents.apply(doc);
      WidgetlessForms.Outcome forms = WidgetlessForms.apply(doc);
      int decorations = rowDecorations + TableDecoration.containers(doc);
      int spans = InlineBlocks.spans(doc);
      int linkParagraphs = LinkParagraphs.apply(doc);
      int paragraphs = InlineBlocks.paragraphs(doc);
      int placements = InlineBlocks.figurePlacement(doc);
      int ruby = RubyNormalizer.normalize(doc);
      int headings = HeadingLevels.renumber(doc).headings();
      UntaggedArtifacts.Outcome artifacts = UntaggedArtifacts.apply(doc);
      return new int[] {items, tables, figures, boxes, links, artifacts.painting(), borders, artifacts.text(), forms.fields(), forms.values(), paragraphs, placements, cells, tableAttributes, decorations, lists, ruby, spans, headings, linkParagraphs};
    }
  }

  private static int[] render(PDDocument doc, int pageIndex) throws IOException {
    BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, 72);
    BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    rgb.getGraphics().drawImage(image, 0, 0, null);
    return ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData().clone();
  }

  static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        null,
        null,
        null,
        origin,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
