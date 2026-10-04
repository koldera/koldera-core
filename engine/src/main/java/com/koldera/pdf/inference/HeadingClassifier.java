package com.koldera.pdf.inference;

import com.koldera.pdf.generated.HeadingLevels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headings over the blocks of a whole document in reading order. A block is a candidate by line/character limits and
 * font size relative to the body size, or bold at body size ({@link #strongBold}); confidence below {@code minConfidence}
 * makes it a {@code P}. Levels come from the ranked sizes and numbered prefixes, then {@link HeadingLevels#levels}
 * renumbers densely (first heading {@code H1}, no level skipped, at most {@code H6}).
 */
public final class HeadingClassifier {

  /** A block's classification: {@code type} {@code H1}–{@code H6} (level 1–6) or {@code P} (level 0). */
  public record Classified(LayoutBlock block, String type, int level, double confidence, boolean fellBack) {}

  /** A section number: at most three digits before the first dot, so a leading year ("2017 Annual review") is none. */
  static final Pattern NUMBERED = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){0,5})\\.?\\s");
  /** Captions and source lines are never headings. */
  private static final Pattern CAPTION = Pattern.compile("(?i)^(Figure|Fig\\.|Table|Chart|Source|Notes?)(?:\\b|(?<=\\.))");
  /** A figure callout's first word: a currency or percent sign. */
  private static final Pattern CALLOUT = Pattern.compile("[\\p{Sc}%]");

  private static final Pattern LETTER = Pattern.compile("\\p{L}");
  /** A table-of-contents entry's dot leader (four dots, spaced or not, or two ellipses): not a heading. */
  private static final Pattern DOT_LEADER = Pattern.compile("(?:\\.\\s?){4,}|\\x{2026}{2,}");

  private HeadingClassifier() {}

  public static List<Classified> classify(List<LayoutBlock> documentBlocks, InferenceSettings s) {
    float body = bodySize(documentBlocks);
    int n = documentBlocks.size();
    double[] confidence = new double[n];
    boolean[] sizeBased = new boolean[n];
    boolean[] boldAtBody = new boolean[n];
    for (int i = 0; i < n; i++) {
      LayoutBlock block = documentBlocks.get(i);
      boldAtBody[i] = candidate(block, s) && block.bold() && atBody(round(block.bodySize()), body, s);
    }
    float lineGap = medianLineGap(documentBlocks, body, s);
    Map<Integer, List<LayoutBlock>> byPage = new TreeMap<>();
    for (LayoutBlock block : documentBlocks) {
      byPage.computeIfAbsent(block.page(), k -> new ArrayList<>()).add(block);
    }
    for (int i = 0; i < n; i++) {
      LayoutBlock block = documentBlocks.get(i);
      if (!candidate(block, s)) {
        continue;
      }
      float size = round(block.bodySize());
      double c = 0;
      if (size >= s.headingSizeRatio() * body) {
        c = 1.0;
        sizeBased[i] = true;
      } else if (boldAtBody[i]) {
        c = strongBold(documentBlocks, i, boldAtBody, body, lineGap, byPage, s) ? 0.75 : 0.6;
      }
      if (c > 0 && endsLikeASentence(block.text())) {
        c = Math.min(c, 0.4);
      }
      confidence[i] = c;
    }
    // Size ranks of accepted size-based headings, largest first; bold headings at body size rank after them.
    TreeSet<Float> sizes = new TreeSet<>();
    for (int i = 0; i < n; i++) {
      if (confidence[i] >= s.minConfidence() && sizeBased[i]) {
        sizes.add(round(documentBlocks.get(i).bodySize()));
      }
    }
    List<Float> ranked = new ArrayList<>(sizes.descendingSet());
    List<Integer> accepted = new ArrayList<>();
    int[] rank = new int[n];
    for (int i = 0; i < n; i++) {
      if (confidence[i] >= s.minConfidence()) {
        accepted.add(i);
        rank[i] = sizeBased[i] ? ranked.indexOf(round(documentBlocks.get(i).bodySize())) + 1 : ranked.size() + 1;
      }
    }
    int[] original = new int[accepted.size()];
    Map<Integer, Integer> numbered = numberedLevels(documentBlocks, accepted, rank);
    for (int k = 0; k < accepted.size(); k++) {
      int i = accepted.get(k);
      Integer level = numbered.get(i);
      if (level == null) {
        // An unnumbered heading smaller than a numbered one sits below it.
        level = rank[i];
        for (Map.Entry<Integer, Integer> e : numbered.entrySet()) {
          if (rank[e.getKey()] < rank[i]) {
            level = Math.max(level, e.getValue() + 1);
          }
        }
      }
      original[k] = level;
    }
    int[] levels = HeadingLevels.levels(original);
    List<Classified> out = new ArrayList<>(n);
    int k = 0;
    for (int i = 0; i < n; i++) {
      LayoutBlock block = documentBlocks.get(i);
      if (confidence[i] >= s.minConfidence()) {
        int level = Math.min(6, levels[k++]);
        out.add(new Classified(block, "H" + level, level, confidence[i], false));
      } else {
        out.add(new Classified(block, "P", 0, confidence[i], confidence[i] > 0));
      }
    }
    return out;
  }

  /**
   * Levels from numbered prefixes (block index → level) when every numbered heading's depth is
   * non-decreasing as its size rank increases; empty otherwise.
   */
  private static Map<Integer, Integer> numberedLevels(List<LayoutBlock> blocks, List<Integer> accepted, int[] rank) {
    Map<Integer, Integer> depth = new TreeMap<>();
    for (int i : accepted) {
      Matcher m = NUMBERED.matcher(blocks.get(i).text());
      if (m.find()) {
        depth.put(i, m.group(1).split("\\.").length);
      }
    }
    if (depth.isEmpty()) {
      return Map.of();
    }
    // Per rank the smallest and largest depth; a lower rank's largest depth must not exceed a higher rank's smallest.
    TreeMap<Integer, int[]> byRank = new TreeMap<>();
    depth.forEach((i, d) -> byRank.merge(rank[i], new int[] {d, d}, (x, y) -> new int[] {Math.min(x[0], y[0]), Math.max(x[1], y[1])}));
    int deepestSoFar = 0;
    for (int[] range : byRank.values()) {
      if (deepestSoFar > range[0]) {
        return Map.of();
      }
      deepestSoFar = Math.max(deepestSoFar, range[1]);
    }
    int minDepth = Integer.MAX_VALUE;
    int baseRank = Integer.MAX_VALUE;
    for (Map.Entry<Integer, Integer> e : depth.entrySet()) {
      if (e.getValue() < minDepth) {
        minDepth = e.getValue();
        baseRank = rank[e.getKey()];
      } else if (e.getValue() == minDepth) {
        baseRank = Math.min(baseRank, rank[e.getKey()]);
      }
    }
    Map<Integer, Integer> out = new TreeMap<>();
    for (Map.Entry<Integer, Integer> e : depth.entrySet()) {
      out.put(e.getKey(), baseRank + e.getValue() - minDepth);
    }
    return out;
  }

  /**
   * At most {@code headingMaxLines} text lines and {@code headingMaxChars} characters; letters at least
   * {@code headingMinLetterShare} of the non-space characters; no dot leader, no caption or source line
   * ({@code Figure}, {@code Fig.}, {@code Table}, {@code Chart}, {@code Source}, {@code Note(s)}), no figure
   * callout (a first word with a currency or percent sign, such as a 5-million-pound figure).
   */
  static boolean candidate(LayoutBlock block, InferenceSettings s) {
    String text = block.text();
    if (block.lines() <= 0 || block.lines() > s.headingMaxLines() || text.codePointCount(0, text.length()) > s.headingMaxChars()) {
      return false;
    }
    int letters = 0;
    int visible = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      if (!Character.isWhitespace(cp)) {
        visible++;
        letters += Character.isLetter(cp) ? 1 : 0;
      }
      i += Character.charCount(cp);
    }
    String first = text.strip().split("\\s+", 2)[0];
    return letters > 0
        && letters >= s.headingMinLetterShare() * visible
        && !DOT_LEADER.matcher(text).find()
        && !CAPTION.matcher(text).find()
        && !CALLOUT.matcher(first).find();
  }

  /**
   * A bold heading at body size: followed by non-bold body text, short, not ending in a colon, not part of a run of bold
   * candidates (a label column), with no other text block on its baselines (a row label), and close to the text below.
   */
  private static boolean strongBold(List<LayoutBlock> blocks, int i, boolean[] boldAtBody, float body, float lineGap,
      Map<Integer, List<LayoutBlock>> byPage, InferenceSettings s) {
    LayoutBlock block = blocks.get(i);
    String text = block.text().strip();
    LayoutBlock following = null;
    for (int j = i + 1; j < blocks.size() && following == null; j++) {
      if (blocks.get(j).lines() > 0 && !blocks.get(j).text().isBlank()) {
        following = blocks.get(j);
      }
    }
    if (following == null || !bodyText(following, body, s) || text.codePointCount(0, text.length()) > s.headingBoldMaxChars()
        || text.endsWith(":")) {
      return false;
    }
    int from = i;
    while (from > 0 && boldAtBody[from - 1]) {
      from--;
    }
    int to = i;
    while (to + 1 < blocks.size() && boldAtBody[to + 1]) {
      to++;
    }
    if (to - from + 1 >= s.headingBoldRunMax()) {
      return false;
    }
    // A row label: another text block STARTS on one of its baselines (a value cell). A line in the middle of another
    // column's paragraph on the same baseline does not count.
    float size = block.bodySize();
    for (LayoutBlock other : byPage.getOrDefault(block.page(), List.of())) {
      if (other == block || other.lines() == 0 || other.text().isBlank()) {
        continue;
      }
      float start = first(other);
      for (TextRun a : block.runs()) {
        if (a.hasGlyphs() && Math.abs(a.baseline() - start) <= s.sameLineFactor() * Math.max(size, other.bodySize())) {
          return false;
        }
      }
    }
    LayoutBlock next = neighbour(blocks, i, 1);
    float below = next == null ? Float.NEGATIVE_INFINITY : lowest(block) - first(next);
    // The heading sticks to what follows: the next text block starts within headingBoldFollowFactor x the median line gap below
    // (chosen by the benchmark).
    return next != null && below > 0 && below <= s.headingBoldFollowFactor() * lineGap;
  }

  /** The nearest non-blank text block before ({@code step -1}) or after ({@code +1}) on the same page, or null (an empty paragraph is no text). */
  private static LayoutBlock neighbour(List<LayoutBlock> blocks, int i, int step) {
    for (int j = i + step; j >= 0 && j < blocks.size(); j += step) {
      LayoutBlock other = blocks.get(j);
      if (other.page() != blocks.get(i).page()) {
        return null;
      }
      if (other.lines() > 0 && other.hasGeometry() && !other.text().isBlank()) {
        return other;
      }
    }
    return null;
  }

  /** The highest glyph baseline of the block. */
  private static float first(LayoutBlock block) {
    float first = -Float.MAX_VALUE;
    for (TextRun run : block.runs()) {
      if (run.hasGlyphs()) {
        first = Math.max(first, run.baseline());
      }
    }
    return first == -Float.MAX_VALUE ? block.y1() - block.bodySize() : first;
  }

  /** The lowest glyph baseline of the block. */
  private static float lowest(LayoutBlock block) {
    float lowest = Float.MAX_VALUE;
    for (TextRun run : block.runs()) {
      if (run.hasGlyphs()) {
        lowest = Math.min(lowest, run.baseline());
      }
    }
    return lowest == Float.MAX_VALUE ? block.y0() : lowest;
  }

  /** The median distance between consecutive baselines inside body-size blocks of several lines; 1.2 × body without any. */
  static float medianLineGap(List<LayoutBlock> blocks, float body, InferenceSettings s) {
    List<Float> gaps = new ArrayList<>();
    for (LayoutBlock block : blocks) {
      if (block.lines() < 2 || !atBody(round(block.bodySize()), body, s)) {
        continue;
      }
      TreeSet<Float> baselines = new TreeSet<>();
      for (TextRun run : block.runs()) {
        if (run.hasGlyphs()) {
          baselines.add(run.baseline());
        }
      }
      Float previous = null;
      for (float b : baselines.descendingSet()) {
        if (previous == null) {
          previous = b;
        } else if (previous - b > s.sameLineFactor() * body) {
          gaps.add(previous - b);
          previous = b;
        }
      }
    }
    if (gaps.isEmpty()) {
      return 1.2f * body;
    }
    gaps.sort(null);
    return gaps.get(gaps.size() / 2);
  }

  private static boolean atBody(float size, float body, InferenceSettings s) {
    return Math.abs(size - body) <= s.blockFontSizeChange() * body;
  }

  private static boolean bodyText(LayoutBlock next, float body, InferenceSettings s) {
    return next.lines() > 0 && !next.bold() && atBody(round(next.bodySize()), body, s);
  }

  private static boolean endsLikeASentence(String text) {
    String t = text.strip();
    if (t.isEmpty()) {
      return false;
    }
    char last = t.charAt(t.length() - 1);
    return last == '.' || last == ',' || last == ';' || last == '!';

  }

  /** The most frequent run font size by character count, rounded to 0.5 pt (the smaller on a tie); 0 without text. */
  static float bodySize(List<LayoutBlock> blocks) {
    Map<Float, Long> chars = new TreeMap<>();
    for (LayoutBlock block : blocks) {
      for (TextRun run : block.runs()) {
        if (run.hasGlyphs() && !run.text().isBlank()) {
          chars.merge(round(run.fontSize()), (long) run.text().strip().length(), Long::sum);
        }
      }
    }
    float best = 0;
    long most = -1;
    for (Map.Entry<Float, Long> e : chars.entrySet()) {
      if (e.getValue() > most) {
        most = e.getValue();
        best = e.getKey();
      }
    }
    return best;
  }

  static float round(float size) {
    return Math.round(size * 2f) / 2f;
  }
}
