package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.structure.ContentTagger.PageItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** Ruled and aligned tables from rulings and text runs; header rows, captions and confidence. */
class TableDetectorTest {

  @Test
  void aRuledGridWithABoldHeaderRow() throws Exception {
    List<TableDetector.DetectedTable> tables = detect(SyntheticPdfFactory.untaggedRuledTable());

    assertEquals(1, tables.size(), tables.toString());
    TableDetector.DetectedTable table = tables.get(0);
    assertEquals(List.of(List.of("Region", "2024", "2025"), List.of("North", "10", "12"), List.of("South", "8", "9")), texts(table));
    assertEquals(1, table.headerRows());
    assertEquals(1.0, table.confidence());
    assertNotNull(table.caption());
    assertEquals("Table 1. Revenue", table.caption().text());
    assertTrue(table.captionFirst());
    assertEquals(72.0, table.box().getLowerLeftX(), 0.5);
    assertEquals(640.0, table.box().getLowerLeftY(), 0.5);
    assertEquals(522.0, table.box().getUpperRightX(), 0.5);
    assertEquals(700.0, table.box().getUpperRightY(), 0.5);
  }

  @Test
  void anAlignedTableWithoutHeader() throws Exception {
    List<TableDetector.DetectedTable> tables = detect(SyntheticPdfFactory.untaggedAlignedTable());

    assertEquals(1, tables.size(), tables.toString());
    TableDetector.DetectedTable table = tables.get(0);
    assertEquals(List.of(List.of("Apples", "12", "40%"), List.of("Pears", "9", "30%"), List.of("Plums", "6", "20%"), List.of("Cherries", "3", "10%")),
        texts(table));
    assertEquals(0, table.headerRows());
    assertEquals(0.8, table.confidence());
    assertEquals(null, table.caption());
  }

  @Test
  void aRunAcrossAColumnFallsBack() throws Exception {
    List<TableDetector.DetectedTable> tables = detect(SyntheticPdfFactory.untaggedTableWithMergedRun());

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(0.3, tables.get(0).confidence());
    assertEquals(4, tables.get(0).rows().size());
  }

  @Test
  void proseParagraphsAreNoTable() throws Exception {
    assertEquals(List.of(), detect(SyntheticPdfFactory.untaggedStructuredArticle()));
    assertEquals(List.of(), detect(SyntheticPdfFactory.untaggedReport(1)));
  }

  @Test
  void twoColumnsOfProseOnSharedBaselinesAreNoTable() throws Exception {
    // Both columns on the same baselines look like rows of two cells; lines of prose in both columns are not a table.
    String[] left = {"The first column opens with a line", "of prose that runs on to a second", "and a third line before it ends in"};
    String[] right = {"The second column has its own text", "whose lines sit on the same baselines", "as the lines of the first column do"};
    assertEquals(List.of(), detect(page(11, new float[] {72, 320}, 700, 14, new String[][] {left, right})));
  }

  @Test
  void aNumberedListWithWideIndentsIsNoTable() throws Exception {
    // "1." and its text are separate runs far apart: a list, not two columns of cells.
    String[] markers = {"1.", "2.", "3."};
    String[] items = {"First step of the task", "Second step of the task", "Third step of the task"};
    assertEquals(List.of(), detect(page(11, new float[] {72, 108}, 700, 14, new String[][] {markers, items})));
  }

  @Test
  void aNumberedTableKeepsItsNumberCellApart() throws Exception {
    // PageLayout joins a bare marker to text up to 4 × size away; a table's "1." cell is its own cell.
    String[] numbers = {"1.", "2.", "3."};
    String[] fruit = {"Apples", "Pears", "Plums"};
    String[] counts = {"12", "9", "6"};
    List<TableDetector.DetectedTable> tables = detect(page(11, new float[] {72, 100, 200}, 700, 16, new String[][] {numbers, fruit, counts}));

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(List.of(List.of("1.", "Apples", "12"), List.of("2.", "Pears", "9"), List.of("3.", "Plums", "6")), texts(tables.get(0)));
  }

  @Test
  void aCentredBoldHeaderOverLeftAlignedColumnsIsTheHeaderRow() throws Exception {
    // As in Word tables: header cells centred over their columns start left of the body cells.
    byte[] pdf = drawn(new Object[][] {
        {true, 10f, 150f, 592f, "Section Name"}, {true, 10f, 367f, 592f, "Normative/Informative"},
        {false, 12f, 96f, 566f, "1. Purpose"}, {false, 12f, 392f, 566f, "Informative"},
        {false, 12f, 96f, 540f, "2. Introduction"}, {false, 12f, 392f, 540f, "Informative"},
        {false, 12f, 96f, 514f, "3. Definitions"}, {false, 12f, 394f, 514f, "Normative"}});
    List<TableDetector.DetectedTable> tables = detect(pdf);

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(List.of("Section Name", "Normative/Informative"), texts(tables.get(0)).get(0));
    assertEquals(4, tables.get(0).rows().size());
    assertEquals(1, tables.get(0).headerRows());
  }

  @Test
  void aWrappedCellLineStaysInItsCell() throws Exception {
    byte[] pdf = drawn(new Object[][] {
        {false, 11f, 72f, 700f, "IAL1"}, {false, 11f, 200f, 700f, "Attributes are self-asserted"},
        {false, 11f, 200f, 687f, "or treated as self-asserted."},
        {false, 11f, 72f, 668f, "IAL2"}, {false, 11f, 200f, 668f, "Remote or in-person proofing"},
        {false, 11f, 72f, 649f, "IAL3"}, {false, 11f, 200f, 649f, "In-person proofing"}});
    List<TableDetector.DetectedTable> tables = detect(pdf);

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(List.of(List.of("IAL1", "Attributes are self-asserted or treated as self-asserted."), List.of("IAL2", "Remote or in-person proofing"),
        List.of("IAL3", "In-person proofing")), texts(tables.get(0)));
  }

  @Test
  void aParagraphLineAcrossTheColumnsEndsTheTable() throws Exception {
    // A line right under the rows that covers the gutter is prose, not a wrapped cell.
    byte[] pdf = drawn(new Object[][] {
        {false, 11f, 72f, 700f, "Apples"}, {false, 11f, 200f, 700f, "12"},
        {false, 11f, 72f, 684f, "Pears"}, {false, 11f, 200f, 684f, "9"},
        {false, 11f, 72f, 668f, "Plums"}, {false, 11f, 200f, 668f, "6"},
        {false, 11f, 72f, 655f, "A closing sentence that runs on across both columns of the table."}});
    List<TableDetector.DetectedTable> tables = detect(pdf);

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(3, tables.get(0).rows().size());
    assertEquals(List.of("Plums", "6"), texts(tables.get(0)).get(2));
  }

  @Test
  void spaceRunsNeitherMakeCellsNorBreakTheHeader() throws Exception {
    // Word paints lone space runs at cell starts and after cell text; they join the cell they sit in.
    byte[] pdf = drawn(new Object[][] {
        {true, 12f, 78f, 470f, "Impact"}, {false, 12f, 250f, 470f, " "}, {true, 12f, 321f, 470f, "1"}, {true, 12f, 407f, 470f, "2"},
        {false, 12f, 78f, 445f, "Inconvenience"}, {false, 12f, 313f, 445f, "Low"}, {false, 12f, 399f, 445f, "Mod"},
        {false, 12f, 78f, 420f, "Financial loss"}, {false, 12f, 313f, 420f, "Low"}, {false, 12f, 399f, 420f, "Mod"}, {false, 12f, 425f, 420f, " "}});
    List<TableDetector.DetectedTable> tables = detect(pdf);

    assertEquals(1, tables.size(), tables.toString());
    TableDetector.DetectedTable table = tables.get(0);
    assertEquals(List.of(List.of("Impact", "1", "2"), List.of("Inconvenience", "Low", "Mod"), List.of("Financial loss", "Low", "Mod")), texts(table));
    assertEquals(1, table.headerRows());
    assertEquals(11, table.rows().stream().flatMap(List::stream).mapToInt(c -> c.itemIndexes().size()).sum(), "the space runs are in cells");
  }

  @Test
  void aBulletListFollowedByANumberedHeadingIsNoTable() throws Exception {
    // nist p29: two bullet items with their text at a tab, then the section heading "4.4.2 Relying Parties" in step
    // (the bullets end where "4.4.2" ends, so the first column looks right-aligned).
    byte[] pdf = drawn(new Object[][] {
        {false, 10f, 93f, 698f, "•"}, {false, 12f, 108f, 698f, "OpenID Connect claims are specified using JSON."},
        {false, 10f, 93f, 657f, "•"}, {false, 12f, 108f, 657f, "Kerberos tickets allow a ticket-granting authority."},
        {true, 11f, 72f, 616f, "4.4.2"}, {true, 11f, 108f, 616f, "Relying Parties"}});
    assertEquals(List.of(), detect(pdf));
  }

  @Test
  void aRuledGridKeepsTheSpaceRunsOfARowWithoutText() throws Exception {
    List<TableDetector.DetectedTable> tables = detect(SyntheticPdfFactory.untaggedRuledTableWithBlankRow());

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(3, tables.get(0).rows().size(), "the row without visible text is dropped");
    assertEquals(11, tables.get(0).items().size(), "its space run joins the nearest cell (9 cell texts, 1 space, 1 caption)");
  }

  @Test
  void anEmptyRuledCellStaysAnEmptyCell() throws Exception {
    List<TableDetector.DetectedTable> tables = detect(SyntheticPdfFactory.untaggedRuledTableWithEmptyCell());

    assertEquals(1, tables.size(), tables.toString());
    assertEquals(List.of("South", "8", ""), texts(tables.get(0)).get(2));
  }

  @Test
  void aCaptionNeedsALabelAfterTheWord() {
    for (String caption : List.of("Table 1. Revenue", "Таблиця 2 – Доходи", "Table A.2 Abbreviations", "TABLE 3: Costs", "Tab. 4 Costs",
        "Table II. Results", "Табл. 5. Витрати", "Table 2-1 Normative and Informative Sections", "Table 7")) {
      assertTrue(TableDetector.isCaption(caption), caption);
    }
    for (String prose : List.of("Table 2 shows that costs rose.", "table of contents", "Tables and figures", "Table 1 shows", "Tablespoon")) {
      assertFalse(TableDetector.isCaption(prose), prose);
    }
  }

  @Test
  void aCaptionBetweenTwoTablesGoesToTheTableBelowIt() throws Exception {
    List<Object[]> runs = new ArrayList<>();
    for (int r = 0; r < 3; r++) {
      runs.add(new Object[] {false, 11f, 72f, 700f - 16 * r, "North " + r});
      runs.add(new Object[] {false, 11f, 200f, 700f - 16 * r, String.valueOf(10 + r)});
    }
    runs.add(new Object[] {false, 11f, 72f, 650f, "Table 2. Costs"});
    for (int r = 0; r < 3; r++) {
      runs.add(new Object[] {false, 11f, 72f, 630f - 16 * r, "South " + r});
      runs.add(new Object[] {false, 11f, 200f, 630f - 16 * r, String.valueOf(20 + r)});
    }
    List<TableDetector.DetectedTable> tables = detect(drawn(runs.toArray(new Object[0][])));

    assertEquals(2, tables.size(), tables.toString());
    assertEquals(null, tables.get(0).caption());
    assertEquals("Table 2. Costs", tables.get(1).caption().text());
    assertTrue(tables.get(1).captionFirst());
  }

  @Test
  void aTableOfContentsIsNoTable() throws Exception {
    // Entries with dot leaders and right-aligned page numbers line up like two columns; they are a TOC, not a table.
    byte[] pdf = drawn(new Object[][] {
        {false, 12f, 72f, 700f, "5.4 Risk Acceptance ............................"}, {false, 12f, 500f, 700f, "22"},
        {false, 12f, 72f, 686f, "5.5 Digital Identity Statement ...................."}, {false, 12f, 500f, 686f, "23"},
        {false, 12f, 72f, 672f, "6 Selecting Assurance Levels ....................."}, {false, 12f, 500f, 672f, "25"}});
    assertEquals(List.of(), detect(pdf));
  }

  /** One page of Helvetica runs {bold, size, x, y, text}, drawn in the given order. */
  static byte[] drawn(Object[][] runs) throws Exception {
    try (PDDocument doc = new PDDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font regular =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      org.apache.pdfbox.pdmodel.font.PDType1Font bold =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        for (Object[] run : runs) {
          cs.beginText();
          cs.setFont((Boolean) run[0] ? bold : regular, (Float) run[1]);
          cs.newLineAtOffset((Float) run[2], (Float) run[3]);
          cs.showText((String) run[4]);
          cs.endText();
        }
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  /** One page: column {@code c} of {@code columns} drawn at x {@code xs[c]}, row r at baseline {@code top − pitch × r}, row by row. */
  static byte[] page(float size, float[] xs, float top, float pitch, String[][] columns) throws Exception {
    try (PDDocument doc = new PDDocument(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font font =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        for (int r = 0; r < columns[0].length; r++) {
          for (int c = 0; c < columns.length; c++) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(xs[c], top - pitch * r);
            cs.showText(columns[c][r]);
            cs.endText();
          }
        }
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  static List<TableDetector.DetectedTable> detect(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      PageItems page = LayoutFixtures.pages(doc).get(0);
      Map<PDContentStream, List<TextRun>> runs = TextRuns.of(page.page());
      List<LayoutBlock> blocks = PageLayout.blocks(page, runs, InferenceSettings.DEFAULTS);
      return TableDetector.detect(page, blocks, runs, InferenceSettings.DEFAULTS);
    }
  }

  /** Each cell's text: its runs joined by one space. */
  static List<List<String>> texts(TableDetector.DetectedTable table) {
    List<List<String>> out = new ArrayList<>();
    for (List<TableDetector.Cell> row : table.rows()) {
      List<String> cells = new ArrayList<>();
      for (TableDetector.Cell cell : row) {
        cells.add(PageLayout.normalize(String.join(" ", cell.runs().stream().map(TextRun::text).toList())));
      }
      out.add(cells);
    }
    return out;
  }
}
