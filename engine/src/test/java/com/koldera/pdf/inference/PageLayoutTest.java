package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lines from text runs, blocks from lines, in content order. */
class PageLayoutTest {

  @Test
  void linesAndBlocks() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedReport(1)).get(0);

    assertEquals(4, blocks.size(), blocks.toString());
    LayoutBlock header = blocks.get(0);
    assertEquals("Annual report 2025", header.text());
    assertEquals(1, header.lines());
    assertEquals(72.0, header.x0(), 0.01);
    assertEquals(800.0, header.y0(), 0.01);
    assertEquals(9.0, header.bodySize(), 0.01);
    assertEquals(1, header.page());
    assertEquals("The first page opens with a paragraph that continues on a second line.", blocks.get(1).text());
    assertEquals(2, blocks.get(1).lines());
    assertEquals(686.0, blocks.get(1).y0(), 0.01);
    assertEquals(11.0, blocks.get(1).bodySize(), 0.01);
    assertEquals(2, blocks.get(1).itemIndexes().size());
    assertEquals(2, blocks.get(2).lines());
    assertEquals("Another paragraph on the first page ends the body text here.", blocks.get(2).text());
    assertEquals("Page 1", blocks.get(3).text());
    assertEquals(290.0, blocks.get(3).x0(), 0.01);
    assertEquals(30.0, blocks.get(3).y0(), 0.01);
  }

  @Test
  void interleavedColumnsFormOneBlockPerColumn() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedTwoColumnsInterleaved()).get(0);

    assertEquals(2, blocks.size(), blocks.toString());
    assertEquals(6, blocks.get(0).lines());
    assertEquals(72.0, blocks.get(0).x0(), 0.01);
    assertEquals(List.of(0, 2, 4, 6, 8, 10), blocks.get(0).itemIndexes());
    assertEquals(6, blocks.get(1).lines());
    assertEquals(320.0, blocks.get(1).x0(), 0.01);
  }

  @Test
  void proseLinesThatStartLikeAMarkerStayInTheirParagraph() throws Exception {
    // Reviewer probes: "v. Wade", "– even in the north –", "p. 45" continue a line that does not end the paragraph.
    String[][] probes = {{"The court relied on the earlier decision of Roe", "v. Wade held that the right exists in law."},
        {"Rainfall rose sharply in every single region", "– even in the north – and fell again later."},
        {"For the full argument and its sources, see", "p. 45 of the second report of the committee."}};
    for (String[] probe : probes) {
      List<LayoutBlock> blocks = LayoutFixtures.blocks(lines(11, new float[][] {{72, 700}, {72, 686}}, probe)).get(0);
      assertEquals(List.of(probe[0] + " " + probe[1]), blocks.stream().map(LayoutBlock::text).toList());
    }
  }

  @Test
  void proseThatWrapsInsideAListItemStaysInTheItem() throws Exception {
    // A wrapped line of an item that starts like a marker ("v.", "c.", "p.") continues the item; only
    // the next marker of the item's own sequence ("2." after "1.", the same bullet) starts the next item.
    Object[][] probes = {
        {new float[][] {{72, 700}, {84, 686}, {72, 672}, {72, 658}},
            new String[] {"1. The court followed the decision of the Supreme Court in Roe", "v. Wade, which held that the right exists.",
                "2. The second item of the list follows here.", "3. The third item closes the list here."},
            List.of("1. The court followed the decision of the Supreme Court in Roe v. Wade, which held that the right exists.",
                "2. The second item of the list follows here.", "3. The third item closes the list here.")},
        {new float[][] {{72, 700}, {84, 700}, {84, 686}, {72, 672}, {84, 672}},
            new String[] {"•", "Prices rose over the whole period, most likely", "c. 1900, and restored order in the market.", "•",
                "A second point of the list here."},
            List.of("• Prices rose over the whole period, most likely c. 1900, and restored order in the market.", "• A second point of the list here.")},
        {new float[][] {{72, 700}, {84, 700}, {84, 686}, {72, 672}, {84, 672}},
            new String[] {"•", "Readers who want the full argument should see", "p. 45 of the second report for details.", "•",
                "Another point of the list here."},
            List.of("• Readers who want the full argument should see p. 45 of the second report for details.", "• Another point of the list here.")}};
    for (Object[] probe : probes) {
      List<LayoutBlock> blocks = LayoutFixtures.blocks(lines(11, (float[][]) probe[0], (String[]) probe[1])).get(0);
      assertEquals(probe[2], blocks.stream().map(LayoutBlock::text).toList());
      List<ListClassifier.ListRun> runs = ListClassifier.lists(blocks, InferenceSettings.DEFAULTS);
      assertEquals(1, runs.size(), blocks.toString());
      assertEquals(blocks.size(), runs.get(0).items().size());
      org.junit.jupiter.api.Assertions.assertTrue(runs.get(0).confidence() >= InferenceSettings.DEFAULTS.minConfidence());
    }
  }

  @Test
  void aMarkerAfterAFinishedLineStartsABlock() throws Exception {
    // The line above ends the paragraph (":"), so "a. First" starts the list.
    String[] text = {"The applicant must state the following:", "a. First option of the form", "b. Second option of the form"};
    List<LayoutBlock> blocks = LayoutFixtures.blocks(lines(11, new float[][] {{72, 700}, {72, 686}, {72, 672}}, text)).get(0);
    assertEquals(List.of(text[0], text[1], text[2]), blocks.stream().map(LayoutBlock::text).toList());
  }

  /** One Helvetica line per text at the given positions (one text-showing operator each). */
  private static byte[] lines(float size, float[][] at, String[] text) throws Exception {
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font font =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        for (int i = 0; i < text.length; i++) {
          cs.beginText();
          cs.setFont(font, size);
          cs.newLineAtOffset(at[i][0], at[i][1]);
          cs.showText(text[i]);
          cs.endText();
        }
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void aListLabelSetOffByATabJoinsItsText() throws Exception {
    // Word sets a numbered reference's label off by a tab: "2." ends at 78.4, the text starts at 99.3 (9 pt, a gap of
    // more than twice the size). The label line joins the text on its baseline; each item starts its own block.
    byte[] pdf;
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font font =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        String[][] lines = {{"1.", "First reference of the list"}, {"2.", "Second reference of the list"}};
        for (int i = 0; i < lines.length; i++) {
          for (int k = 0; k < 2; k++) {
            cs.beginText();
            cs.setFont(font, 9);
            cs.newLineAtOffset(k == 0 ? 70.8f : 99.3f, 700 - 11.5f * i);
            cs.showText(lines[i][k]);
            cs.endText();
          }
        }
      }
      doc.save(out);
      pdf = out.toByteArray();
    }

    List<LayoutBlock> blocks = LayoutFixtures.blocks(pdf).get(0);

    assertEquals(List.of("1. First reference of the list", "2. Second reference of the list"), blocks.stream().map(LayoutBlock::text).toList());
  }

  @Test
  void aSuperscriptStaysOnItsLine() throws Exception {
    // A 7 pt footnote mark raised 4 pt inside an 11 pt line: the line and the paragraph stay one block.
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedParagraphWithSuperscript()).get(0);

    assertEquals(List.of("The study found a clear effect1 in every group that was tested, and the effect lasted for the whole year."),
        blocks.stream().map(LayoutBlock::text).toList());
    assertEquals(2, blocks.get(0).lines());
  }

  @Test
  void aListItemsHangingIndentContinuesTheItem() throws Exception {
    // "7-1." at x 72, its text at x 108 (a tab); the wrapped line hangs at 108, more than twice the size from 72.
    byte[] pdf;
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font font =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        float[][] at = {{72, 700}, {108, 700}, {108, 686}, {72, 660}, {108, 660}};
        String[] text = {"7-1.", "The aim of this leaflet is to inform", "all personnel of their duties.", "7-2.", "MOD has obligations."};
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

    List<LayoutBlock> blocks = LayoutFixtures.blocks(pdf).get(0);

    assertEquals(List.of("7-1. The aim of this leaflet is to inform all personnel of their duties.", "7-2. MOD has obligations."),
        blocks.stream().map(LayoutBlock::text).toList());
  }

  @Test
  void aLooselySpacedListItemKeepsItsHangingLines() throws Exception {
    // Japanese Word layout: 12 pt text on a 20.9 pt line pitch (1.74 × size). Lines at the item's hanging indent
    // continue the item up to listItemGapFactor × size; a paragraph back at the margin does not.
    byte[] pdf;
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font font =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        float[][] at = {{108, 700}, {126, 700}, {126, 679.1f}, {108, 658.2f}, {126, 658.2f}, {90, 637.3f}};
        String[] text = {"1.", "Everyone charged has the right", "to be presumed innocent.", "2.", "No one shall be held guilty.", "Article 12"};
        for (int i = 0; i < text.length; i++) {
          cs.beginText();
          cs.setFont(font, 12);
          cs.newLineAtOffset(at[i][0], at[i][1]);
          cs.showText(text[i]);
          cs.endText();
        }
      }
      doc.save(out);
      pdf = out.toByteArray();
    }

    List<LayoutBlock> blocks = LayoutFixtures.blocks(pdf).get(0);

    assertEquals(List.of("1. Everyone charged has the right to be presumed innocent.", "2. No one shall be held guilty.", "Article 12"),
        blocks.stream().map(LayoutBlock::text).toList());
  }

  @Test
  void aListItemWithABoldFirstLineStaysOneBlock() throws Exception {
    // "a) enhance incentives ..." in bold, the item wraps in regular weight: the bold flip does not split a list item;
    // a bold line after regular item lines starts a block of its own.
    byte[] pdf;
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.font.PDType1Font regular =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
      org.apache.pdfbox.pdmodel.font.PDType1Font bold =
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
      try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        Object[][] lines = {{bold, 700f, "a) enhance incentives to continue working"}, {regular, 686f, "at an older age by rewarding later retirement;"},
            {bold, 672f, "b) restrict early retirement schemes"}, {regular, 658f, "which encourage workers to leave employment."},
            {bold, 644f, "Responsibilities of the holders"}};
        for (Object[] line : lines) {
          cs.beginText();
          cs.setFont((org.apache.pdfbox.pdmodel.font.PDType1Font) line[0], 9);
          cs.newLineAtOffset(72, (Float) line[1]);
          cs.showText((String) line[2]);
          cs.endText();
        }
      }
      doc.save(out);
      pdf = out.toByteArray();
    }

    List<LayoutBlock> blocks = LayoutFixtures.blocks(pdf).get(0);

    assertEquals(List.of("a) enhance incentives to continue working at an older age by rewarding later retirement;",
        "b) restrict early retirement schemes which encourage workers to leave employment.", "Responsibilities of the holders"),
        blocks.stream().map(LayoutBlock::text).toList(), "a bold line after the item's regular lines is a heading, not the item");
  }

  @Test
  void aMarkedSequenceOfSeveralOperatorsKeepsAllItsText() throws Exception {
    // Word's /Span <</Lang ...>> survives tag stripping: one walked item holds every operator inside it, on two rows;
    // the line after it continues its second row.
    byte[] pdf;
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
      resources.put(org.apache.pdfbox.cos.COSName.getPDFName("F1"),
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA));
      page.setResources(resources);
      org.apache.pdfbox.pdmodel.common.PDStream stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
      try (java.io.OutputStream os = stream.createOutputStream()) {
        os.write(("/Span <</Lang (en)>> BDC BT /F1 11 Tf 72 700 Td (The first row of a marked sequence) Tj 0 -14 Td (continues here) Tj ET EMC\n"
            + "BT /F1 11 Tf 150 686 Td (and ends outside it.) Tj ET\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      page.setContents(stream);
      doc.save(out);
      pdf = out.toByteArray();
    }

    List<LayoutBlock> blocks = LayoutFixtures.blocks(pdf).get(0);

    assertEquals(1, blocks.size(), blocks.toString());
    assertEquals("The first row of a marked sequence continues here and ends outside it.", blocks.get(0).text());
    assertEquals(2, blocks.get(0).lines());
    assertEquals(3, blocks.get(0).runs().size());
  }

  @Test
  void aBoldLineStartsItsOwnBlock() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedBoldAndRegularLine()).get(0);

    assertEquals(2, blocks.size(), blocks.toString());
    assertEquals(true, blocks.get(0).bold());
    assertEquals(false, blocks.get(1).bold());
  }

  @Test
  void aFigureBetweenTwoLinesEndsTheBlock() throws Exception {
    // untaggedArticle: two lines, a gap, two lines, an image, a rule, two lines → 3 text blocks and the image.
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedArticle()).get(0);

    assertEquals(List.of(2, 2, 0, 2), blocks.stream().map(LayoutBlock::lines).toList(), blocks.toString());
    assertEquals("", blocks.get(2).text(), "the image's block has no text");
  }
}
