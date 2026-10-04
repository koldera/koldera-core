package com.koldera.pdf.inference;

import java.util.List;

/**
 * A block of text lines on one page, or a single image/shared-form draw ({@code lines == 0}). The box is in user space
 * ({@code y0} the lowest baseline, {@code y1} the highest baseline plus font size); {@code bodySize} is the size painting
 * most characters; {@code itemIndexes} and {@code runs} are in content order.
 */
public record LayoutBlock(int page, List<Integer> itemIndexes, List<TextRun> runs, float x0, float y0, float x1, float y1, float bodySize, boolean bold,
    int lines, String text) {

  public LayoutBlock {
    itemIndexes = List.copyOf(itemIndexes);
    runs = List.copyOf(runs);
  }

  /** Whether the block has a position (an adopted item without a measured box has none). */
  public boolean hasGeometry() {
    return !Float.isNaN(x0) && !Float.isNaN(y0) && !Float.isNaN(x1) && !Float.isNaN(y1);
  }
}
