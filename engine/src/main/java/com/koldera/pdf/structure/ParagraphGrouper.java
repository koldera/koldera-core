package com.koldera.pdf.structure;

import java.util.List;

/**
 * Pure paragraph heuristics for basic tagging. A line is one text-showing operator at its text origin; operators on one baseline
 * share a line. A new line joins the paragraph when its baseline is within {@link #BASELINE_FACTOR} font sizes of the previous
 * one and its left edge moves by at most {@link #LEFT_EDGE_FACTOR} sizes. Content order is the only order.
 */
public final class ParagraphGrouper {

  public static final float BASELINE_FACTOR = 1.5f;
  public static final float LEFT_EDGE_FACTOR = 2.0f;
  /** Baselines closer than this many font sizes are the same visual line. */
  public static final float SAME_LINE_FACTOR = 0.25f;

  /**
   * One text line: user-space x (left edge) and baseline y of its text origin, effective font size, and {@code breakBefore}
   * (a paragraph must start here, because real content such as a figure sits between this line and the previous one).
   */
  public record Line(float x, float y, float fontSize, boolean breakBefore) {}

  private ParagraphGrouper() {}

  /** Paragraph index per input line: non-decreasing, starting at 0, deterministic. */
  public static int[] group(List<Line> lines) {
    int[] groups = new int[lines.size()];
    if (lines.isEmpty()) {
      return groups;
    }
    int paragraph = 0;
    Line lineStart = lines.get(0);
    for (int i = 1; i < lines.size(); i++) {
      Line current = lines.get(i);
      float size = Math.max(Math.max(lineStart.fontSize(), current.fontSize()), 1e-3f);
      float baselineGap = Math.abs(current.y() - lineStart.y());
      if (current.breakBefore()) {
        paragraph++;
        lineStart = current;
      } else if (baselineGap <= SAME_LINE_FACTOR * size) {
        // same visual line: keep the line's left edge
      } else if (baselineGap <= BASELINE_FACTOR * size && Math.abs(current.x() - lineStart.x()) <= LEFT_EDGE_FACTOR * size) {
        lineStart = current;
      } else {
        paragraph++;
        lineStart = current;
      }
      groups[i] = paragraph;
    }
    return groups;
  }
}
