package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.structure.ContentTagger;
import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.junit.jupiter.api.Test;

/** The layout grouping: blocks become paragraphs in reading order, running headers/footers and paths artifacts. */
class LayoutGroupingTest {

  @Test
  void aReportIsGroupedIntoParagraphsAndRunningArtifacts() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedReport(5))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(new LayoutGrouping.Counts(10, 0, 0, 0, 0, 10, 0, 5, 0, "Annual report 2025"), grouping.counts());
      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals(10, document.getKids().size());
    }
  }

  @Test
  void aStructuredArticleGetsHeadingsAndLists() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedStructuredArticle())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(H1,H2,P,P,L(LI(Lbl,LBody),LI(Lbl,LBody),LI(Lbl,LBody)),P,L(LI(LBody),LI(LBody),LI(LBody)),H2,P)", shape(document));
      assertEquals(new LayoutGrouping.Counts(4, 3, 2, 6, 0, 0, 0, 1, 0, null), grouping.counts());
    }
  }

  @Test
  void proseThatLooksLikeANestedItemStaysInItsItem() throws Exception {
    // "v." as a run of its own at a deeper x makes a nested "item"; single-letter prose rejoins item 1's LBody.
    byte[] pdf;
    try (PDDocument doc = new PDDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font font =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      float[][] at = {{72, 700}, {90, 686}, {110, 686}, {72, 672}, {72, 658}};
      String[] text = {"1. The first item of the list follows the case of Roe", "v.", "Wade held that it exists.", "2. Second item of the list.",
          "3. Third item of the list."};
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        for (int i = 0; i < text.length; i++) {
          cs.beginText();
          cs.setFont(font, 11);
          cs.newLineAtOffset(at[i][0], at[i][1]);
          cs.showText(text[i]);
          cs.endText();
        }
      }
      doc.save(out);
      pdf = out.toByteArray();
    }
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(L(LI(LBody),LI(LBody),LI(LBody)))", shape(document));
      assertEquals(3, grouping.counts().listItems());
      PDStructureElement firstItem = (PDStructureElement) ((PDStructureElement) document.getKids().get(0)).getKids().get(0);
      assertEquals("1. The first item of the list follows the case of Roe v. Wade held that it exists.", StructureText.textOf(doc, firstItem));
    }
  }

  /** The element types of a subtree, kids in parentheses. */
  static String shape(PDStructureElement element) {
    List<String> kids = new ArrayList<>();
    for (Object kid : element.getKids()) {
      if (kid instanceof PDStructureElement child) {
        kids.add(shape(child));
      }
    }
    return element.getStructureType() + (kids.isEmpty() ? "" : "(" + String.join(",", kids) + ")");
  }

  @Test
  void interleavedColumnsAreTaggedColumnByColumn() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedTwoColumnsInterleaved())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(1, grouping.counts().pagesReordered());
      assertEquals(0, grouping.counts().pagesKept());
      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals(2, document.getKids().size());
      // MCIDs are numbered in content order (L1 0, R1 1, L2 2, ...): the left column's P lists the even ones.
      assertEquals(List.of(0, 2, 4, 6, 8, 10), mcids((PDStructureElement) document.getKids().get(0)));
      assertEquals(List.of(1, 3, 5, 7, 9, 11), mcids((PDStructureElement) document.getKids().get(1)));
    }
  }

  @Test
  void anAmbiguousPageKeepsItsContentOrderLineByLine() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedTwoColumnsWithStraddlingBlock())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().pagesReordered());
      assertEquals(1, grouping.counts().pagesKept());
      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      List<Integer> order = new ArrayList<>();
      for (Object kid : document.getKids()) {
        order.addAll(mcids((PDStructureElement) kid));
      }
      assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), order, "the structure follows the content order");
    }
  }

  @Test
  void aSuperscriptInTheMiddleOfALineKeepsContentOrder() throws Exception {
    assertContentOrderKept(SyntheticPdfFactory.untaggedParagraphWithSuperscript(), 4);
  }

  @Test
  void aTwoRowLabelValueFormKeepsContentOrder() throws Exception {
    assertContentOrderKept(SyntheticPdfFactory.untaggedLabelValueForm(2), 4);
  }

  @Test
  void aFourRowInvoiceFormKeepsContentOrder() throws Exception {
    // Label column and value column each hold four lines: rows, not columns (aligned-pairs veto; no prose either).
    assertContentOrderKept(SyntheticPdfFactory.untaggedLabelValueForm(4), 8);
  }

  @Test
  void columnsWithAlignedParagraphGapsInContentOrderAreKept() throws Exception {
    assertContentOrderKept(SyntheticPdfFactory.untaggedTwoColumnsWithAlignedParagraphGaps(false), 12);
  }

  @Test
  void interleavedColumnsWithAlignedParagraphGapsAreReadColumnByColumn() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedTwoColumnsWithAlignedParagraphGaps(true))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(1, grouping.counts().pagesReordered());
      assertEquals(List.of(0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9, 11), structureOrder(doc), "the left column's paragraphs, then the right column's");
    }
  }

  @Test
  void aRepeatedNumberInTheBodyIsNotPageFurniture() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedRepeatedBodyLine(4, "12.50", false))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().runningArtifacts());
    }
  }

  @Test
  void numberedBodyLinesAreNotPageFurniture() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedRepeatedBodyLine(4, "Question", true))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().runningArtifacts());
    }
  }

  @Test
  void textTheBasicTaggerMarksAsPaginationStaysAnArtifact() throws Exception {
    // Each chapter header recurs on 3 of 6 pages: below the running-artifact share, but the basic tagger's band rule
    // (same text at the same place in the page band on at least three pages) makes it pagination; so must the inference.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedChapterHeaders())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(6, grouping.counts().paragraphs());
      for (int p = 0; p < 6; p++) {
        String content = new String(doc.getPage(p).getContents().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("\\s+", " ");
        assertTrue(content.matches(".*/Artifact <</Type /Pagination >> BDC \\(Chapter (one|two)\\) Tj EMC.*"), content);
      }
    }
  }

  @Test
  void anAuthorMarkedFooterIsNeverPageFurniture() throws Exception {
    // A footer that already carries an MCID on every page is author content: never an artifact, and no refusal.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithMarkedFooterOnEveryPage(4))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().runningArtifacts());
      assertEquals(8, grouping.counts().paragraphs());
    }
  }

  private static void assertContentOrderKept(byte[] pdf, int items) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().pagesReordered());
      assertEquals(1, grouping.counts().pagesKept());
      List<Integer> expected = new ArrayList<>();
      for (int k = 0; k < items; k++) {
        expected.add(k);
      }
      assertEquals(expected, structureOrder(doc), "the structure follows the content order");
    }
  }

  /** MCIDs of page 1 in structure order (depth first: the cells of a table row by row). */
  private static List<Integer> structureOrder(PDDocument doc) {
    PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
    List<Integer> order = new ArrayList<>();
    collectMcids(document, order);
    return order;
  }

  private static void collectMcids(PDStructureElement element, List<Integer> out) {
    for (Object kid : element.getKids()) {
      if (kid instanceof Integer mcid) {
        out.add(mcid);
      } else if (kid instanceof PDStructureElement child) {
        collectMcids(child, out);
      }
    }
  }

  @Test
  void aRuledTableIsWritten() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedRuledTable())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(Table(Caption,THead(TR(TH,TH,TH)),TBody(TR(TD,TD,TD),TR(TD,TD,TD))))", shape(document));
      assertEquals(1, grouping.counts().tables());
      assertEquals(0, grouping.counts().headings());
      assertEquals(0, grouping.counts().paragraphs());
      PDStructureElement table = (PDStructureElement) document.getKids().get(0);
      assertEquals("Table 1. Revenue", StructureText.textOf(doc, (PDStructureElement) table.getKids().get(0)));
      PDStructureElement headerRow = (PDStructureElement) ((PDStructureElement) table.getKids().get(1)).getKids().get(0);
      for (Object kid : headerRow.getKids()) {
        org.apache.pdfbox.cos.COSDictionary a = (org.apache.pdfbox.cos.COSDictionary) ((PDStructureElement) kid).getCOSObject()
            .getDictionaryObject(org.apache.pdfbox.cos.COSName.A);
        assertEquals("Table", a.getNameAsString(org.apache.pdfbox.cos.COSName.O));
        assertEquals("Column", a.getNameAsString(org.apache.pdfbox.cos.COSName.getPDFName("Scope")));
      }
      assertEquals("Region", StructureText.textOf(doc, (PDStructureElement) headerRow.getKids().get(0)));
    }
  }

  @Test
  void aRuledTablesSpaceRunsLeaveNoStrayParagraph() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedRuledTableWithBlankRow())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(Table(Caption,THead(TR(TH,TH,TH)),TBody(TR(TD,TD,TD),TR(TD,TD,TD))))", shape(document));
    }
  }

  @Test
  void anAlignedTableKeepsRowsUnderTable() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedAlignedTable())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(Table(TR(TD,TD,TD),TR(TD,TD,TD),TR(TD,TD,TD),TR(TD,TD,TD)))", shape(document));
      assertEquals(1, grouping.counts().tables());
      assertEquals(0, grouping.counts().fallbacks());
    }
  }

  // Blocks drawn one after another are not tables; the content order stays (base output).

  @Test
  void twoAddressBlocksSideBySideKeepContentOrder() throws Exception {
    assertNoTableContentOrder(TableDetectorTest.drawn(new Object[][] {
        {false, 11f, 72f, 700f, "Paul A. Grassi"}, {false, 11f, 72f, 686f, "Michael E. Garcia"}, {false, 11f, 72f, 672f, "Applied Cybersecurity Division"},
        {false, 11f, 72f, 658f, "Information Technology Laboratory"},
        {false, 11f, 330f, 700f, "James L. Fenton"}, {false, 11f, 330f, 686f, "Altmode Networks"}, {false, 11f, 330f, 672f, "Los Altos, Calif."}}));
  }

  @Test
  void twoColumnsOfShortProseLinesKeepContentOrder() throws Exception {
    assertNoTableContentOrder(TableDetectorTest.drawn(new Object[][] {
        {false, 11f, 72f, 700f, "The river rose"}, {false, 11f, 72f, 686f, "over the banks"}, {false, 11f, 72f, 672f, "and the town"},
        {false, 11f, 72f, 658f, "was flooded."},
        {false, 11f, 300f, 700f, "Help came fast"}, {false, 11f, 300f, 686f, "from the hills"}, {false, 11f, 300f, 672f, "and the valley"},
        {false, 11f, 300f, 658f, "by the evening."}}));
  }

  @Test
  void aShortSidebarNextToAProseColumnKeepsContentOrder() throws Exception {
    assertNoTableContentOrder(TableDetectorTest.drawn(new Object[][] {
        {false, 11f, 72f, 700f, "The survey covered every site in the"}, {false, 11f, 72f, 686f, "four regions and took place over the"},
        {false, 11f, 72f, 672f, "whole of the spring, with the results"}, {false, 11f, 72f, 658f, "checked twice by the regional teams."},
        {false, 11f, 330f, 700f, "Key facts"}, {false, 11f, 330f, 686f, "12 sites"}, {false, 11f, 330f, 672f, "4 regions"}}));
  }

  @Test
  void aThreeColumnIndexKeepsContentOrder() throws Exception {
    String[][] columns = {{"Apple, 3", "Banana, 7", "Cherry, 12", "Date, 15"}, {"Elder, 18", "Fig, 21", "Grape, 25", "Hazel, 28"},
        {"Iris, 30", "Juniper, 33", "Kale, 36", "Lime, 40"}};
    float[] xs = {72, 230, 390};
    List<Object[]> runs = new ArrayList<>();
    for (int c = 0; c < 3; c++) {
      for (int r = 0; r < 4; r++) {
        runs.add(new Object[] {false, 11f, xs[c], 700f - 14 * r, columns[c][r]});
      }
    }
    assertNoTableContentOrder(TableDetectorTest.drawn(runs.toArray(new Object[0][])));
  }

  @Test
  void aSlideWithTwoBulletedBoxesKeepsContentOrder() throws Exception {
    List<Object[]> runs = new ArrayList<>();
    String[][] boxes = {{"Faster start", "Fewer steps", "Lower cost"}, {"More users", "Better data", "New markets"}};
    float[] xs = {72, 320};
    for (int b = 0; b < 2; b++) {
      for (int i = 0; i < 3; i++) {
        runs.add(new Object[] {false, 14f, xs[b], 600f - 24 * i, "•"});
        runs.add(new Object[] {false, 14f, xs[b] + 18, 600f - 24 * i, boxes[b][i]});
      }
    }
    assertNoTableContentOrder(TableDetectorTest.drawn(runs.toArray(new Object[0][])));
  }

  @Test
  void aSlideWithTwoTitledBoxesKeepsContentOrder() throws Exception {
    assertNoTableContentOrder(TableDetectorTest.drawn(new Object[][] {
        {true, 14f, 72f, 620f, "Before"}, {false, 12f, 72f, 596f, "Manual forms"}, {false, 12f, 72f, 578f, "Paper archive"}, {false, 12f, 72f, 560f, "Long waits"},
        {true, 14f, 320f, 620f, "After"}, {false, 12f, 320f, 596f, "Online forms"}, {false, 12f, 320f, 578f, "Digital archive"}, {false, 12f, 320f, 560f, "Same day"}}));
  }

  @Test
  void twoProseColumnsDrawnLineByLineAcrossAreNoTable() throws Exception {
    // A producer that draws by baseline across both columns: the content order reads across, but a cell without
    // terminal punctuation continued by a lowercase cell below it in the same column is prose.
    try (PDDocument doc = Loader.loadPDF(TableDetectorTest.drawn(new Object[][] {
        {false, 11f, 72f, 700f, "The river rose"}, {false, 11f, 300f, 700f, "Help came fast"},
        {false, 11f, 72f, 686f, "over the banks"}, {false, 11f, 300f, 686f, "from the hills"},
        {false, 11f, 72f, 672f, "and the town"}, {false, 11f, 300f, 672f, "and the valley"},
        {false, 11f, 72f, 658f, "was flooded."}, {false, 11f, 300f, 658f, "by the evening."}}))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().tables());
      assertEquals(0, grouping.counts().fallbacks());
    }
  }

  @Test
  void aLowConfidenceCandidateDrawnColumnByColumnIsNoFallback() throws Exception {
    // The merged-run layout drawn column by column: the guards reject it before the fallback, so no row joins cells
    // across the gutter into one paragraph.
    List<Object[]> runs = new ArrayList<>();
    String[][] rows = {{"Apples", "12", "40%"}, {"Alpha      Beta", null, "30%"}, {"Plums", "6", "20%"}, {"Cherries", "3", "10%"}};
    float[] xs = {72, 200, 330};
    for (int c = 0; c < 3; c++) {
      for (int r = 0; r < 4; r++) {
        if (rows[r][c] != null) {
          runs.add(new Object[] {false, 11f, xs[c], 600f - 16 * r, rows[r][c].replace("      ", " ".repeat(40))});
        }
      }
    }
    assertNoTableContentOrder(TableDetectorTest.drawn(runs.toArray(new Object[0][])));
  }

  @Test
  void aLabelValueFormDrawnRowByRowStaysATable() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedLabelValueForm(4))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(1, grouping.counts().tables());
    }
  }

  /** No table, no fallback, and the structure lists every MCID in content order (0, 1, 2, …). */
  private static void assertNoTableContentOrder(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(0, grouping.counts().tables(), grouping.counts().toString());
      assertEquals(0, grouping.counts().fallbacks(), grouping.counts().toString());
      assertEquals(0, grouping.counts().pagesReordered());
      List<Integer> order = structureOrder(doc);
      List<Integer> expected = new ArrayList<>();
      for (int k = 0; k < order.size(); k++) {
        expected.add(k);
      }
      assertEquals(expected, order, "the structure follows the content order");
    }
  }

  @Test
  void aNumberedTablesCellsAreNeverFused() throws Exception {
    byte[] pdf = TableDetectorTest.page(11, new float[] {72, 100, 200}, 700, 16,
        new String[][] {{"1.", "2.", "3."}, {"Apples", "Pears", "Plums"}, {"12", "9", "6"}});
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(Table(TR(TD,TD,TD),TR(TD,TD,TD),TR(TD,TD,TD)))", shape(document));
      PDStructureElement firstRow = (PDStructureElement) ((PDStructureElement) document.getKids().get(0)).getKids().get(0);
      assertEquals("1.", StructureText.textOf(doc, (PDStructureElement) firstRow.getKids().get(0)));
      assertEquals(0, grouping.counts().lists());
    }
  }

  @Test
  void aMergedRunTableFallsBackToParagraphs() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedTableWithMergedRun())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals("Document(P,P,P,P)", shape(document));
      assertEquals(0, grouping.counts().tables());
      assertEquals(1, grouping.counts().fallbacks());
      assertEquals(4, grouping.counts().paragraphs());
      assertEquals("Alpha Beta 30%", StructureText.textOf(doc, (PDStructureElement) document.getKids().get(1)));
      assertTrue(StructureInferenceRule.describe(grouping.counts()).contains("fallbacks 1"), StructureInferenceRule.describe(grouping.counts()));
    }
  }

  @Test
  void aDocumentWhoseOnlyTextRepeatsKeepsItAsContent() throws Exception {
    // "Body text on page N." at the same place on every page is the only text: page furniture needs a page body.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithSharedTextFormOnEveryPage(3))) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false);
      ContentTagger.apply(doc, ContentTagger.analyze(doc, true, grouping));

      assertEquals(new LayoutGrouping.Counts(3, 0, 0, 0, 0, 0, 0, 3, 0, null), grouping.counts());
    }
  }

  @Test
  void textRunsThatCannotBeMatchedRefuseTheDocument() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      LayoutGrouping grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, false, page -> {
        Map<PDContentStream, List<TextRun>> runs = new LinkedHashMap<>(TextRuns.of(page));
        runs.replaceAll((stream, list) -> list.subList(0, list.size() - 1)); // one run missing
        return runs;
      });

      UnsupportedContentException e = assertThrows(UnsupportedContentException.class, () -> ContentTagger.analyze(doc, true, grouping));
      assertEquals("text runs could not be matched to the content", e.getMessage());
    }
  }

  @Test
  void workCapsRefuseTheDocumentWithTheirCounts() throws Exception {
    InferenceSettings d = InferenceSettings.DEFAULTS;
    InferenceSettings fewRuns = withCaps(d.maxPages(), 3);
    InferenceSettings noPages = withCaps(0, d.maxTextRuns());
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      assertEquals("7 text runs exceed the cap of 3",
          assertThrows(UnsupportedContentException.class, () -> ContentTagger.analyze(doc, true, new LayoutGrouping(fewRuns, false))).getMessage());
      assertEquals("1 pages exceed the cap of 0",
          assertThrows(UnsupportedContentException.class, () -> ContentTagger.analyze(doc, true, new LayoutGrouping(noPages, false))).getMessage());
    }
  }

  static InferenceSettings withCaps(int maxPages, int maxTextRuns) {
    InferenceSettings d = InferenceSettings.DEFAULTS;
    return new InferenceSettings(d.minTaggedTextCoverage(), d.maxLargestElementShare(), d.largestElementMinMcids(), maxPages, maxTextRuns,
        d.sameLineFactor(), d.blockGapFactor(), d.blockLeftEdgeFactor(), d.blockFontSizeChange(), d.runningMinPageShare(), d.runningMinPages(),
        d.runningPositionTolerance(), d.columnGapFactor(), d.columnMinLines(), d.headingSizeRatio(), d.headingMaxLines(), d.headingMaxChars(),
        d.listIndentTolerance(), d.listMinItems(), d.tableMinAlignedRows(), d.tableMinColumns(), d.tableColumnTolerance(), d.rulingMaxThickness(),
        d.captionMaxDistance(), d.minConfidence(), d.columnRowAlignedShare(), d.columnMinLineChars(), d.columnMinLineFill(),
        d.columnMaxGutterShare(), d.runningMaxLines(), d.runningMaxChars(), d.runningNumericMaxChars(), d.listLabelGapFactor(),
        d.superscriptRiseFactor(), d.listItemGapFactor(), d.headingBoldMaxChars(), d.headingBoldFollowFactor(), d.headingBoldRunMax(),
        d.headingMinLetterShare(), d.paragraphEndLineShare(), d.tableCellGapFactor(), d.tableRowGapFactor(), d.captionMaxLines());
  }

  private static List<Integer> mcids(PDStructureElement element) {
    List<Integer> out = new ArrayList<>();
    for (Object kid : element.getKids()) {
      if (kid instanceof Integer mcid) {
        out.add(mcid);
      }
    }
    return out;
  }
}
