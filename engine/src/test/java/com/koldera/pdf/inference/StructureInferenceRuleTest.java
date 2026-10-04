package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.rules.BasicStructureRule;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.rules.RuleDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Test;

/** {@code PDF_STRUCTURE_INFERRED_V1}: the §3.2 strategy per upload, with content-order tagging as the structure. */
class StructureInferenceRuleTest {

  private final StructureInferenceRule rule = new StructureInferenceRule(InferenceSettings.DEFAULTS);

  @Test
  void anUntaggedUploadIsTaggedWithTheStrategyReason() throws Exception {
    List<String> basicTypes;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      BasicStructureRule basic = new BasicStructureRule();
      WorkerResult.Action action = basic.apply(doc, basic.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      basicTypes = types(doc.getDocumentCatalog().getStructureTreeRoot());
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(RuleId.PDF_STRUCTURE_INFERRED_V1, action.ruleId());
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("strategy untagged: no StructTreeRoot; structure is built from the page content; inferred paragraphs 3, headings 0, lists 0,"
          + " list items 0, tables 0, running artifacts 0, pages reordered 0, pages kept in content order 1, fallbacks 0", action.reason());
      assertEquals(basicTypes, types(doc.getDocumentCatalog().getStructureTreeRoot()));
    }
  }

  @Test
  void aSharedFormUploadIsTagged() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithSharedTextFormOnEveryPage(3))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("shared forms 3 (page furniture 3)"), action.reason());
      assertEquals(List.of("Document", "P", "P", "P"), types(doc.getDocumentCatalog().getStructureTreeRoot()));
    }
  }

  @Test
  void aReportGetsParagraphsAndPageFurniture() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedReport(5))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(List.of("Document", "P", "P", "P", "P", "P", "P", "P", "P", "P", "P"), types(doc.getDocumentCatalog().getStructureTreeRoot()));
      assertTrue(action.reason().contains("running artifacts 10"), action.reason());
      assertTrue(action.reason().endsWith("; running artifact sample: \"Annual report 2025\""), action.reason());
      for (int p = 0; p < 5; p++) {
        String content = new String(doc.getPage(p).getContents().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("\\s+", " ");
        assertTrue(content.contains("BT /F1 9 Tf 72 800 Td /Artifact <</Type /Pagination >> BDC (Annual report 2025) Tj EMC ET"), content);
        assertTrue(content.contains("BT /F1 9 Tf 290 30 Td /Artifact <</Type /Pagination >> BDC (Page " + (p + 1) + ") Tj EMC ET"), content);
      }
      LayoutGrouping.Counts counts = StructureInferenceRule.inferred(action).orElseThrow();
      assertEquals(new LayoutGrouping.Counts(10, 0, 0, 0, 0, 10, 0, 5, 0, "Annual report 2025"), counts);
      WorkerResult.Finding finding = new FindingsBuilder().structureInferred(counts).build().get(0);
      assertEquals(FindingsBuilder.STRUCTURE_INFERRED, finding.ruleId());
      assertTrue(finding.needsHumanReview());
      assertEquals("Structure was inferred from the page layout: 10 paragraphs, 0 headings, 0 lists (0 items), 0 tables; 10 running headers and footers"
          + " were marked as page furniture (artifact), for example \"Annual report 2025\"; 0 pages were put into column reading order, 5 kept their"
          + " content order; 0 detected structures fell back to paragraphs because the evidence was weak. Check the headings, lists, tables and"
          + " reading order against the document.", finding.message());
    }
  }

  @Test
  void aDocumentAboveTheTextRunCapFallsBackToBasicTagsWithReason() throws Exception {
    StructureInferenceRule capped = new StructureInferenceRule(LayoutGroupingTest.withCaps(InferenceSettings.DEFAULTS.maxPages(), 3));
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      RuleDecision decision = capped.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = capped.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("inference skipped: 7 text runs exceed the cap of 3; basic tags in content order"), action.reason());
      assertTrue(action.reason().contains("tagged 3 paragraphs, 1 figures"), action.reason());
      assertTrue(StructureInferenceRule.inferred(action).isEmpty());
      assertEquals(List.of("Document", "P", "P", "Figure", "P"), types(doc.getDocumentCatalog().getStructureTreeRoot()));
    }
  }

  @Test
  void aProgrammingErrorInTheGroupingFailsTheRule() throws Exception {
    StructureInferenceRule broken = new StructureInferenceRule(InferenceSettings.DEFAULTS, page -> {
      throw new IllegalStateException("bug");
    });
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      WorkerResult.Action action = broken.apply(doc, broken.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));

      assertEquals(ActionOutcome.FAILED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("IllegalStateException"), action.reason());
    }
  }

  /** The scratch carry check does not hide a programming error: the rule fails at apply as on every other path. */
  @Test
  void aProgrammingErrorInTheGroupingFailsTheRuleForDescribedFiguresToo() throws Exception {
    StructureInferenceRule broken = new StructureInferenceRule(InferenceSettings.DEFAULTS, page -> {
      throw new IllegalStateException("bug");
    });
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(SyntheticPdfFactory.DescribedImages.figure("Sales by region, 2025")), null))) {
      RuleDecision decision = broken.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = broken.apply(doc, decision);

      assertEquals(ActionOutcome.FAILED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("IllegalStateException"), action.reason());
    }
  }

  @Test
  void anUnreadableLayoutFallsBackToBasicTags() throws Exception {
    StructureInferenceRule unreadable = new StructureInferenceRule(InferenceSettings.DEFAULTS, page -> {
      throw new java.io.IOException("damaged font");
    });
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      WorkerResult.Action action = unreadable.apply(doc, unreadable.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("; inference skipped: the page layout could not be read (IOException); basic tags in content order"), action.reason());
    }
  }

  @Test
  void inferenceIsDeterministic() throws Exception {
    assertTrue(java.util.Arrays.equals(inferred(SyntheticPdfFactory.untaggedTwoColumnsInterleaved()), inferred(SyntheticPdfFactory.untaggedTwoColumnsInterleaved())));
  }

  private byte[] inferred(byte[] input) throws Exception {
    try (PDDocument doc = Loader.loadPDF(input); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      StructureInferenceRule fresh = new StructureInferenceRule(InferenceSettings.DEFAULTS);
      WorkerResult.Action action = fresh.apply(doc, fresh.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("pages reordered 1"), action.reason());
      com.koldera.pdf.integrity.CopyWriter.save(doc, out);
      return out.toByteArray();
    }
  }

  @Test
  void aUsableTaggedUploadIsSkippedAndItsTreeUntouched() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertFalse(decision.applicable());
      assertTrue(decision.reason().startsWith("strategy tagged_usable:"), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("StructTreeRoot present", action.before());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject());
    }
  }

  @Test
  void anUnusableTaggedUploadIsRebuilt() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9))) {
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      PDStructureElement oldDocument = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      COSDictionary oldParagraph = ((PDStructureElement) oldDocument.getKids().get(0)).getCOSObject();
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject(), "inspect works on a scratch copy");
      assertEquals("Document rebuilt from content", decision.after());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().startsWith("strategy tagged_unusable: tagged text coverage 10.0 % (< 90 %)"), action.reason());
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      assertFalse(rootBefore == root.getCOSObject(), "a new tree");
      List<String> types = types(root);
      assertEquals("Document", types.get(0));
      assertEquals(List.of("P", "P", "P", "P", "P", "P", "P", "P", "P", "P"), types.subList(1, types.size()));
      for (Object kid : ((PDStructureElement) root.getKids().get(0)).getKids()) {
        assertFalse(((PDStructureElement) kid).getCOSObject() == oldParagraph, "no element of the old tree is reused");
      }
      // Every MCID in the content is one the new tree numbered: 0..9 in content order, each once.
      String content;
      try (var in = doc.getPage(0).getContents()) {
        content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
      }
      List<String> mcids = new ArrayList<>();
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("/MCID (\\d+)").matcher(content);
      while (m.find()) {
        mcids.add(m.group(1));
      }
      assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"), mcids, content);
    }
  }

  @Test
  void anUnusableTaggedUploadWithFormFieldsKeepsItsTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9))) {
      org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget widget = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget();
      widget.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(72, 72, 100, 20));
      doc.getPage(0).getAnnotations().add(widget);
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable());
      assertEquals(
          "strategy tagged_unusable: tagged text coverage 10.0 % (< 90 %); the existing tags are discarded on the copy and structure is built from"
              + " the page content; the tags are kept because the document has form fields, which are not re-tagged",
          decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject());
    }
  }

  /** A described Figure over text covers no image, so its description cannot be carried and the tags are kept. */
  @Test
  void anUnusableTaggedUploadWithADescribedFigureOverTextKeepsItsTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(withFirstElementAs(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9), "Figure", org.apache.pdfbox.cos.COSName.ALT,
        "Sales by region, 2025", null))) {
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable());
      assertEquals(
          "strategy tagged_unusable: tagged text coverage 10.0 % (< 90 %); the existing tags are discarded on the copy and structure is built from"
              + " the page content; the tags are kept because figures in them have alternative text that could not be carried to the rebuilt tags",
          decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals(decision.reason(), action.reason());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject());
    }
  }

  @Test
  void aTypeRoleMappedToFigureWithActualTextAlsoKeepsTheTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(withFirstElementAs(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9), "Chart", org.apache.pdfbox.cos.COSName.ACTUAL_TEXT,
        "Sales by region, 2025", java.util.Map.of("Chart", "Diagram", "Diagram", "Figure")))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable(), decision.reason());
      assertTrue(decision.reason().endsWith(CARRY_REFUSED), decision.reason());
    }
  }

  static final String CARRY_REFUSED = "; the tags are kept because figures in them have alternative text that could not be carried to the rebuilt tags";

  /** The rebuilt Figure over the same image carries the description, and the decoration step keeps it. */
  @Test
  void aDescribedFigureOverOneImageIsRebuiltWithItsAlternativeText() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(SyntheticPdfFactory.DescribedImages.figure("Sales by region, 2025")), null))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith("; alternative text carried to 1 rebuilt figure"), action.reason());
      List<COSDictionary> figures = figures(doc);
      assertEquals(1, figures.size());
      assertEquals("Sales by region, 2025", figures.get(0).getString(COSName.ALT));
      assertFalse(figures.get(0).containsKey(COSName.ACTUAL_TEXT));
      assertTrue(StructureInferenceRule.inferred(action).isPresent(), "the counts still parse: " + action.reason());
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), "PAC-only structure checks");

      com.koldera.pdf.repair.FiguresWithoutAltRule decoration = new com.koldera.pdf.repair.FiguresWithoutAltRule();
      WorkerResult.Action decorated = decoration.apply(doc, decoration.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));
      assertEquals(ActionOutcome.SKIPPED, decorated.outcome(), decorated.reason());
      assertEquals(1, figures(doc).size(), decorated.reason());
      assertEquals("Sales by region, 2025", figures(doc).get(0).getString(COSName.ALT));
    }
  }

  /** Case (b): two described images, each description on the Figure over its own image (told apart by the layout box). */
  @Test
  void twoDescribedImagesKeepTheirOwnDescriptions() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(SyntheticPdfFactory.DescribedImages.figure("Left chart"), SyntheticPdfFactory.DescribedImages.figure("Right chart")), null))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith("; alternative text carried to 2 rebuilt figures"), action.reason());
      java.util.Map<String, String> altByX = new java.util.TreeMap<>();
      for (COSDictionary figure : figures(doc)) {
        COSDictionary layout = (COSDictionary) figure.getDictionaryObject(COSName.A);
        float x = ((org.apache.pdfbox.cos.COSNumber) ((org.apache.pdfbox.cos.COSArray) layout.getDictionaryObject(COSName.BBOX)).get(0)).floatValue();
        altByX.put(String.valueOf(Math.round(x)), figure.getString(COSName.ALT));
      }
      assertEquals(java.util.Map.of("72", "Left chart", "322", "Right chart"), altByX);
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), "PAC-only structure checks");
    }
  }

  /** Case (c): one described Figure over two images becomes two Figures: not 1:1, so nothing is rebuilt. */
  @Test
  void aDescribedFigureSplitOverTwoRebuiltFiguresKeepsTheTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(new SyntheticPdfFactory.DescribedImages("Figure", "Two charts", null, null, 2, false)), null))) {
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable(), decision.reason());
      assertTrue(decision.reason().endsWith(CARRY_REFUSED), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject());
    }
  }

  /** Case (d): /ActualText (and /Lang) alone is carried as it is. */
  @Test
  void aFigureWithOnlyActualTextAndLanguageIsCarried() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(new SyntheticPdfFactory.DescribedImages("Figure", null, "Umsatz nach Region", "de-DE", 1, false)), null))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      COSDictionary figure = figures(doc).get(0);
      assertEquals("Umsatz nach Region", figure.getString(COSName.ACTUAL_TEXT));
      assertEquals("de-DE", figure.getString(COSName.LANG));
      assertFalse(figure.containsKey(COSName.ALT));
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), "PAC-only structure checks");
    }
  }

  /** Case (e): a type the /RoleMap maps to Figure (transitively) is carried like a Figure. */
  @Test
  void aRoleMappedChartIsCarried() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(new SyntheticPdfFactory.DescribedImages("Chart", "Sales by region, 2025", null, null, 1, false)),
        java.util.Map.of("Chart", "Diagram", "Diagram", "Figure")))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("Sales by region, 2025", figures(doc).get(0).getString(COSName.ALT));
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), "PAC-only structure checks");
    }
  }

  /** Case (f): an image inside a form XObject drawn once, described through an MCR with /Stm, is carried. */
  @Test
  void aDescribedImageInsideAFormDrawnOnceIsCarried() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(new SyntheticPdfFactory.DescribedImages("Figure", "Chart in a form", null, null, 1, true)), null))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith("; alternative text carried to 1 rebuilt figure"), action.reason());
      assertEquals("Chart in a form", figures(doc).get(0).getString(COSName.ALT));
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), "PAC-only structure checks");
    }
  }

  /** An inline image (BI … EI) is an image item like an image XObject: its description is carried. */
  @Test
  void aDescribedInlineImageIsCarried() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(new SyntheticPdfFactory.DescribedImages("Figure", "Inline chart", null, null, 1, false, true, 1)), null))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith("; alternative text carried to 1 rebuilt figure"), action.reason());
      assertEquals("Inline chart", figures(doc).get(0).getString(COSName.ALT));
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), "PAC-only structure checks");
    }
  }

  /** A described form drawn twice becomes one Figure per draw: its images are no longer in exactly one Figure, so the tags are kept. */
  @Test
  void aDescribedFormDrawnTwiceKeepsTheTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(new SyntheticPdfFactory.DescribedImages("Figure", "Chart in a form", null, null, 1, true, false, 2)), null))) {
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable(), decision.reason());
      assertTrue(decision.reason().endsWith(CARRY_REFUSED), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject());
    }
  }

  /** Figures of an inferred upload tree are block-level (/Placement /Block), so the PAC-only placement check is clean. */
  @Test
  void anUntaggedUploadsInferredFigureIsBlockPlaced() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      COSDictionary figure = figures(doc).get(0);
      COSDictionary layout = (COSDictionary) figure.getDictionaryObject(COSName.A);
      assertEquals(COSName.getPDFName("Block"), layout.getDictionaryObject(COSName.getPDFName("Placement")));
      assertFalse(com.koldera.pdf.generated.StructureRules.check(doc).containsKey(com.koldera.pdf.generated.StructureRules.FIGURE_PLACEMENT));
    }
  }

  /**
   * Descriptions of elements other than Figures survive the rebuild as marked-content properties around exactly the same
   * content: the Span's /ActualText, the Formula's /Alt and /Lang on one /Span sequence, the paragraph's /Lang, the soft-hyphen
   * Span's /ActualText. Marker-only: glyph text is identical; extracted text differs only where a kept /ActualText replaces glyphs.
   */
  @Test
  void descriptionsOfOtherElementsSurviveTheRebuildAsMarkedContentProperties() throws Exception {
    byte[] pdf = SyntheticPdfFactory.taggedUnusableWithDescribedText(false);
    String glyphsBefore;
    String textBefore;
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      glyphsBefore = PdfPreflight.extractGlyphText(doc);
      textBefore = PdfPreflight.extractText(doc);
    }
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith("; descriptions of 4 elements other than figures kept as marked-content properties"), action.reason());
      assertFalse(types(doc.getDocumentCatalog().getStructureTreeRoot()).contains("Formula"), "the tree was rebuilt");
      List<Object> tokens = new org.apache.pdfbox.pdfparser.PDFStreamParser(doc.getPage(0)).parse();
      assertEquals(List.of(List.of("\u0001")), enclosedTexts(tokens, COSName.ACTUAL_TEXT, "fi"));
      assertEquals(List.of(List.of("E = mc", "2")), enclosedTexts(tokens, COSName.ALT, "E equals m c squared"));
      assertEquals(List.of(List.of("E = mc", "2")), enclosedTexts(tokens, COSName.LANG, "en-GB"));
      assertEquals(List.of(List.of("Ein deutscher Satz.")), enclosedTexts(tokens, COSName.LANG, "de"));
      assertEquals(List.of(List.of("-")), enclosedTexts(tokens, COSName.ACTUAL_TEXT, "\u00AD"));
      assertEquals(glyphsBefore, PdfPreflight.extractGlyphText(doc), "marker-only: the glyph text is identical");
      String textAfter = PdfPreflight.extractText(doc);
      // The soft hyphen replaces the painted "-" (and the text stripper, like a reader, does not show it).
      assertTrue(textBefore.contains("A regu-"), textBefore);
      assertEquals(textBefore.replace("A regu-", "A regu"), textAfter, "only the kept replacement text changed the extracted text");
    }
  }

  /** A described element whose content is not one contiguous run (another element's content in between) keeps the tags. */
  @Test
  void aDescribedElementWhoseContentIsScatteredKeepsTheTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedText(true))) {
      COSDictionary rootBefore = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable(), decision.reason());
      assertTrue(decision.reason().endsWith("; " + StructureInferenceRule.DESCRIBED_OTHERS), decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, decision).outcome());
      assertSame(rootBefore, doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject());
    }
  }

  /** For every marked-content sequence whose inline properties have {@code key} = {@code value}: the strings shown (Tj) inside it, in order. */
  private static List<List<String>> enclosedTexts(List<Object> tokens, COSName key, String value) {
    List<List<String>> out = new ArrayList<>();
    List<int[]> open = new ArrayList<>();
    int depth = 0;
    Object previous = null;
    for (Object token : tokens) {
      if (token instanceof org.apache.pdfbox.contentstream.operator.Operator op) {
        switch (op.getName()) {
          case "BDC" -> {
            depth++;
            if (previous instanceof COSDictionary props && props.getDictionaryObject(key) instanceof org.apache.pdfbox.cos.COSString s
                && value.equals(s.getString())) {
              open.add(new int[] {depth, out.size()});
              out.add(new ArrayList<>());
            }
          }
          case "BMC" -> depth++;
          case "EMC" -> {
            if (!open.isEmpty() && open.get(open.size() - 1)[0] == depth) {
              open.remove(open.size() - 1);
            }
            depth--;
          }
          case "Tj" -> {
            for (int[] sequence : open) {
              out.get(sequence[1]).add(new String(((org.apache.pdfbox.cos.COSString) previous).getBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
          }
          default -> {
            // other operators show no text
          }
        }
      }
      previous = token;
    }
    return out;
  }

  /** The Figure elements of the document's structure tree, depth first. */
  private static List<COSDictionary> figures(PDDocument doc) {
    List<COSDictionary> out = new ArrayList<>();
    java.util.Deque<Object> todo = new java.util.ArrayDeque<>();
    todo.push(doc.getDocumentCatalog().getStructureTreeRoot());
    while (!todo.isEmpty()) {
      Object node = todo.pop();
      List<Object> kids = node instanceof PDStructureTreeRoot root ? root.getKids() : node instanceof PDStructureElement e ? e.getKids() : List.of();
      if (node instanceof PDStructureElement e && "Figure".equals(e.getStructureType())) {
        out.add(e.getCOSObject());
      }
      for (int i = kids.size() - 1; i >= 0; i--) {
        todo.push(kids.get(i));
      }
    }
    return out;
  }

  @Test
  void aFigureWithBlankAlternativeTextDoesNotKeepTheTags() throws Exception {
    try (PDDocument doc = Loader.loadPDF(withFirstElementAs(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9), "Figure", org.apache.pdfbox.cos.COSName.ALT, "  ", null))) {
      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertTrue(decision.applicable(), decision.reason());
      assertEquals(ActionOutcome.APPLIED, rule.apply(doc, decision).outcome());
    }
  }

  /** The fixture with its first element (the one holding MCID 0) retyped, given {@code key} = {@code text}, and an optional /RoleMap. */
  private static byte[] withFirstElementAs(byte[] pdf, String type, org.apache.pdfbox.cos.COSName key, String text, java.util.Map<String, String> roleMap)
      throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      PDStructureElement first = (PDStructureElement) ((PDStructureElement) root.getKids().get(0)).getKids().get(0);
      first.getCOSObject().setName(org.apache.pdfbox.cos.COSName.S, type);
      first.getCOSObject().setString(key, text);
      if (roleMap != null) {
        COSDictionary map = new COSDictionary();
        roleMap.forEach((from, to) -> map.setName(from, to));
        root.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.ROLE_MAP, map);
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void anUnusableTaggedUploadWhoseTagsCannotBeStrippedKeepsThem() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9))) {
      // An extra content stream that opens marked content and never closes it.
      org.apache.pdfbox.pdmodel.common.PDStream extra = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
      try (java.io.OutputStream out = extra.createOutputStream()) {
        out.write("/X BMC\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      org.apache.pdfbox.cos.COSArray contents = new org.apache.pdfbox.cos.COSArray();
      contents.add(doc.getPage(0).getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.CONTENTS));
      contents.add(extra.getCOSObject());
      doc.getPage(0).getCOSObject().setItem(org.apache.pdfbox.cos.COSName.CONTENTS, contents);

      RuleDecision decision = rule.inspect(doc, request(Mode.FIX, Origin.UPLOAD));

      assertFalse(decision.applicable());
      assertTrue(decision.reason().startsWith("strategy tagged_unusable: tagged text coverage 10.0 % (< 90 %)"), decision.reason());
      assertTrue(decision.reason().endsWith("; the tags could not be rebuilt (existing marked content is not balanced; the tags are kept), so they are kept"),
          decision.reason());
    }
  }

  @Test
  void theRuleDoesNotParticipateOutsideFixUploads() {
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.SAFE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
  }

  @Test
  void theBasicTaggerTakesPartInAFixUploadOnlyInTheLadderRunWithoutTheStructureSteps() {
    BasicStructureRule basic = new BasicStructureRule();
    assertFalse(basic.participates(request(Mode.FIX, Origin.UPLOAD), java.util.Set.of()));
    assertTrue(basic.participates(request(Mode.FIX, Origin.UPLOAD), java.util.EnumSet.of(RuleId.PDF_STRUCTURE_INFERRED_V1)));
    assertTrue(basic.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD), java.util.Set.of()));
    assertTrue(basic.participates(request(Mode.FIX, Origin.HTML_GENERATED), java.util.Set.of()));
    assertFalse(basic.participates(request(Mode.SAFE_FIX, Origin.UPLOAD), java.util.Set.of()));
  }

  /** Element types in document order, depth first. */
  private static List<String> types(PDStructureTreeRoot root) {
    List<String> out = new ArrayList<>();
    for (Object kid : root.getKids()) {
      collect(kid, out);
    }
    return out;
  }

  private static void collect(Object node, List<String> out) {
    if (node instanceof PDStructureElement element) {
      out.add(element.getStructureType());
      for (Object kid : element.getKids()) {
        collect(kid, out);
      }
    }
  }

  static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
