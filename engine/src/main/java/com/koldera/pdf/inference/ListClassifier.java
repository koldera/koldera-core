package com.koldera.pdf.inference;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists over the blocks of one page in reading order. An item starts with a bullet or an ordinal marker; consecutive items
 * of one marker family at the same indent form a list, a deeper marker nests. Confidence: bullets 1.0, strictly
 * consecutive ordinals 0.9, fewer than {@code listMinItems} items or gaps 0.5; stray single-letter ordinals ("p. 45") are
 * prose. The marker is an {@code Lbl} only when it is a text run of its own and the block holds more.
 */
public final class ListClassifier {

  /** One item: its block, the run holding only the marker ({@code null} → no {@code Lbl}), its nesting depth (0 = outermost). */
  public record Entry(LayoutBlock block, TextRun label, int depth, List<LayoutBlock> more) {
    public Entry(LayoutBlock block, TextRun label, int depth) {
      this(block, label, depth, List.of());
    }

    public Entry {
      more = List.copyOf(more);
    }
  }

  /**
   * A run of items in reading order with depths; {@code numbering} is the {@code /ListNumbering} of each
   * sibling sequence's list, indexed like {@code items} (the value for the list the item belongs to).
   */
  public record ListRun(List<Entry> items, double confidence, List<String> numbering) {
    public ListRun(List<Entry> items, double confidence) {
      this(items, confidence, List.of());
    }

    public ListRun {
      items = List.copyOf(items);
      numbering = List.copyOf(numbering);
    }
  }

  enum Family { BULLET, DECIMAL, ALPHA, ROMAN }

  /**
   * Bullets • – ▪ ◦ * ●, and U+F0B7: the Symbol-font bullet as Word and PDFsharp paint it (it extracts as
   * a private-use character).
   */
  private static final Pattern BULLET = Pattern.compile("^([\\x{2022}\\x{2013}\\x{25AA}\\x{25E6}*\\x{25CF}\\x{F0B7}])(?:\\s|$)");
  private static final Pattern ORDINAL = Pattern.compile("^(\\d{1,3}|[a-z]|[ivxlcdm]{1,6})([.)])\\s");
  /**
   * A numbered paragraph with a chapter prefix, closing punctuation required ({@code "7-1."}, {@code
   * "2.3.1)"}; not {@code "1.2 Scope"}): decimal, compared by its last number under the same prefix.
   */
  private static final Pattern PREFIXED = Pattern.compile("^((?:\\d{1,3}[.-]){1,3})(\\d{1,3})([.)])\\s");
  private static final Pattern BARE = Pattern.compile(
      "^(?:[\\x{2022}\\x{2013}\\x{25AA}\\x{25E6}*\\x{25CF}\\x{F0B7}]|(?:\\d{1,3}[.-]){0,3}\\d{1,3}[.)]|(?:[a-z]|[ivxlcdm]{1,6})[.)])$");
  private static final Pattern SINGLE_LETTER = Pattern.compile("^[a-z][.)]$");
  private static final Pattern ROMAN = Pattern.compile("^m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$");

  private ListClassifier() {}

  /** A marker at a block's start: its token ({@code "•"}, {@code "2."}), possible families, value per family, x positions. */
  record Marker(String token, String glyph, Set<Family> families, String prefix, int decimal, int alpha, int roman, float x, float textX0, TextRun run) {}

  /** Whether a line's text starts with a list marker (bullet or ordinal); PageLayout starts a block there. */
  static boolean startsWithMarker(String line) {
    String text = line.strip();
    return BULLET.matcher(text).find() || ORDINAL.matcher(text).find() || PREFIXED.matcher(text).find();
  }

  /** Where the item text starts after a leading marker and its white space; -1 without a marker. */
  static int markerEnd(String text) {
    for (Pattern p : new Pattern[] {BULLET, PREFIXED, ORDINAL}) {
      Matcher m = p.matcher(text);
      if (m.find()) {
        int end = m.end();
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
          end++;
        }
        return end;
      }
    }
    return -1;
  }

  /**
   * Whether {@code line} starts with the marker that continues the sequence of the item whose text starts
   * {@code itemText}: the same bullet glyph, or the next ordinal of a family both share under the same prefix
   * (1 → 2, a → b, i → ii, 7-1 → 7-2).
   */
  static boolean continues(String itemText, String line) {
    Marker a = parse(itemText.strip());
    Marker b = parse(line.strip());
    if (a == null || b == null) {
      return false;
    }
    if (a.glyph() != null || b.glyph() != null) {
      return a.glyph() != null && a.glyph().equals(b.glyph());
    }
    if (!a.prefix().equals(b.prefix())) {
      return false;
    }
    for (Family f : a.families()) {
      if (b.families().contains(f) && Builder.value(b, f) == Builder.value(a, f) + 1) {
        return true;
      }
    }
    return false;
  }

  /** The marker at the start of {@code text} without position (x fields 0, no run), or null. */
  private static Marker parse(String text) {
    Matcher bullet = BULLET.matcher(text);
    Matcher ordinal = ORDINAL.matcher(text);
    Matcher prefixed = PREFIXED.matcher(text);
    if (bullet.find()) {
      return new Marker(bullet.group(1), bullet.group(1), EnumSet.of(Family.BULLET), "", 0, 0, 0, 0, 0, null);
    }
    if (prefixed.find()) {
      String prefix = prefixed.group(1);
      return new Marker(prefix + prefixed.group(2) + prefixed.group(3), null, EnumSet.of(Family.DECIMAL), prefix, Integer.parseInt(prefixed.group(2)), 0, 0,
          0, 0, null);
    }
    if (!ordinal.find()) {
      return null;
    }
    String value = ordinal.group(1);
    String token = value + ordinal.group(2);
    Set<Family> families = EnumSet.noneOf(Family.class);
    if (Character.isDigit(value.charAt(0))) {
      return new Marker(token, null, EnumSet.of(Family.DECIMAL), "", Integer.parseInt(value), 0, 0, 0, 0, null);
    }
    int alpha = 0;
    int roman = 0;
    if (value.length() == 1) {
      families.add(Family.ALPHA);
      alpha = value.charAt(0) - 'a' + 1;
    }
    if (ROMAN.matcher(value).matches()) {
      families.add(Family.ROMAN);
      roman = romanValue(value);
    }
    return families.isEmpty() ? null : new Marker(token, null, families, "", 0, alpha, roman, 0, 0, null);
  }

  /** Whether the text (trimmed) is a list marker and nothing else ({@code "2."}, {@code "•"}). */
  static boolean isBareMarker(String text) {
    return BARE.matcher(text.strip()).matches();
  }

  public static List<ListRun> lists(List<LayoutBlock> pageBlocksInOrder, InferenceSettings s) {
    List<ListRun> out = new ArrayList<>();
    Builder current = null;
    for (LayoutBlock block : pageBlocksInOrder) {
      Marker marker = block.lines() > 0 ? marker(block, s) : null;
      if (marker == null) {
        if (current != null) {
          out.add(current.build(s));
          current = null;
        }
        continue;
      }
      if (current == null || !current.add(block, marker, s)) {
        if (current != null) {
          out.add(current.build(s));
        }
        current = new Builder();
        current.start(block, marker);
      }
    }
    if (current != null) {
      out.add(current.build(s));
    }
    return out;
  }

  /** The block's marker, or null. */
  static Marker marker(LayoutBlock block, InferenceSettings s) {
    Marker parsed = parse(block.text());
    if (parsed == null) {
      return null;
    }
    String token = parsed.token();
    String glyph = parsed.glyph();
    Set<Family> families = parsed.families();
    String prefix = parsed.prefix();
    int decimal = parsed.decimal();
    int alpha = parsed.alpha();
    int roman = parsed.roman();
    TextRun first = markerRun(block, token);
    if (first == null) {
      first = firstRun(block, s);
    }
    if (first == null) {
      return new Marker(token, glyph, families, prefix, decimal, alpha, roman, block.x0(), block.x0(), null);
    }
    boolean own = first.text().strip().equals(token);
    float textX0;
    if (own) {
      textX0 = Float.NaN;
      for (TextRun run : firstLine(block, first, s)) {
        if (run != first && run.x0() > first.x0() && (Float.isNaN(textX0) || run.x0() < textX0)) {
          textX0 = run.x0();
        }
      }
      if (Float.isNaN(textX0)) {
        textX0 = first.x1();
      }
    } else {
      String runText = first.text();
      int at = runText.indexOf(token);
      int end = at < 0 ? 0 : at + token.length();
      while (end < runText.length() && Character.isWhitespace(runText.charAt(end))) {
        end++;
      }
      textX0 = runText.isEmpty() ? first.x0() : first.x0() + (first.x1() - first.x0()) * end / runText.length();
    }
    boolean label = own && block.runs().size() > 1;
    return new Marker(token, glyph, families, prefix, decimal, alpha, roman, first.x0(), textX0, label ? first : null);
  }

  /** The run that shows the marker: the leftmost run with glyphs whose trimmed text starts with it; null when none. */
  private static TextRun markerRun(LayoutBlock block, String token) {
    TextRun best = null;
    for (TextRun run : block.runs()) {
      if (run.hasGlyphs() && run.text().strip().startsWith(token) && (best == null || run.x0() < best.x0())) {
        best = run;
      }
    }
    return best;
  }

  /** The leftmost run with glyphs on the block's first (highest) line. */
  private static TextRun firstRun(LayoutBlock block, InferenceSettings s) {
    TextRun top = null;
    for (TextRun run : block.runs()) {
      if (run.hasGlyphs() && !run.text().isBlank() && (top == null || run.baseline() > top.baseline())) {
        top = run;
      }
    }
    if (top == null) {
      return null;
    }
    TextRun left = top;
    for (TextRun run : firstLine(block, top, s)) {
      if (run.x0() < left.x0()) {
        left = run;
      }
    }
    return left;
  }

  private static List<TextRun> firstLine(LayoutBlock block, TextRun anchor, InferenceSettings s) {
    List<TextRun> out = new ArrayList<>();
    for (TextRun run : block.runs()) {
      if (run.hasGlyphs() && !run.text().isBlank() && Math.abs(run.baseline() - anchor.baseline()) <= s.sameLineFactor() * Math.max(run.fontSize(), anchor.fontSize())) {
        out.add(run);
      }
    }
    return out;
  }

  static int romanValue(String roman) {
    int total = 0;
    int previous = 0;
    for (int i = roman.length() - 1; i >= 0; i--) {
      int v = switch (roman.charAt(i)) {
        case 'i' -> 1;
        case 'v' -> 5;
        case 'x' -> 10;
        case 'l' -> 50;
        case 'c' -> 100;
        case 'd' -> 500;
        case 'm' -> 1000;
        default -> 0;
      };
      total += v < previous ? -v : v;
      previous = Math.max(previous, v);
    }
    return total;
  }

  /** One list level being built: its marker x, possible families and markers, and where its items sit in the run. */
  private static final class Level {
    final float x;
    final Set<Family> families;
    final List<Marker> markers = new ArrayList<>();
    final List<Integer> entries = new ArrayList<>();

    Level(float x, Set<Family> families) {
      this.x = x;
      this.families = EnumSet.copyOf(families);
    }
  }

  private static final class Builder {
    final List<Entry> entries = new ArrayList<>();
    final List<Level> levels = new ArrayList<>();
    /** Every level ever opened, for the confidence and numbering. */
    final List<Level> all = new ArrayList<>();
    /** The last item's marker (for nesting). */
    Marker last;

    void start(LayoutBlock block, Marker marker) {
      Level level = new Level(marker.x(), marker.families());
      levels.add(level);
      all.add(level);
      append(level, block, marker, 0);
    }

    boolean add(LayoutBlock block, Marker marker, InferenceSettings s) {
      float tol = s.listIndentTolerance();
      // A sibling at some open level (deepest first).
      for (int d = levels.size() - 1; d >= 0; d--) {
        Level level = levels.get(d);
        if (Math.abs(marker.x() - level.x) <= tol) {
          if (!compatible(level.families, marker.families())) {
            return false;
          }
          while (levels.size() > d + 1) {
            levels.remove(levels.size() - 1);
          }
          level.families.retainAll(marker.families());
          append(level, block, marker, d);
          return true;
        }
      }
      if (marker.x() >= last.textX0() - tol) {
        Level nested = new Level(marker.x(), marker.families());
        levels.add(nested);
        all.add(nested);
        append(nested, block, marker, levels.size() - 1);
        return true;
      }
      return false;
    }

    private void append(Level level, LayoutBlock block, Marker marker, int depth) {
      level.markers.add(marker);
      level.entries.add(entries.size());
      entries.add(new Entry(block, marker.run(), depth));
      last = marker;
    }

    private static boolean compatible(Set<Family> a, Set<Family> b) {
      for (Family f : a) {
        if (b.contains(f)) {
          return true;
        }
      }
      return false;
    }

    ListRun build(InferenceSettings s) {
      // A level of single-letter "ordinals" that do not start a real sequence is prose: at the top the run is no list
      // (confidence 0, not a fallback); nested, its items (and anything nested in them) rejoin the parent item's text.
      boolean[] dropped = new boolean[entries.size()];
      List<Level> kept = new ArrayList<>();
      for (Level level : all) {
        if (letterOrdinalsHold(level)) {
          kept.add(level);
          continue;
        }
        int depth = entries.get(level.entries.get(0)).depth();
        if (depth == 0) {
          return new ListRun(entries, 0.0, List.of());
        }
        for (int index : level.entries) {
          for (int k = index; k < entries.size() && (k == index || entries.get(k).depth() > depth); k++) {
            dropped[k] = true;
          }
        }
      }
      List<Entry> out = new ArrayList<>();
      int[] newIndex = new int[entries.size()];
      for (int i = 0; i < entries.size(); i++) {
        Entry entry = entries.get(i);
        if (!dropped[i]) {
          newIndex[i] = out.size();
          out.add(entry);
          continue;
        }
        newIndex[i] = -1;
        Entry parent = out.get(out.size() - 1); // a nested level always follows its parent item
        List<LayoutBlock> more = new ArrayList<>(parent.more());
        more.add(entry.block());
        more.addAll(entry.more());
        out.set(out.size() - 1, new Entry(parent.block(), parent.label(), parent.depth(), more));
      }
      double confidence = 1.0;
      String[] numbering = new String[out.size()];
      for (Level level : kept) {
        Family family = resolve(level);
        String value = numbering(family, level.markers.get(0));
        for (int index : level.entries) {
          if (newIndex[index] >= 0) {
            numbering[newIndex[index]] = value;
          }
        }
        if (family != Family.BULLET && !consecutive(level.markers, family)) {
          confidence = Math.min(confidence, 0.5);
        } else if (family != Family.BULLET) {
          confidence = Math.min(confidence, 0.9);
        }
      }
      if (out.size() < s.listMinItems()) {
        confidence = 0.5;
      }
      return new ListRun(out, confidence, List.of(numbering));
    }

    /**
     * A level that starts with a single-letter ordinal ({@code "a."}, {@code "v."}) counts only when it starts at
     * {@code a} or {@code i} and its second item follows consecutively.
     */
    private static boolean letterOrdinalsHold(Level level) {
      Marker first = level.markers.get(0);
      if (!SINGLE_LETTER.matcher(first.token()).matches()) {
        return true;
      }
      char letter = first.token().charAt(0);
      if ((letter != 'a' && letter != 'i') || level.markers.size() < 2) {
        return false;
      }
      return consecutive(level.markers.subList(0, 2), resolve(level));
    }

    /** The level's family: the one under which its markers are consecutive (alpha before roman), else the first possible. */
    private static Family resolve(Level level) {
      for (Family f : level.families) {
        if (consecutive(level.markers, f)) {
          return f;
        }
      }
      return level.families.iterator().next();
    }

    private static boolean consecutive(List<Marker> markers, Family family) {
      for (int i = 1; i < markers.size(); i++) {
        if (value(markers.get(i), family) != value(markers.get(i - 1), family) + 1 || !markers.get(i).prefix().equals(markers.get(i - 1).prefix())) {
          return false;
        }
      }
      return true;
    }

    static int value(Marker marker, Family family) {

      return switch (family) {
        case DECIMAL -> marker.decimal();
        case ALPHA -> marker.alpha();
        case ROMAN -> marker.roman();
        case BULLET -> 0;
      };
    }

    private static String numbering(Family family, Marker first) {
      return switch (family) {
        case DECIMAL -> "Decimal";
        case ALPHA -> "LowerAlpha";
        case ROMAN -> "LowerRoman";
        case BULLET -> switch (first.glyph() == null ? "" : first.glyph().toLowerCase(Locale.ROOT)) {
          case "•", "●", "" -> "Disc";
          case "◦" -> "Circle";
          case "▪" -> "Square";
          default -> "None";
        };
      };
    }
  }
}
