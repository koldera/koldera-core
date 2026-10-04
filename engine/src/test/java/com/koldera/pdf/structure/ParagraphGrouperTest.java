package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.koldera.pdf.structure.ParagraphGrouper.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The paragraph heuristics of the design section, exercised with synthetic positions only. */
class ParagraphGrouperTest {

  @Test
  void linesWithinOneAndAHalfFontSizesAndAlignedLeftEdgeShareAParagraph() {
    int[] groups =
        ParagraphGrouper.group(
            List.of(new Line(72, 720, 12, false), new Line(72, 706, 12, false), new Line(72, 692, 12, false)));

    assertArrayEquals(new int[] {0, 0, 0}, groups);
  }

  @Test
  void baselineJumpBeyondOneAndAHalfFontSizesStartsANewParagraph() {
    int[] groups =
        ParagraphGrouper.group(
            List.of(new Line(72, 720, 12, false), new Line(72, 706, 12, false), new Line(72, 660, 12, false), new Line(72, 646, 12, false)));

    assertArrayEquals(new int[] {0, 0, 1, 1}, groups);
  }

  @Test
  void leftEdgeJumpBeyondTwoFontSizesStartsANewParagraphEvenWhenBaselinesAreClose() {
    int[] groups = ParagraphGrouper.group(List.of(new Line(72, 720, 12, false), new Line(200, 706, 12, false)));

    assertArrayEquals(new int[] {0, 1}, groups);
  }

  @Test
  void showOperatorsOnTheSameBaselineAreOneLineWhateverTheirHorizontalPosition() {
    int[] groups =
        ParagraphGrouper.group(
            List.of(new Line(72, 720, 12, false), new Line(150, 720, 12, false), new Line(400, 720, 12, false), new Line(72, 706, 12, false)));

    assertArrayEquals(new int[] {0, 0, 0, 0}, groups);
  }

  @Test
  void anExplicitBreakStartsANewParagraphEvenForAdjacentLines() {
    int[] groups = ParagraphGrouper.group(List.of(new Line(72, 720, 12, false), new Line(72, 706, 12, true)));

    assertArrayEquals(new int[] {0, 1}, groups);
  }

  @Test
  void thresholdsScaleWithTheLargerFontSizeOfTheTwoLines() {
    // 24 pt heading followed 30 pt lower by 12 pt body: 30 <= 1.5 x 24, so no break (headings are never inferred).
    int[] groups = ParagraphGrouper.group(List.of(new Line(72, 720, 24, false), new Line(72, 690, 12, false)));

    assertArrayEquals(new int[] {0, 0}, groups);
    assertEquals(1.5f, ParagraphGrouper.BASELINE_FACTOR);
    assertEquals(2.0f, ParagraphGrouper.LEFT_EDGE_FACTOR);
  }

  @Test
  void emptyInputYieldsNoParagraphs() {
    assertArrayEquals(new int[0], ParagraphGrouper.group(List.of()));
  }
}
