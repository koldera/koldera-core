package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The thresholds every inference step reads live in one record; its defaults are pinned here. */
class InferenceSettingsTest {

  @Test
  void defaultsArePinned() {
    InferenceSettings s = InferenceSettings.DEFAULTS;
    assertEquals(0.90, s.minTaggedTextCoverage());
    assertEquals(0.50, s.maxLargestElementShare());
    assertEquals(20, s.largestElementMinMcids());
    assertEquals(1000, s.maxPages());
    assertEquals(200000, s.maxTextRuns());
    assertEquals(0.25f, s.sameLineFactor());
    assertEquals(1.5f, s.blockGapFactor());
    assertEquals(2.0f, s.blockLeftEdgeFactor());
    assertEquals(0.15f, s.blockFontSizeChange());
    assertEquals(0.60, s.runningMinPageShare());
    assertEquals(3, s.runningMinPages());
    assertEquals(2.0f, s.runningPositionTolerance());
    assertEquals(2.0f, s.columnGapFactor());
    assertEquals(3, s.columnMinLines());
    assertEquals(1.2f, s.headingSizeRatio());
    assertEquals(2, s.headingMaxLines());
    assertEquals(200, s.headingMaxChars());
    assertEquals(2.0f, s.listIndentTolerance());
    assertEquals(2, s.listMinItems());
    assertEquals(3, s.tableMinAlignedRows());
    assertEquals(2, s.tableMinColumns());
    assertEquals(2.0f, s.tableColumnTolerance());
    assertEquals(1.5f, s.rulingMaxThickness());
    assertEquals(24.0f, s.captionMaxDistance());
    assertEquals(0.70, s.minConfidence());
    assertEquals(0.50, s.columnRowAlignedShare());
    assertEquals(20, s.columnMinLineChars());
    assertEquals(0.60, s.columnMinLineFill());
    assertEquals(0.50, s.columnMaxGutterShare());
    assertEquals(2, s.runningMaxLines());
    assertEquals(100, s.runningMaxChars());
    assertEquals(12, s.runningNumericMaxChars());
    assertEquals(4.0f, s.listLabelGapFactor());
    assertEquals(0.5f, s.superscriptRiseFactor());
    assertEquals(2.0f, s.listItemGapFactor());
    assertEquals(80, s.headingBoldMaxChars());
    assertEquals(1.5f, s.headingBoldFollowFactor());
    assertEquals(3, s.headingBoldRunMax());
    assertEquals(0.5, s.headingMinLetterShare());
    assertEquals(0.85, s.paragraphEndLineShare());
    assertEquals(0.6f, s.tableCellGapFactor());
    assertEquals(3.5f, s.tableRowGapFactor());
    assertEquals(3, s.captionMaxLines());
  }
}
