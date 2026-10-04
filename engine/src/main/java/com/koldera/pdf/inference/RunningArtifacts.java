package com.koldera.pdf.inference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Running headers and footers: in a document of at least {@code runningMinPages} pages, a short text block whose key (digit
 * runs replaced by {@code #}) recurs at the same place on enough pages is page furniture and becomes an
 * {@code /Artifact /Pagination} on every page. The caller's {@code eligible} test limits it to the page band. Deterministic.
 */
public final class RunningArtifacts {

  private RunningArtifacts() {}

  public static Set<LayoutBlock> find(List<List<LayoutBlock>> pages, InferenceSettings s) {
    return find(pages, s, block -> true);
  }

  public static Set<LayoutBlock> find(List<List<LayoutBlock>> pages, InferenceSettings s, java.util.function.Predicate<LayoutBlock> eligible) {
    Set<LayoutBlock> out = new LinkedHashSet<>();
    if (pages.size() < s.runningMinPages()) {
      return out;
    }
    // key → every text block with that key, as {page index, block}
    Map<String, List<Object[]>> byKey = new LinkedHashMap<>();
    for (int p = 0; p < pages.size(); p++) {
      for (LayoutBlock block : pages.get(p)) {
        if (block.lines() == 0 || !block.hasGeometry() || !furnitureShaped(block, s) || !eligible.test(block)) {
          continue;
        }
        String key = key(block.text());
        if (!key.isEmpty()) {
          byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[] {p, block});
        }
      }
    }
    double needed = s.runningMinPageShare() * pages.size();
    float tolerance = Math.max(s.runningPositionTolerance(), 0.01f);
    for (List<Object[]> blocks : byKey.values()) {
      if (blocks.size() < needed) {
        continue;
      }
      // Grid of tolerance-sized cells: a block's matches lie in its own or a neighbouring cell, so the
      // comparison stays near linear even when a key (a lone page number "#") recurs thousands of times.
      Map<Long, List<Object[]>> grid = new LinkedHashMap<>();
      for (Object[] entry : blocks) {
        LayoutBlock block = (LayoutBlock) entry[1];
        grid.computeIfAbsent(cell(cellOf(block.x0(), tolerance), cellOf(block.y0(), tolerance)), k -> new ArrayList<>()).add(entry);
      }
      for (Object[] candidate : blocks) {
        LayoutBlock block = (LayoutBlock) candidate[1];
        long cx = cellOf(block.x0(), tolerance);
        long cy = cellOf(block.y0(), tolerance);
        Set<Integer> on = new HashSet<>();
        for (long dx = -1; dx <= 1; dx++) {
          for (long dy = -1; dy <= 1; dy++) {
            for (Object[] other : grid.getOrDefault(cell(cx + dx, cy + dy), List.of())) {
              LayoutBlock o = (LayoutBlock) other[1];
              if (Math.abs(o.x0() - block.x0()) <= s.runningPositionTolerance() && Math.abs(o.y0() - block.y0()) <= s.runningPositionTolerance()) {
                on.add((Integer) other[0]);
              }
            }
          }
        }
        if (on.size() >= 2 && on.size() >= needed) {
          out.add(block);
        }
      }
    }
    return out;
  }

  private static long cellOf(float value, float size) {
    return (long) Math.floor(value / size);
  }

  private static long cell(long x, long y) {
    return x * 1_000_003L + y;
  }

  private static boolean furnitureShaped(LayoutBlock block, InferenceSettings s) {
    int chars = block.text().codePointCount(0, block.text().length());
    if (block.lines() > s.runningMaxLines() || chars > s.runningMaxChars()) {
      return false;
    }
    return block.text().codePoints().anyMatch(Character::isLetter) || chars <= s.runningNumericMaxChars();
  }

  /** The block text with every digit run replaced by {@code #} and whitespace collapsed. */
  static String key(String text) {
    return text.replaceAll("\\p{Nd}+", "#").replaceAll("\\s+", " ").strip();
  }
}
