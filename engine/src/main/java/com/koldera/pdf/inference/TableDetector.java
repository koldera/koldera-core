package com.koldera.pdf.inference;

import com.koldera.pdf.structure.ContentTagger.ItemKind;
import com.koldera.pdf.structure.ContentTagger.ItemView;
import com.koldera.pdf.structure.ContentTagger.PageItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Tables of one page from its rulings and text runs (thresholds from {@link InferenceSettings}); artifacts and author-marked
 * items never take part. A ruled grid needs closed rulings and two rows and columns of text; an aligned table needs
 * consecutive rows whose columns share an alignment edge, and guards reject lists, prose columns and contents lists. Header rows are all-bold leading rows or a row below a ruling;
 * captions are nearby label blocks. Nothing here reorders or changes content.
 */
public final class TableDetector {

  /** One cell: its text runs and the content-order indexes of its text items (both empty for an empty cell). */
  public record Cell(List<TextRun> runs, List<Integer> itemIndexes) {
    public Cell {
      runs = List.copyOf(runs);
      itemIndexes = List.copyOf(itemIndexes);
    }
  }

  /**
   * A table: its box, rows of cells (every row as long as the widest), the number of header rows, the caption block (or
   * null) and whether it comes before the rows, and the confidence.
   */
  public record DetectedTable(PDRectangle box, List<List<Cell>> rows, int headerRows, LayoutBlock caption, boolean captionFirst, double confidence) {
    public DetectedTable {
      rows = rows.stream().map(List::copyOf).toList();
    }

    /** The text items of one row, ascending. */
    public List<Integer> rowItems(int row) {
      TreeSet<Integer> out = new TreeSet<>();
      for (Cell cell : rows.get(row)) {
        out.addAll(cell.itemIndexes());
      }
      return List.copyOf(out);
    }

    /** Every text item of the cells and the caption, ascending. */
    public List<Integer> items() {
      TreeSet<Integer> out = new TreeSet<>();
      for (int r = 0; r < rows.size(); r++) {
        out.addAll(rowItems(r));
      }
      if (caption != null) {
        out.addAll(caption.itemIndexes());
      }
      return List.copyOf(out);
    }

    DetectedTable withCaption(LayoutBlock block, boolean first) {
      return new DetectedTable(box, rows, headerRows, block, first, confidence);
    }
  }

  static final double RULED = 1.0;
  static final double ALIGNED = 0.8;
  static final double CROSSING = 0.3;
  /** Above this many rulings on a page no grid is searched (the touch test is quadratic). */
  static final int MAX_RULINGS = 2000;
  /** A dot leader, as in {@link HeadingClassifier}: four dots (optionally spaced) or two ellipses. */
  private static final Pattern DOT_LEADER = Pattern.compile("(?:\\.\\s?){4,}|\\x{2026}{2,}");
  /** Terminal punctuation at the end of a cell, optionally closed by quotes or brackets. */
  private static final Pattern TERMINAL = Pattern.compile("[.!?:;][\"')\\]\\u2019\\u201D]*$");
  /**
   * A caption: the word (any case), then a label (a number, optionally after a letter such as {@code A.2}, or a Roman
   * numeral), then punctuation, a word in upper case or the end ("Table 2 shows that…" and "table of contents" are prose).
   */
  private static final Pattern CAPTION = Pattern.compile(
      "^(?iu:Table|Tab\\.|Табл\\.|Таблиця)\\s*(?:[A-Z]?[.-]?\\p{N}[\\w.-]*|[IVXLC]+\\b\\.?)(?:\\s*[.:\\u2013\\u2014-]|\\s+\\p{Lu}|\\s*$)");

  private TableDetector() {}

  /** Whether a block's text starts like a table caption: the word and a label, then punctuation, an upper-case word or the end. */
  static boolean isCaption(String text) {
    return CAPTION.matcher(text).find();
  }

  public static List<DetectedTable> detect(PageItems page, List<LayoutBlock> blocks, Map<PDContentStream, List<TextRun>> runs, InferenceSettings s) {
    Map<COSBase, List<TextRun>> byStream = PageLayout.byStream(runs);
    Set<Integer> eligible = new HashSet<>();
    for (LayoutBlock block : blocks) {
      if (block.lines() > 0) {
        eligible.addAll(block.itemIndexes());
      }
    }
    List<Text> texts = new ArrayList<>();
    List<ItemView> adopted = new ArrayList<>();
    List<Ruling> horizontal = new ArrayList<>();
    List<Ruling> vertical = new ArrayList<>();
    float thick = s.rulingMaxThickness();
    for (ItemView item : page.items()) {
      if (item.kind() == ItemKind.TEXT && item.adopted()) {
        adopted.add(item);
      } else if (item.kind() == ItemKind.TEXT && eligible.contains(item.index())) {
        Text text = Text.of(item, PageLayout.runsOf(byStream, item), s);
        if (text != null) {
          texts.add(text);
        }
      } else if (item.kind() == ItemKind.PATH && item.bbox() != null) {
        PDRectangle b = item.bbox();
        if (b.getHeight() <= thick && b.getWidth() > thick) {
          horizontal.add(new Ruling((b.getLowerLeftY() + b.getUpperRightY()) / 2, b.getLowerLeftX(), b.getUpperRightX()));
        } else if (b.getWidth() <= thick && b.getHeight() > thick) {
          vertical.add(new Ruling((b.getLowerLeftX() + b.getUpperRightX()) / 2, b.getLowerLeftY(), b.getUpperRightY()));
        }
      }
    }
    float tol = s.tableColumnTolerance();
    horizontal = merge(horizontal, tol);
    vertical = merge(vertical, tol);

    List<DetectedTable> tables = new ArrayList<>();
    Set<Text> used = new HashSet<>();
    if (horizontal.size() + vertical.size() <= MAX_RULINGS) {
      for (Grid grid : grids(horizontal, vertical, tol)) {
        if (tables.stream().anyMatch(t -> overlaps(t.box(), grid.box()))) {
          continue;
        }
        DetectedTable table = ruled(grid, texts, adopted, used, horizontal, s);
        if (table != null) {
          tables.add(table);
        }
      }
    }
    List<Text> free = new ArrayList<>();
    for (Text text : texts) {
      if (!used.contains(text) && tables.stream().noneMatch(t -> inside(t.box(), text.cx(), text.cy(), tol))) {
        free.add(text);
      }
    }
    tables.addAll(aligned(free, horizontal, s));
    tables.sort(Comparator.comparingDouble((DetectedTable t) -> -t.box().getUpperRightY()).thenComparingDouble(t -> t.box().getLowerLeftX()));
    return captions(tables, blocks, s);
  }

  // ---------------------------------------------------------------- text items

  /** One eligible text item: its glyph runs and their extent; {@code multiRow} when its runs sit on several baselines. */
  private record Text(int index, List<TextRun> runs, float x0, float x1, float baseline, float top, float size, boolean bold, boolean multiRow,
      String text) {
    static Text of(ItemView item, List<TextRun> all, InferenceSettings s) {
      List<TextRun> runs = new ArrayList<>();
      for (TextRun run : all) {
        if (run.hasGlyphs()) {
          runs.add(run);
        }
      }
      if (runs.isEmpty()) {
        return null;
      }
      Map<Float, Integer> chars = new LinkedHashMap<>();
      int bold = 0;
      int count = 0;
      float x0 = Float.MAX_VALUE;
      float x1 = -Float.MAX_VALUE;
      float top = -Float.MAX_VALUE;
      StringBuilder text = new StringBuilder();
      for (TextRun run : runs) {
        int n = Math.max(1, run.text().length());
        chars.merge(run.fontSize(), n, Integer::sum);
        count += n;
        bold += run.bold() ? n : 0;
        x0 = Math.min(x0, run.x0());
        x1 = Math.max(x1, run.x1());
        top = Math.max(top, run.top());
        text.append(run.text()).append(' ');
      }
      float size = 0;
      int most = -1;
      for (Map.Entry<Float, Integer> e : chars.entrySet()) {
        if (e.getValue() > most) {
          most = e.getValue();
          size = e.getKey();
        }
      }
      size = size > 0 ? size : Math.max(1f, item.size());
      float baseline = runs.get(0).baseline();
      boolean multiRow = false;
      for (TextRun run : runs) {
        multiRow |= Math.abs(run.baseline() - baseline) > s.sameLineFactor() * size;
      }
      return new Text(item.index(), List.copyOf(runs), x0, x1, baseline, top, size, bold * 2 > count, multiRow, PageLayout.normalize(text.toString()));
    }

    float cx() {
      return (x0 + x1) / 2;
    }

    float cy() {
      return baseline + size / 2;
    }
  }

  // ---------------------------------------------------------------- rulings and grids

  /** A ruling: {@code at} its y (horizontal) or x (vertical), {@code from..to} its extent along it. */
  private record Ruling(float at, float from, float to) {}

  /** Collinear pieces (same {@code at} within the tolerance) that touch or overlap are one ruling. */
  private static List<Ruling> merge(List<Ruling> rulings, float tol) {
    List<Ruling> sorted = new ArrayList<>(rulings);
    sorted.sort(Comparator.comparingDouble(Ruling::at).thenComparingDouble(Ruling::from));
    List<Ruling> out = new ArrayList<>();
    int i = 0;
    while (i < sorted.size()) {
      int j = i;
      float first = sorted.get(i).at();
      while (j < sorted.size() && sorted.get(j).at() - first <= tol) {
        j++;
      }
      List<Ruling> line = new ArrayList<>(sorted.subList(i, j));
      line.sort(Comparator.comparingDouble(Ruling::from));
      float at = 0;
      for (Ruling r : line) {
        at += r.at();
      }
      at /= line.size();
      Ruling current = null;
      for (Ruling r : line) {
        if (current != null && r.from() <= current.to() + tol) {
          current = new Ruling(at, current.from(), Math.max(current.to(), r.to()));
        } else {
          if (current != null) {
            out.add(current);
          }
          current = new Ruling(at, r.from(), r.to());
        }
      }
      out.add(current);
      i = j;
    }
    return out;
  }

  /** A closed grid: distinct ruling positions, {@code ys} top to bottom, {@code xs} left to right. */
  private record Grid(List<Float> xs, List<Float> ys) {
    PDRectangle box() {
      float x0 = xs.get(0);
      float y1 = ys.get(0);
      return new PDRectangle(x0, ys.get(ys.size() - 1), xs.get(xs.size() - 1) - x0, y1 - ys.get(ys.size() - 1));
    }
  }

  private static List<Grid> grids(List<Ruling> horizontal, List<Ruling> vertical, float tol) {
    int h = horizontal.size();
    int[] parent = new int[h + vertical.size()];
    for (int i = 0; i < parent.length; i++) {
      parent[i] = i;
    }
    for (int i = 0; i < h; i++) {
      Ruling hr = horizontal.get(i);
      for (int j = 0; j < vertical.size(); j++) {
        Ruling vr = vertical.get(j);
        if (vr.at() >= hr.from() - tol && vr.at() <= hr.to() + tol && hr.at() >= vr.from() - tol && hr.at() <= vr.to() + tol) {
          union(parent, i, h + j);
        }
      }
    }
    Map<Integer, List<Integer>> components = new TreeMap<>();
    for (int i = 0; i < parent.length; i++) {
      components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
    }
    List<Grid> out = new ArrayList<>();
    for (List<Integer> members : components.values()) {
      List<Ruling> hs = new ArrayList<>();
      List<Ruling> vs = new ArrayList<>();
      for (int m : members) {
        if (m < h) {
          hs.add(horizontal.get(m));
        } else {
          vs.add(vertical.get(m - h));
        }
      }
      List<Float> ys = distinct(hs, tol);
      List<Float> xs = distinct(vs, tol);
      if (ys.size() < 3 || xs.size() < 3) {
        continue;
      }
      ys = ys.reversed();
      float minX = xs.get(0);
      float maxX = xs.get(xs.size() - 1);
      float maxY = ys.get(0);
      float minY = ys.get(ys.size() - 1);
      if (spans(hs, maxY, minX, maxX, tol) && spans(hs, minY, minX, maxX, tol) && spans(vs, minX, minY, maxY, tol) && spans(vs, maxX, minY, maxY, tol)) {
        out.add(new Grid(List.copyOf(xs), List.copyOf(ys)));
      }
    }
    out.sort(Comparator.comparingDouble((Grid g) -> -g.ys().get(0)).thenComparingDouble(g -> g.xs().get(0)));
    return out;
  }

  private static int find(int[] parent, int i) {
    while (parent[i] != i) {
      parent[i] = parent[parent[i]];
      i = parent[i];
    }
    return i;
  }

  private static void union(int[] parent, int a, int b) {
    int ra = find(parent, a);
    int rb = find(parent, b);
    if (ra != rb) {
      parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }
  }

  /** The distinct positions of the rulings, ascending (positions within the tolerance are one). */
  private static List<Float> distinct(List<Ruling> rulings, float tol) {
    List<Float> at = new ArrayList<>();
    for (Ruling r : rulings) {
      at.add(r.at());
    }
    at.sort(Float::compare);
    List<Float> out = new ArrayList<>();
    for (float a : at) {
      if (out.isEmpty() || a - out.get(out.size() - 1) > tol) {
        out.add(a);
      }
    }
    return out;
  }

  /** Whether a ruling at {@code at} covers {@code from..to} (ends within the tolerance). */
  private static boolean spans(List<Ruling> rulings, float at, float from, float to, float tol) {
    for (Ruling r : rulings) {
      if (Math.abs(r.at() - at) <= tol && r.from() <= from + tol && r.to() >= to - tol) {
        return true;
      }
    }
    return false;
  }

  private static DetectedTable ruled(Grid grid, List<Text> texts, List<ItemView> adopted, Set<Text> used, List<Ruling> horizontal, InferenceSettings s) {
    float tol = s.tableColumnTolerance();
    PDRectangle box = grid.box();
    for (ItemView item : adopted) {
      if (inside(box, item.x(), item.y(), tol)) {
        return null; // author-marked content inside the grid: its structure is not guessed
      }
    }
    List<Float> xs = grid.xs();
    List<Float> ys = grid.ys();
    int rows = ys.size() - 1;
    int cols = xs.size() - 1;
    List<List<List<Text>>> cells = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      List<List<Text>> row = new ArrayList<>();
      for (int c = 0; c < cols; c++) {
        row.add(new ArrayList<>());
      }
      cells.add(row);
    }
    boolean crossing = false;
    for (Text text : texts) {
      int in = 0;
      int cell = -1;
      boolean split = false;
      for (TextRun run : text.runs()) {
        float cx = (run.x0() + run.x1()) / 2;
        float cy = run.baseline() + run.fontSize() / 2;
        if (!inside(box, cx, cy, tol)) {
          continue;
        }
        in++;
        int c = slot(xs, cx);
        int r = rows - 1 - slot(ys.reversed(), cy);
        int at = r * cols + c;
        split |= cell >= 0 && cell != at;
        cell = cell < 0 ? at : cell;
        for (int k = 1; k < xs.size() - 1; k++) {
          crossing |= run.x0() < xs.get(k) - tol && run.x1() > xs.get(k) + tol;
        }
      }
      if (in == 0) {
        continue;
      }
      crossing |= split || in < text.runs().size();
      cells.get(cell / cols).get(cell % cols).add(text);
    }
    List<Integer> keptRows = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      if (cells.get(r).stream().anyMatch(TableDetector::visible)) {
        keptRows.add(r);
      }
    }
    List<Integer> keptCols = new ArrayList<>();
    for (int c = 0; c < cols; c++) {
      final int col = c;
      if (cells.stream().anyMatch(row -> visible(row.get(col)))) {
        keptCols.add(c);
      }
    }
    if (keptRows.size() < 2 || keptCols.size() < 2) {
      return null;
    }
    // The white space of a dropped row or column (it holds no visible text) joins the nearest kept cell, so no stray
    // block is left in the table area.
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        if (keptRows.contains(r) && keptCols.contains(c)) {
          continue;
        }
        List<Text> blanks = cells.get(r).get(c);
        if (!blanks.isEmpty()) {
          cells.get(closest(keptRows, r)).get(closest(keptCols, c)).addAll(blanks);
          blanks.clear();
        }
      }
    }
    List<List<Cell>> out = new ArrayList<>();
    List<List<Text>> rowTexts = new ArrayList<>();
    for (int r : keptRows) {
      List<Cell> row = new ArrayList<>();
      List<Text> all = new ArrayList<>();
      for (int c : keptCols) {
        List<Text> cellTexts = cells.get(r).get(c);
        cellTexts.sort(Comparator.comparingInt(Text::index));
        row.add(cell(cellTexts));
        used.addAll(cellTexts);
        cellTexts.stream().filter(t -> !t.text().isBlank()).forEach(all::add);
      }
      out.add(row);
      rowTexts.add(all);
    }
    return new DetectedTable(box, out, headerRows(rowTexts, horizontal, box, s), null, false, crossing ? CROSSING : RULED);
  }

  /** The kept index nearest {@code index} (the lower one on a tie). */
  private static int closest(List<Integer> kept, int index) {
    int best = kept.get(0);
    for (int k : kept) {
      if (Math.abs(k - index) < Math.abs(best - index)) {
        best = k;
      }
    }
    return best;
  }

  /** The index of the slot {@code value} falls in between ascending {@code edges} (clamped to the outer slots). */
  private static int slot(List<Float> edges, float value) {
    for (int k = 1; k < edges.size() - 1; k++) {
      if (value < edges.get(k)) {
        return k - 1;
      }
    }
    return edges.size() - 2;
  }

  /** A list marker, or a lone glyph that is neither letter nor digit (a bullet whose font maps it to nothing readable). */
  private static boolean markerLike(String text) {
    String t = text.strip();
    return ListClassifier.isBareMarker(t) || (t.codePointCount(0, t.length()) == 1 && !Character.isLetterOrDigit(t.codePointAt(0)));
  }

  /** Whether a cell's texts show anything but white space. */
  private static boolean visible(List<Text> texts) {
    return texts.stream().anyMatch(t -> !t.text().isBlank());
  }

  private static Cell cell(List<Text> texts) {
    List<TextRun> runs = new ArrayList<>();
    List<Integer> items = new ArrayList<>();
    for (Text text : texts) {
      runs.addAll(text.runs());
      items.add(text.index());
    }
    return new Cell(runs, items);
  }

  private static boolean inside(PDRectangle box, float x, float y, float tol) {
    return x >= box.getLowerLeftX() - tol && x <= box.getUpperRightX() + tol && y >= box.getLowerLeftY() - tol && y <= box.getUpperRightY() + tol;
  }

  private static boolean overlaps(PDRectangle a, PDRectangle b) {
    return a.getLowerLeftX() < b.getUpperRightX() && b.getLowerLeftX() < a.getUpperRightX() && a.getLowerLeftY() < b.getUpperRightY()
        && b.getLowerLeftY() < a.getUpperRightY();
  }

  // ---------------------------------------------------------------- aligned tables

  /** A row of text items on one baseline, cut into cells ({@code segments}). */
  private static final class Row {
    final float baseline;
    final float size;
    final List<Text> texts = new ArrayList<>();
    boolean poisoned;
    List<Segment> segments;
    /** An anchor row's wrapped cell lines below it (rows of fewer cells at line pitch). */
    final List<Row> continuation = new ArrayList<>();

    Row(float baseline, float size) {
      this.baseline = baseline;
      this.size = size;
    }
  }

  /** One cell of a row: neighbouring text items, left to right. */
  private record Segment(List<Text> texts, float x0, float x1) {
    String text() {
      StringBuilder sb = new StringBuilder();
      for (Text t : texts) {
        sb.append(t.text()).append(' ');
      }
      return PageLayout.normalize(sb.toString());
    }
  }

  private static List<DetectedTable> aligned(List<Text> all, List<Ruling> horizontal, InferenceSettings s) {
    List<Text> texts = new ArrayList<>();
    List<Text> blanks = new ArrayList<>();
    for (Text text : all) {
      (text.text().isBlank() ? blanks : texts).add(text);
    }
    List<Row> rows = rows(texts, s);
    List<DetectedTable> out = new ArrayList<>();
    List<Row> anchors = new ArrayList<>();
    Row last = null;
    for (Row row : rows) {
      boolean anchor = !row.poisoned && row.segments.size() >= s.tableMinColumns();
      float size = last == null ? row.size : Math.max(last.size, row.size);
      float gap = last == null ? 0 : last.baseline - row.baseline;
      if (anchor) {
        if (!anchors.isEmpty() && gap > s.tableRowGapFactor() * size) {
          evaluate(anchors, horizontal, s, out);
          anchors = new ArrayList<>();
        }
        anchors.add(row);
        last = row;
      } else if (!row.poisoned && !anchors.isEmpty() && gap <= s.blockGapFactor() * size) {
        anchors.get(anchors.size() - 1).continuation.add(row); // a wrapped line of a cell (checked against the columns later)
        last = row;
      } else {
        evaluate(anchors, horizontal, s, out);
        anchors = new ArrayList<>();
        last = null;
      }
    }
    evaluate(anchors, horizontal, s, out);
    return blanks.isEmpty() ? out : withBlanks(out, all, blanks);
  }

  /**
   * White-space runs (Word paints lone spaces at cell starts and ends) take no part in rows, cells or gutters; one
   * lying at a table's row (its baseline within one size of the row's) and within one size of the table's sides joins
   * the cell of the nearest column, so it does not become a stray paragraph.
   */
  private static List<DetectedTable> withBlanks(List<DetectedTable> tables, List<Text> all, List<Text> blanks) {
    Map<Integer, Text> byIndex = new HashMap<>();
    for (Text text : all) {
      byIndex.put(text.index(), text);
    }
    List<DetectedTable> out = new ArrayList<>();
    for (DetectedTable table : tables) {
      int rows = table.rows().size();
      int cols = table.rows().get(0).size();
      float[][] rowRange = new float[rows][];
      float[][] colRange = new float[cols][];
      for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
          for (TextRun run : table.rows().get(r).get(c).runs()) {
            if (run.text().isBlank()) {
              continue;
            }
            rowRange[r] = rowRange[r] == null ? new float[] {run.baseline(), run.baseline()}
                : new float[] {Math.min(rowRange[r][0], run.baseline()), Math.max(rowRange[r][1], run.baseline())};
            colRange[c] = colRange[c] == null ? new float[] {run.x0(), run.x1()} : new float[] {Math.min(colRange[c][0], run.x0()), Math.max(colRange[c][1], run.x1())};
          }
        }
      }
      List<List<List<Text>>> cells = new ArrayList<>();
      for (int r = 0; r < rows; r++) {
        List<List<Text>> row = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
          List<Text> texts = new ArrayList<>();
          for (int index : table.rows().get(r).get(c).itemIndexes()) {
            texts.add(byIndex.get(index));
          }
          row.add(texts);
        }
        cells.add(row);
      }
      PDRectangle box = table.box();
      for (Text blank : blanks) {
        float size = blank.size();
        if (blank.cx() < box.getLowerLeftX() - size || blank.cx() > box.getUpperRightX() + size) {
          continue;
        }
        int row = nearest(rowRange, blank.baseline(), size);
        int col = nearest(colRange, blank.cx(), Float.MAX_VALUE);
        if (row >= 0 && col >= 0) {
          cells.get(row).get(col).add(blank);
        }
      }
      List<List<Cell>> rebuilt = new ArrayList<>();
      for (List<List<Text>> row : cells) {
        List<Cell> cellRow = new ArrayList<>();
        for (List<Text> texts : row) {
          texts.sort(Comparator.comparingInt(Text::index));
          cellRow.add(cell(texts));
        }
        rebuilt.add(cellRow);
      }
      out.add(new DetectedTable(box, rebuilt, table.headerRows(), null, false, table.confidence()));
    }
    return out;
  }

  /** The index of the range nearest {@code value} (0 inside it), if within {@code reach}; -1 otherwise. Null ranges are skipped. */
  private static int nearest(float[][] ranges, float value, float reach) {
    int best = -1;
    float bestDistance = Float.MAX_VALUE;
    for (int i = 0; i < ranges.length; i++) {
      if (ranges[i] == null) {
        continue;
      }
      float distance = value < ranges[i][0] ? ranges[i][0] - value : value > ranges[i][1] ? value - ranges[i][1] : 0;
      if (distance < bestDistance && distance <= reach) {
        best = i;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** Rows top to bottom; larger text first so a smaller raised piece joins the row it is raised from. */
  private static List<Row> rows(List<Text> texts, InferenceSettings s) {
    List<Text> order = new ArrayList<>(texts);
    order.sort(Comparator.comparingDouble((Text t) -> -t.size()).thenComparingDouble(t -> -t.baseline()).thenComparingInt(Text::index));
    NavigableMap<Float, List<Row>> byBaseline = new TreeMap<>();
    List<Row> all = new ArrayList<>();
    float maxSize = 1f;
    for (Text t : texts) {
      maxSize = Math.max(maxSize, t.size());
    }
    float reach = Math.max(s.sameLineFactor(), s.superscriptRiseFactor()) * maxSize;
    for (Text text : order) {
      Row best = null;
      float bestDelta = Float.MAX_VALUE;
      for (List<Row> near : byBaseline.subMap(text.baseline() - reach, true, text.baseline() + reach, true).values()) {
        for (Row row : near) {
          float delta = Math.abs(row.baseline - text.baseline());
          float size = Math.max(row.size, text.size());
          boolean same = delta <= s.sameLineFactor() * size;
          boolean raised = text.size() <= (1 - s.blockFontSizeChange()) * row.size && delta <= s.superscriptRiseFactor() * row.size;
          if ((same || raised) && delta < bestDelta) {
            best = row;
            bestDelta = delta;
          }
        }
      }
      if (best == null) {
        best = new Row(text.baseline(), text.size());
        byBaseline.computeIfAbsent(text.baseline(), k -> new ArrayList<>()).add(best);
        all.add(best);
      }
      best.texts.add(text);
    }
    for (Text text : texts) {
      if (!text.multiRow()) {
        continue;
      }
      for (TextRun r : text.runs()) {
        for (List<Row> near : byBaseline.subMap(r.baseline() - reach, true, r.baseline() + reach, true).values()) {
          for (Row row : near) {
            if (Math.abs(row.baseline - r.baseline()) <= s.sameLineFactor() * Math.max(row.size, text.size()) || row.texts.contains(text)) {
              row.poisoned = true;
            }
          }
        }
      }
    }
    for (Row row : all) {
      row.texts.sort(Comparator.comparingDouble(Text::x0).thenComparingInt(Text::index));
      List<Segment> segments = new ArrayList<>();
      List<Text> current = new ArrayList<>();
      float x0 = 0;
      float x1 = 0;
      for (Text text : row.texts) {
        if (!current.isEmpty() && text.x0() - x1 <= s.tableCellGapFactor() * Math.max(row.size, text.size())) {
          current.add(text);
          x1 = Math.max(x1, text.x1());
          continue;
        }
        if (!current.isEmpty()) {
          segments.add(new Segment(List.copyOf(current), x0, x1));
        }
        current = new ArrayList<>(List.of(text));
        x0 = text.x0();
        x1 = text.x1();
      }
      if (!current.isEmpty()) {
        segments.add(new Segment(List.copyOf(current), x0, x1));
      }
      row.segments = segments;
    }
    all.sort(Comparator.comparingDouble((Row r) -> -r.baseline));
    return all;
  }

  /** The column of a cell: the column region its centre lies in (gutters left of it counted). */
  private static int column(Segment seg, List<float[]> gutters) {
    float centre = (seg.x0() + seg.x1()) / 2;
    int c = 0;
    for (float[] g : gutters) {
      if (centre >= (g[0] + g[1]) / 2) {
        c++;
      }
    }
    return c;
  }

  /** Whether the cell covers a whole gutter (a run across a column start). */
  private static boolean across(Segment seg, List<float[]> gutters) {
    for (float[] g : gutters) {
      if (seg.x0() <= g[0] && seg.x1() >= g[1]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Adds the table of the anchor rows {@code rows} (with their wrapped lines) to {@code out}, or of the parts left when
   * rows that break the columns are taken out.
   */
  private static void evaluate(List<Row> rows, List<Ruling> horizontal, InferenceSettings s, List<DetectedTable> out) {
    if (rows.size() < s.tableMinAlignedRows()) {
      return;
    }
    float tol = s.tableColumnTolerance();
    List<float[]> gutters = gutters(rows, tol);
    int columns = gutters.size() + 1;
    if (columns < s.tableMinColumns()) {
      return;
    }
    // A wrapped line stays inside one column (it covers no gutter); the first line that does not is prose after the
    // table: the table ends before it and the rows after it are tried on their own.
    for (int r = 0; r < rows.size(); r++) {
      List<Row> lines = rows.get(r).continuation;
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).segments.stream().anyMatch(seg -> across(seg, gutters))) {
          lines.subList(i, lines.size()).clear();
          evaluate(rows.subList(0, r + 1), horizontal, s, out);
          evaluate(rows.subList(r + 1, rows.size()), horizontal, s, out);
          return;
        }
      }
    }
    boolean crossing = false;
    List<int[]> columnOf = new ArrayList<>();
    List<Integer> broken = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
      Row row = rows.get(r);
      int[] cols = new int[row.segments.size()];
      Set<Integer> filled = new HashSet<>();
      boolean twice = false;
      for (int k = 0; k < row.segments.size(); k++) {
        Segment seg = row.segments.get(k);
        crossing |= across(seg, gutters);
        cols[k] = column(seg, gutters);
        twice |= !filled.add(cols[k]);
      }
      columnOf.add(cols);
      // A wrapped line continues a filled cell of its row.
      boolean wraps = true;
      for (Row line : row.continuation) {
        for (Segment seg : line.segments) {
          wraps &= filled.contains(column(seg, gutters));
        }
      }
      if (twice || filled.size() < columns - 1 || !wraps) {
        broken.add(r);
      }
    }
    if (!broken.isEmpty()) {
      int from = 0;
      for (int b : broken) {
        evaluate(rows.subList(from, b), horizontal, s, out);
        from = b + 1;
      }
      evaluate(rows.subList(from, rows.size()), horizontal, s, out);
      return;
    }
    // Every column's cells (not those across a gutter) share a left edge, a right edge or a centre; the first row may
    // differ (a header centred over left-aligned cells).
    List<List<Segment>> byColumn = new ArrayList<>();
    List<List<Segment>> byColumnBelowFirst = new ArrayList<>();
    for (int c = 0; c < columns; c++) {
      byColumn.add(new ArrayList<>());
      byColumnBelowFirst.add(new ArrayList<>());
    }
    for (int r = 0; r < rows.size(); r++) {
      List<Segment> segments = rows.get(r).segments;
      for (int k = 0; k < segments.size(); k++) {
        Segment seg = segments.get(k);
        if (!across(seg, gutters)) {
          byColumn.get(columnOf.get(r)[k]).add(seg);
          if (r > 0) {
            byColumnBelowFirst.get(columnOf.get(r)[k]).add(seg);
          }
        }
      }
    }
    int prose = 0;
    for (int c = 0; c < columns; c++) {
      List<Segment> column = byColumn.get(c);
      if (!column.isEmpty() && !sharesEdge(column, tol) && !sharesEdge(byColumnBelowFirst.get(c), tol)) {
        return;
      }
      List<Integer> chars = new ArrayList<>();
      for (Segment seg : column) {
        chars.add(seg.text().length());
      }
      chars.sort(Integer::compare);
      if (!chars.isEmpty() && chars.get((chars.size() - 1) / 2) >= s.columnMinLineChars()) {
        prose++;
      }
    }
    if (prose >= 2) {
      return; // columns of prose on shared baselines
    }
    for (List<Segment> column : byColumn) {
      if (column.stream().filter(seg -> DOT_LEADER.matcher(seg.text()).find()).count() * 2 >= column.size() && !column.isEmpty()) {
        return; // a table of contents: entries with dot leaders and their page numbers
      }
    }
    if (columns == 2 && !byColumn.get(0).isEmpty() && byColumn.get(0).stream().filter(seg -> markerLike(seg.text())).count() * 2 >= byColumn.get(0).size()) {
      return; // list markers and their items (with a third column it is a numbered table)
    }
    if (drawnColumnByColumn(rows, columnOf, gutters, columns) || proseRunsOn(rows, columnOf, columns)) {
      return;
    }
    List<List<Cell>> cells = new ArrayList<>();
    List<List<Text>> rowTexts = new ArrayList<>();
    float x0 = Float.MAX_VALUE;
    float x1 = -Float.MAX_VALUE;
    float y0 = Float.MAX_VALUE;
    float y1 = -Float.MAX_VALUE;
    for (int r = 0; r < rows.size(); r++) {
      Row row = rows.get(r);
      List<List<Text>> slots = new ArrayList<>();
      for (int c = 0; c < columns; c++) {
        slots.add(new ArrayList<>());
      }
      List<Segment> all = new ArrayList<>(row.segments);
      List<Integer> at = new ArrayList<>();
      for (int k = 0; k < row.segments.size(); k++) {
        at.add(columnOf.get(r)[k]);
      }
      for (Row line : row.continuation) {
        for (Segment seg : line.segments) {
          all.add(seg);
          at.add(column(seg, gutters));
        }
      }
      List<Text> texts = new ArrayList<>();
      for (int k = 0; k < all.size(); k++) {
        Segment seg = all.get(k);
        slots.get(at.get(k)).addAll(seg.texts());
        texts.addAll(seg.texts());
        x0 = Math.min(x0, seg.x0());
        x1 = Math.max(x1, seg.x1());
        for (Text t : seg.texts()) {
          y0 = Math.min(y0, t.baseline());
          y1 = Math.max(y1, t.top());
        }
      }
      List<Cell> cellRow = new ArrayList<>();
      for (List<Text> slot : slots) {
        slot.sort(Comparator.comparingInt(Text::index));
        cellRow.add(cell(slot));
      }
      cells.add(cellRow);
      rowTexts.add(texts);
    }
    PDRectangle box = new PDRectangle(x0, y0, x1 - x0, y1 - y0);
    out.add(new DetectedTable(box, cells, headerRows(rowTexts, horizontal, box, s), null, false, crossing ? CROSSING : ALIGNED));
  }

  /**
   * Whether some split between columns has every text item left of it drawn before every item right of it: blocks drawn
   * one after another (two addresses, a sidebar, the boxes of a slide), not a table, which is drawn row by row (fail
   * closed toward the content order).
   */
  private static boolean drawnColumnByColumn(List<Row> rows, List<int[]> columnOf, List<float[]> gutters, int columns) {
    int[] first = new int[columns];
    int[] last = new int[columns];
    java.util.Arrays.fill(first, Integer.MAX_VALUE);
    java.util.Arrays.fill(last, Integer.MIN_VALUE);
    for (int r = 0; r < rows.size(); r++) {
      Row row = rows.get(r);
      for (int k = 0; k < row.segments.size(); k++) {
        mark(row.segments.get(k), columnOf.get(r)[k], first, last);
      }
      for (Row line : row.continuation) {
        for (Segment seg : line.segments) {
          mark(seg, column(seg, gutters), first, last);
        }
      }
    }
    for (int split = 1; split < columns; split++) {
      int lastLeft = Integer.MIN_VALUE;
      int firstRight = Integer.MAX_VALUE;
      for (int c = 0; c < columns; c++) {
        if (c < split) {
          lastLeft = Math.max(lastLeft, last[c]);
        } else {
          firstRight = Math.min(firstRight, first[c]);
        }
      }
      if (lastLeft < firstRight) {
        return true;
      }
    }
    return false;
  }

  private static void mark(Segment seg, int column, int[] first, int[] last) {
    for (Text t : seg.texts()) {
      first[column] = Math.min(first[column], t.index());
      last[column] = Math.max(last[column], t.index());
    }
  }

  /**
   * Whether a column reads as prose drawn across the columns line by line: a cell of several words without terminal
   * punctuation is followed in the same column by a cell of several words that starts in lower case.
   */
  private static boolean proseRunsOn(List<Row> rows, List<int[]> columnOf, int columns) {
    String[] previous = new String[columns];
    for (int r = 0; r < rows.size(); r++) {
      String[] current = new String[columns];
      List<Segment> segments = rows.get(r).segments;
      for (int k = 0; k < segments.size(); k++) {
        current[columnOf.get(r)[k]] = segments.get(k).text();
      }
      for (int c = 0; c < columns; c++) {
        String above = previous[c];
        String below = current[c];
        if (above != null && below != null && above.contains(" ") && below.contains(" ") && !TERMINAL.matcher(above).find()
            && Character.isLowerCase(below.codePointAt(0))) {
          return true;
        }
      }
      previous = current;
    }
    return false;
  }

  /**
   * Inner gutters {from, to}, left to right: maximal x ranges between the rows' cells that the cells of at most one row
   * cover, at least the tolerance wide.
   */
  private static List<float[]> gutters(List<Row> rows, float tol) {
    TreeMap<Float, Integer> events = new TreeMap<>();
    float min = Float.MAX_VALUE;
    float max = -Float.MAX_VALUE;
    for (Row row : rows) {
      for (Segment seg : row.segments) {
        events.merge(seg.x0(), 1, Integer::sum);
        events.merge(seg.x1(), -1, Integer::sum);
        min = Math.min(min, seg.x0());
        max = Math.max(max, seg.x1());
      }
    }
    List<float[]> out = new ArrayList<>();
    int coverage = 0;
    float start = Float.NaN;
    for (Map.Entry<Float, Integer> e : events.entrySet()) {
      int next = coverage + e.getValue();
      float x = e.getKey();
      if (coverage > 1 && next <= 1 && x < max) {
        start = x;
      } else if (coverage <= 1 && next > 1 && !Float.isNaN(start)) {
        if (x - start >= tol && start > min) {
          out.add(new float[] {start, x});
        }
        start = Float.NaN;
      }
      coverage = next;
    }
    return out;
  }

  /** Whether the cells share their left edge, right edge or centre within the tolerance. */
  private static boolean sharesEdge(List<Segment> column, float tol) {
    float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
    float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    for (Segment seg : column) {
      float[] v = {seg.x0(), seg.x1(), (seg.x0() + seg.x1()) / 2};
      for (int k = 0; k < 3; k++) {
        lo[k] = Math.min(lo[k], v[k]);
        hi[k] = Math.max(hi[k], v[k]);
      }
    }
    for (int k = 0; k < 3; k++) {
      if (hi[k] - lo[k] <= tol) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- header rows and captions

  /**
   * The leading rows whose text is all bold (not every row); else 1 when a horizontal ruling across at least half the
   * table lies between the first and second row and between no other two consecutive rows; else 0.
   */
  private static int headerRows(List<List<Text>> rows, List<Ruling> horizontal, PDRectangle box, InferenceSettings s) {
    int bold = 0;
    while (bold < rows.size() && !rows.get(bold).isEmpty() && rows.get(bold).stream().allMatch(Text::bold)) {
      bold++;
    }
    if (bold > 0 && bold < rows.size()) {
      return bold;
    }
    if (bold == rows.size() || rows.size() < 2) {
      return 0;
    }
    float tol = s.tableColumnTolerance();
    float width = box.getWidth();
    boolean first = false;
    for (int r = 0; r + 1 < rows.size(); r++) {
      float above = Float.MAX_VALUE;
      for (Text t : rows.get(r)) {
        above = Math.min(above, t.baseline());
      }
      float below = -Float.MAX_VALUE;
      for (Text t : rows.get(r + 1)) {
        below = Math.max(below, t.baseline() + t.size() / 2);
      }
      boolean ruled = false;
      for (Ruling h : horizontal) {
        float overlap = Math.min(h.to(), box.getUpperRightX()) - Math.max(h.from(), box.getLowerLeftX());
        ruled |= h.at() > below && h.at() <= above + tol && overlap >= 0.5f * width;
      }
      if (r == 0) {
        first = ruled;
      } else if (ruled) {
        return 0;
      }
    }
    return first ? 1 : 0;
  }

  private static List<DetectedTable> captions(List<DetectedTable> tables, List<LayoutBlock> blocks, InferenceSettings s) {
    Set<Integer> cellItems = new HashSet<>();
    for (DetectedTable table : tables) {
      cellItems.addAll(table.items());
    }
    List<LayoutBlock> candidates = new ArrayList<>();
    for (LayoutBlock block : blocks) {
      if (block.lines() > 0 && block.lines() <= s.captionMaxLines() && block.hasGeometry() && isCaption(block.text())
          && block.itemIndexes().stream().noneMatch(cellItems::contains)) {
        candidates.add(block);
      }
    }
    // Captions above first (the usual place), then below among the blocks left: a caption between two tables belongs to
    // the table below it and is never reused.
    Set<LayoutBlock> taken = new HashSet<>();
    LayoutBlock[] caption = new LayoutBlock[tables.size()];
    boolean[] first = new boolean[tables.size()];
    for (boolean above : new boolean[] {true, false}) {
      for (int t = 0; t < tables.size(); t++) {
        DetectedTable table = tables.get(t);
        if (caption[t] != null || table.confidence() < s.minConfidence()) {
          continue;
        }
        LayoutBlock best = nearest(table.box(), candidates, taken, above, s);
        if (best != null) {
          taken.add(best);
          caption[t] = best;
          first[t] = above;
        }
      }
    }
    List<DetectedTable> out = new ArrayList<>();
    for (int t = 0; t < tables.size(); t++) {
      out.add(caption[t] == null ? tables.get(t) : tables.get(t).withCaption(caption[t], first[t]));
    }
    return out;
  }

  /** The untaken caption block nearest the box on the given side, overlapping it horizontally, within {@code captionMaxDistance}. */
  private static LayoutBlock nearest(PDRectangle box, List<LayoutBlock> candidates, Set<LayoutBlock> taken, boolean above, InferenceSettings s) {
    float tol = s.tableColumnTolerance();
    LayoutBlock best = null;
    float bestDistance = Float.MAX_VALUE;
    for (LayoutBlock block : candidates) {
      if (taken.contains(block) || block.x0() >= box.getUpperRightX() || block.x1() <= box.getLowerLeftX()) {
        continue;
      }
      float distance = above ? block.y0() - box.getUpperRightY() : box.getLowerLeftY() - block.y1();
      if (distance >= -tol && distance <= s.captionMaxDistance() && distance < bestDistance) {
        best = block;
        bestDistance = distance;
      }
    }
    return best;
  }
}
