package com.koldera.pdf.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading order of one page's blocks, an XY-cut: a column cut through the whole part is tried first, else a horizontal cut
 * at white space of at least the median line gap. A column cut needs a wide enough gutter, enough lines per side and
 * evidence of prose columns rather than rows; otherwise content order is kept (fail closed). The page is {@code reordered}
 * only when a cut separated interleaved sides. Only the structure order is decided; glyphs and streams are never reordered.
 */
public final class ReadingOrder {

  /** {@code order}: the blocks in reading order ({@code blocks} itself unless reordered). */
  public record Result(List<LayoutBlock> order, boolean reordered, boolean ambiguous) {}

  private static final int MAX_DEPTH = 64;

  private ReadingOrder() {}

  public static Result order(List<LayoutBlock> blocks, boolean rightToLeft, InferenceSettings s) {
    if (blocks.size() < 2) {
      return new Result(blocks, false, false);
    }
    for (LayoutBlock block : blocks) {
      if (!block.hasGeometry()) {
        return new Result(blocks, false, true);
      }
    }
    Map<LayoutBlock, Integer> contentIndex = new IdentityHashMap<>();
    for (int i = 0; i < blocks.size(); i++) {
      contentIndex.put(blocks.get(i), i);
    }
    Cutter cutter = new Cutter(medianLineGap(blocks, s), rightToLeft, s, contentIndex);
    List<LayoutBlock> cut = cutter.cut(new ArrayList<>(blocks), 0);
    if (cutter.ambiguous) {
      return new Result(blocks, false, true);
    }
    if (!cutter.separatedInterleaving || readsInContentOrder(cut)) {
      return new Result(blocks, false, false);
    }
    return new Result(List.copyOf(cut), true, false);
  }

  private static boolean readsInContentOrder(List<LayoutBlock> order) {
    int previous = -1;
    for (LayoutBlock block : order) {
      for (int index : block.itemIndexes()) {
        if (index <= previous) {
          return false;
        }
        previous = index;
      }
    }
    return true;
  }

  /** Median distance between consecutive line baselines within blocks; 1.2 × the median block size when there is none. */
  static float medianLineGap(List<LayoutBlock> blocks, InferenceSettings s) {
    List<Float> gaps = new ArrayList<>();
    List<Float> sizes = new ArrayList<>();
    for (LayoutBlock block : blocks) {
      if (block.lines() == 0) {
        continue;
      }
      sizes.add(block.bodySize());
      List<Line> lines = lines(block, s);
      for (int k = 1; k < lines.size(); k++) {
        gaps.add(lines.get(k - 1).baseline - lines.get(k).baseline);
      }
    }
    if (!gaps.isEmpty()) {
      return median(gaps);
    }
    return sizes.isEmpty() ? 12f * 1.2f : 1.2f * Math.max(1f, median(sizes));
  }

  /** One text line of a block: its baseline, characters, horizontal extent and size. */
  private static final class Line {
    final float baseline;
    final float size;
    int chars;
    float x0 = Float.MAX_VALUE;
    float x1 = -Float.MAX_VALUE;

    Line(float baseline, float size) {
      this.baseline = baseline;
      this.size = size;
    }
  }

  /** The block's lines, top to bottom: its runs clustered by baseline (within {@code sameLineFactor ×} the block size). */
  private static List<Line> lines(LayoutBlock block, InferenceSettings s) {
    List<TextRun> runs = new ArrayList<>();
    for (TextRun run : block.runs()) {
      if (run.hasGlyphs()) {
        runs.add(run);
      }
    }
    runs.sort(Comparator.comparingDouble((TextRun r) -> -r.baseline()).thenComparingInt(TextRun::ordinal));
    float tolerance = s.sameLineFactor() * Math.max(1f, block.bodySize());
    List<Line> out = new ArrayList<>();
    Line line = null;
    for (TextRun run : runs) {
      if (line == null || line.baseline - run.baseline() > tolerance) {
        line = new Line(run.baseline(), Math.max(1f, block.bodySize()));
        out.add(line);
      }
      line.chars += run.text().strip().length();
      line.x0 = Math.min(line.x0, run.x0());
      line.x1 = Math.max(line.x1, run.x1());
    }
    return out;
  }

  private static float median(List<Float> values) {
    float[] sorted = new float[values.size()];
    for (int i = 0; i < sorted.length; i++) {
      sorted[i] = values.get(i);
    }
    Arrays.sort(sorted);
    return sorted[(sorted.length - 1) / 2];
  }

  private static final class Cutter {
    final float lineGap;
    final float gutter;
    final boolean rightToLeft;
    final InferenceSettings s;
    final Map<LayoutBlock, Integer> contentIndex;
    boolean ambiguous;
    /** A column cut separated two sides whose items interleave in content order. */
    boolean separatedInterleaving;

    Cutter(float lineGap, boolean rightToLeft, InferenceSettings s, Map<LayoutBlock, Integer> contentIndex) {
      this.lineGap = lineGap;
      this.gutter = s.columnGapFactor() * lineGap;
      this.rightToLeft = rightToLeft;
      this.s = s;
      this.contentIndex = contentIndex;
    }

    /** {@code part} in content order; returns it in reading order. */
    List<LayoutBlock> cut(List<LayoutBlock> part, int depth) {
      if (part.size() < 2 || ambiguous || depth > MAX_DEPTH) {
        return part;
      }
      List<List<LayoutBlock>> columns = columns(part, false);
      if (columns == null) {
        List<List<LayoutBlock>> bands = bands(part);
        if (bands.size() > 1) {
          List<LayoutBlock> out = new ArrayList<>();
          for (List<LayoutBlock> band : bands) {
            out.addAll(cut(band, depth + 1));
          }
          return out;
        }
        columns = columns(part, true);
      }
      if (columns == null) {
        return part;
      }
      if (interleave(columns.get(0), columns.get(1))) {
        separatedInterleaving = true;
      }
      if (rightToLeft) {
        columns = List.of(columns.get(1), columns.get(0));
      }
      List<LayoutBlock> out = new ArrayList<>();
      for (List<LayoutBlock> column : columns) {
        out.addAll(cut(column, depth + 1));
      }
      return out;
    }

    private static boolean interleave(List<LayoutBlock> a, List<LayoutBlock> b) {
      int[] ra = range(a);
      int[] rb = range(b);
      return !(ra[1] < rb[0] || rb[1] < ra[0]);
    }

    private static int[] range(List<LayoutBlock> blocks) {
      int min = Integer.MAX_VALUE;
      int max = Integer.MIN_VALUE;
      for (LayoutBlock block : blocks) {
        for (int index : block.itemIndexes()) {
          min = Math.min(min, index);
          max = Math.max(max, index);
        }
      }
      return new int[] {min, max};
    }

    /** Top-to-bottom bands separated by vertical white space of at least the median line gap; each band in content order. */
    private List<List<LayoutBlock>> bands(List<LayoutBlock> part) {
      List<LayoutBlock> byTop = new ArrayList<>(part);
      byTop.sort(Comparator.comparingDouble((LayoutBlock b) -> -b.y1()).thenComparingInt(contentIndex::get));
      List<List<LayoutBlock>> bands = new ArrayList<>();
      List<LayoutBlock> band = new ArrayList<>();
      float bottom = Float.MAX_VALUE;
      for (LayoutBlock block : byTop) {
        if (!band.isEmpty() && bottom - block.y1() >= lineGap) {
          bands.add(band);
          band = new ArrayList<>();
          bottom = Float.MAX_VALUE;
        }
        band.add(block);
        bottom = Math.min(bottom, block.y0());
      }
      bands.add(band);
      for (List<LayoutBlock> b : bands) {
        b.sort(Comparator.comparingInt(contentIndex::get));
      }
      return bands;
    }

    /**
     * The leftmost accepted column cut as {left, right}, or null. A candidate at a block's left edge needs a gutter of at least
     * {@link #gutter}, enough lines per side and no straddling block, and the sides must read as prose ({@link #proseColumns}).
     * With {@code markAmbiguous}, straddling blocks make the page ambiguous.
     */
    private List<List<LayoutBlock>> columns(List<LayoutBlock> part, boolean markAmbiguous) {
      List<LayoutBlock> byRight = new ArrayList<>(part);
      byRight.sort(Comparator.comparingDouble(LayoutBlock::x1));
      List<LayoutBlock> byLeft = new ArrayList<>(part);
      byLeft.sort(Comparator.comparingDouble(LayoutBlock::x0));
      int n = part.size();
      int[] linesRightFrom = new int[n + 1]; // lines of byLeft[k..]
      for (int k = n - 1; k >= 0; k--) {
        linesRightFrom[k] = linesRightFrom[k + 1] + byLeft.get(k).lines();
      }
      int leftCount = 0;
      int linesLeft = 0;
      float leftEdge = -Float.MAX_VALUE;
      for (int k = 0; k < n; k++) {
        float r = byLeft.get(k).x0();
        if (k > 0 && byLeft.get(k - 1).x0() == r) {
          continue;
        }
        while (leftCount < n && byRight.get(leftCount).x1() < r) {
          leftEdge = byRight.get(leftCount).x1();
          linesLeft += byRight.get(leftCount).lines();
          leftCount++;
        }
        int rightCount = n - k;
        int linesRight = linesRightFrom[k];
        if (leftCount == 0 || rightCount == 0 || r - leftEdge < gutter || linesLeft < s.columnMinLines() || linesRight < s.columnMinLines()) {
          continue;
        }
        if (leftCount + rightCount == n) {
          List<LayoutBlock> left = new ArrayList<>();
          List<LayoutBlock> right = new ArrayList<>();
          for (LayoutBlock block : part) {
            (block.x0() >= r ? right : left).add(block);
          }
          if (proseColumns(left, right, r - leftEdge)) {
            return List.of(left, right);
          }
          continue;
        }
        if (markAmbiguous) {
          ambiguous = true; // a block straddles a gutter that would separate two columns
          return null;
        }
      }
      return null;
    }

    /** Evidence that the two sides are columns of prose, not the rows of a form or a table (see the class comment). */
    private boolean proseColumns(List<LayoutBlock> left, List<LayoutBlock> right, float gutterWidth) {
      List<Line> leftLines = new ArrayList<>();
      for (LayoutBlock block : left) {
        leftLines.addAll(lines(block, s));
      }
      List<Line> rightLines = new ArrayList<>();
      for (LayoutBlock block : right) {
        rightLines.addAll(lines(block, s));
      }
      if (leftLines.isEmpty() || rightLines.isEmpty()) {
        return false;
      }
      int aligned = 0;
      for (Line l : leftLines) {
        for (Line r : rightLines) {
          if (Math.abs(l.baseline - r.baseline) <= s.sameLineFactor() * Math.max(l.size, r.size)) {
            aligned++;
            break;
          }
        }
      }
      if (aligned >= s.columnRowAlignedShare() * leftLines.size()) {
        return false;
      }
      float leftWidth = width(left);
      float rightWidth = width(right);
      return prose(leftLines, leftWidth) && prose(rightLines, rightWidth) && gutterWidth < s.columnMaxGutterShare() * Math.min(leftWidth, rightWidth);
    }

    private boolean prose(List<Line> lines, float sideWidth) {
      List<Float> chars = new ArrayList<>();
      List<Float> widths = new ArrayList<>();
      for (Line line : lines) {
        chars.add((float) line.chars);
        widths.add(line.x1 - line.x0);
      }
      return median(chars) >= s.columnMinLineChars() && sideWidth > 0 && median(widths) >= s.columnMinLineFill() * sideWidth;
    }

    private static float width(List<LayoutBlock> side) {
      float x0 = Float.MAX_VALUE;
      float x1 = -Float.MAX_VALUE;
      for (LayoutBlock block : side) {
        x0 = Math.min(x0, block.x0());
        x1 = Math.max(x1, block.x1());
      }
      return x1 - x0;
    }
  }
}
