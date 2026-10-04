package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** XY-cut reading order per page; the content order is kept when it already agrees or when the columns cannot be separated cleanly. */
class ReadingOrderTest {

  @Test
  void interleavedColumnsAreReordered() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedTwoColumnsInterleaved()).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, false, InferenceSettings.DEFAULTS);

    assertTrue(result.reordered());
    assertFalse(result.ambiguous());
    assertEquals(List.of(72f, 320f), result.order().stream().map(LayoutBlock::x0).toList(), "left column, then right column");
  }

  @Test
  void columnsInContentOrderAreKept() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedTwoColumnsInOrder()).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, false, InferenceSettings.DEFAULTS);

    assertFalse(result.reordered());
    assertFalse(result.ambiguous());
    assertEquals(blocks, result.order());
  }

  @Test
  void rightToLeftColumnsAreReadRightColumnFirst() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedTwoColumnsInterleaved()).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, true, InferenceSettings.DEFAULTS);

    assertTrue(result.reordered());
    assertFalse(result.ambiguous());
    assertEquals(List.of(320f, 72f), result.order().stream().map(LayoutBlock::x0).toList(), "right column first");
  }

  @Test
  void columnsTheContentAlreadyReadsOneAfterTheOtherAreKeptEvenRightToLeft() throws Exception {
    // Fail closed: the content order already reads each column contiguously, so nothing is reordered.
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedTwoColumnsInOrder()).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, true, InferenceSettings.DEFAULTS);

    assertFalse(result.reordered());
    assertEquals(blocks, result.order());
  }

  @Test
  void labelValueRowsAreNotColumns() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedLabelValueForm(4)).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, false, InferenceSettings.DEFAULTS);

    assertFalse(result.reordered());
    assertFalse(result.ambiguous());
  }

  @Test
  void aSuperscriptIsNotAColumn() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedParagraphWithSuperscript()).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, false, InferenceSettings.DEFAULTS);

    assertFalse(result.reordered());
  }

  @Test
  void aBlockAcrossTheGutterKeepsContentOrder() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedTwoColumnsWithStraddlingBlock()).get(0);
    assertEquals(3, blocks.size(), blocks.toString());

    ReadingOrder.Result result = ReadingOrder.order(blocks, false, InferenceSettings.DEFAULTS);

    assertTrue(result.ambiguous());
    assertFalse(result.reordered());
    assertEquals(blocks, result.order());
  }

  @Test
  void aSingleColumnPageKeepsItsOrder() throws Exception {
    List<LayoutBlock> blocks = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedReport(1)).get(0);

    ReadingOrder.Result result = ReadingOrder.order(blocks, false, InferenceSettings.DEFAULTS);

    assertFalse(result.reordered());
    assertFalse(result.ambiguous());
    assertSame(blocks.get(0), result.order().get(0));
    assertEquals(blocks, result.order());
  }
}
