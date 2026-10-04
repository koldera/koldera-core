package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Headings from font size, bold lead-ins and numbered prefixes; weak evidence falls back to paragraphs. */
class HeadingClassifierTest {

  private static final String BODY = "Body text of the synthetic document that runs long enough to be the most frequent size by far in this test.";

  @Test
  void headingsAreClassifiedBySizeBoldAndNumbering() {
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block("Introduction", 16, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("1.2 Scope", 13, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Bold lead", 10, true, 1, y));
    y[0] += 20 - 2; // its text follows at the body's line pitch: the heading sticks to it
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Summary.", 10, true, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("2017", 30, false, 1, y));
    blocks.add(block("Three lines of large text", 16, false, 3, y));
    blocks.add(block(BODY, 10, false, 2, y));

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);

    assertEquals(blocks.size(), out.size());
    for (int i = 0; i < blocks.size(); i++) {
      assertEquals(blocks.get(i), out.get(i).block(), "same order");
    }
    assertHeading(out.get(0), "H1", 1, 1.0);
    assertHeading(out.get(2), "H2", 2, 1.0);
    assertHeading(out.get(4), "H3", 3, 0.75);
    HeadingClassifier.Classified note = out.get(6);
    assertEquals("P", note.type());
    assertEquals(0.4, note.confidence());
    assertTrue(note.fellBack(), "bold with a full stop is weak evidence");
    HeadingClassifier.Classified year = out.get(8);
    assertEquals("P", year.type());
    assertFalse(year.fellBack(), "no letter: not a candidate");
    HeadingClassifier.Classified threeLines = out.get(9);
    assertEquals("P", threeLines.type());
    assertFalse(threeLines.fellBack(), "too many lines: not a candidate");
    for (int i : new int[] {1, 3, 5, 7, 10}) {
      assertEquals("P", out.get(i).type());
      assertFalse(out.get(i).fellBack());
    }
  }

  @Test
  void boldRowLabelsWithValuesOnTheirBaselineAreNoHeadings() {
    // Reviewer probe: "Location" / "Policy" / "IAL1: With personal data" in bold with a value cell on the same baseline.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    for (String[] row : new String[][] {{"Location", "Main building, north wing"}, {"Policy", "Estates policy of the year"},
        {"IAL1: With personal data", "Self-asserted attributes"}}) {
      float top = y[0];
      blocks.add(at(row[0], 10, true, 1, 72, top));
      blocks.add(at(row[1], 10, false, 1, 250, top));
      y[0] = top - 10 - 20;
    }
    blocks.add(block(BODY, 10, false, 2, y));

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);

    for (HeadingClassifier.Classified c : out) {
      assertEquals("P", c.type(), c.block().text());
    }
    assertEquals(0.6, out.get(1).confidence(), "bold at body size alone scores 0.6");
  }

  @Test
  void aBoldSentenceInTheTextFlowIsNoHeading() {
    // Reviewer probe: a bold emphasised line one line gap below the paragraph above: no space above it.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    y[0] += 20 - 2; // the next line follows at the paragraph's own line pitch
    blocks.add(block("This change applies to every region without exception", 10, true, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("P", "P", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  @Test
  void anEmptyParagraphAboveABoldHeadingIsNoText() {
    // Word leaves empty bold paragraphs above headings: their blank block neither closes the space above nor counts as the next block.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block(" ", 12, true, 1, y));
    y[0] += 20 - 4;
    blocks.add(block("Royal Navy (RN)", 10, true, 1, y));
    y[0] += 20 - 2;
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("P", "P", "H1", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  @Test
  void aBoldLineFarAboveTheNextTextIsNoHeading() {
    // Round 2, variant V3: a bold body-size line whose next text starts more than 1.5 line gaps below does not stick to it.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Loose bold line", 10, true, 1, y));
    blocks.add(block(BODY, 10, false, 2, y)); // 30 pt below at a 12 pt line gap

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);
    assertEquals(List.of("P", "P", "P"), types(out));
    assertEquals(0.6, out.get(1).confidence());
  }

  @Test
  void captionWordsInAnyCaseAndExclamationsAreNoHeadings() {
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Fig. 3 Results by region", 14, false, 1, y));
    blocks.add(block("FIGURE 3 Results by region", 14, false, 1, y));
    blocks.add(block("TABLE 2 Costs", 14, false, 1, y));
    blocks.add(block("Act now!", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);
    assertEquals(List.of("P", "P", "P", "P", "P", "P"), types(out));
    assertEquals(0.4, out.get(4).confidence(), "an exclamation ends like a sentence");
  }

  @Test
  void aBoldHeadingBesideTheOtherColumnsTextIsAHeading() {
    // Two columns: "Methods" (bold, left) shares its baseline with a line in the middle of the right column's paragraph.
    // Only a block that starts on that baseline (a row's value cell) makes it a row label.
    List<LayoutBlock> blocks = new ArrayList<>();
    blocks.add(at(BODY, 10, false, 2, 72, 800));
    blocks.add(at("Methods", 10, true, 1, 72, 758));
    blocks.add(at(BODY, 10, false, 2, 72, 746));
    blocks.add(at(BODY + " " + BODY + " " + BODY, 10, false, 6, 320, 794)); // baselines 784 … 748 … 724

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);
    assertEquals("H1", out.get(1).type(), "the right column's line at 748 is inside its paragraph");
  }

  @Test
  void aLargeFigureCalloutIsNoHeading() {
    // Reviewer probe: a 24 pt "£5m saved" callout.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("£5m saved", 24, false, 1, y));
    blocks.add(block("Q3 2024: 12.5%", 16, false, 1, y));
    blocks.add(block("Figure 3 Results by region", 14, false, 1, y));
    blocks.add(block("Source: national statistics", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("P", "P", "P", "P", "P", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  @Test
  void boldLabelsInAColumnOrEndingInAColonAreNoHeadings() {
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Applicants must provide:", 10, true, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Name", 10, true, 1, y));
    blocks.add(block("Address", 10, true, 1, y));
    blocks.add(block("Telephone", 10, true, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("P", "P", "P", "P", "P", "P", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  @Test
  void aTableOfContentsEntryWithADotLeaderIsNoHeading() {
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block("OVERVIEW ........................ 2", 14, false, 1, y));
    blocks.add(block("Scope . . . . . . . . 3", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Overview", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);

    assertEquals(List.of("P", "P", "P", "H1", "P"), types(out));
    assertFalse(out.get(0).fellBack(), "a dot leader is not a candidate");
  }

  @Test
  void numberedHeadingsTakeTheirDepthWhenSizesAgree() {
    // "1 Results" and "1.1 Method" share a size; the numbering tells the depth.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block("Annual review", 20, false, 1, y));
    blocks.add(block("1 Results", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("1.1 Method", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("2 Outlook", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    List<HeadingClassifier.Classified> out = HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS);

    assertEquals(List.of("H1", "H2", "P", "H3", "P", "H2", "P"), types(out));
  }

  @Test
  void aLeadingYearIsNoSectionNumber() {
    // Counted as depth 1, "2017" would make "1 Results" a sibling of the title (H1, H1, H2).
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block("2017 Annual review", 20, false, 1, y));
    blocks.add(block("1 Results", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("1.1 Method", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("H1", "H2", "P", "H3", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  @Test
  void anUnnumberedHeadingSmallerThanANumberedOneIsBelowIt() {
    // "Detail" (12 pt) is smaller than "1.1 Method" (14 pt, level 3): at least level 4, not Method's sibling.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block("Annual review", 20, false, 1, y));
    blocks.add(block("1 Results", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("1.1 Method", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("Detail", 12.5f, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("H1", "H2", "P", "H3", "P", "H4", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  @Test
  void numberingThatContradictsTheSizesIsIgnored() {
    // The deeper number is the larger size: prefixes are ignored and the sizes rank the levels.
    List<LayoutBlock> blocks = new ArrayList<>();
    float[] y = {800};
    blocks.add(block("1.1 Large", 20, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));
    blocks.add(block("2 Smaller", 14, false, 1, y));
    blocks.add(block(BODY, 10, false, 2, y));

    assertEquals(List.of("H1", "P", "H2", "P"), types(HeadingClassifier.classify(blocks, InferenceSettings.DEFAULTS)));
  }

  private static List<String> types(List<HeadingClassifier.Classified> out) {
    return out.stream().map(HeadingClassifier.Classified::type).toList();
  }

  private static void assertHeading(HeadingClassifier.Classified c, String type, int level, double confidence) {
    assertEquals(type, c.type(), c.block().text());
    assertEquals(level, c.level(), c.block().text());
    assertEquals(confidence, c.confidence(), c.block().text());
    assertFalse(c.fellBack(), c.block().text());
  }

  /** A block of {@code lines} lines at x 72 whose top is {@code y[0]}; the next block starts 20 pt below its last baseline. */
  static LayoutBlock block(String text, float size, boolean bold, int lines, float[] y) {
    LayoutBlock block = at(text, size, bold, lines, 72, y[0]);
    y[0] = block.y0() - 20;
    return block;
  }

  private static int nextIndex;

  /**
   * A block of {@code lines} lines at {@code x} (top {@code top}): one run per line, baselines {@code top − size} and then every
   * {@code 1.2 × size}, the text split evenly over the lines.
   */
  static LayoutBlock at(String text, float size, boolean bold, int lines, float x, float top) {
    List<TextRun> runs = new ArrayList<>();
    int per = (text.length() + lines - 1) / lines;
    float baseline = top - size;
    for (int k = 0; k < lines; k++) {
      String part = text.substring(Math.min(text.length(), k * per), Math.min(text.length(), (k + 1) * per));
      runs.add(new TextRun(k, part, x, baseline, x + Math.max(1, part.length()) * size * 0.5f, baseline + size, size, bold,
          bold ? "Helvetica-Bold" : "Helvetica"));
      baseline -= size * 1.2f;
    }
    float bottom = runs.get(runs.size() - 1).baseline();
    float x1 = (float) runs.stream().mapToDouble(TextRun::x1).max().orElse(x);
    return new LayoutBlock(1, List.of(nextIndex++), runs, x, bottom, x1, top, size, bold, lines, text);
  }
}
