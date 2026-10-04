package com.koldera.pdf.inference;

import com.koldera.pdf.structure.ContentTagger.ItemView;
import com.koldera.pdf.structure.ContentTagger.PageItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Lines and blocks of one page from its walked items and text runs (thresholds from {@link InferenceSettings}). Lines join
 * runs on one baseline; a line joins an open block when gap, left edge, size and boldness stay within the thresholds.
 * Layout mode lets any open block take a line (interleaved columns); sequential mode only the previous block. List markers,
 * images, form draws and adopted text start blocks of their own. Nothing here reorders content.
 */
public final class PageLayout {

  /** How many open blocks a line is compared with (most recent first): bounds the work on dense pages. */
  static final int MAX_OPEN_BLOCKS = 64;

  /** A line that ends its paragraph: terminal punctuation or a colon, optionally closed by quotes or brackets. */
  private static final java.util.regex.Pattern PARAGRAPH_END = java.util.regex.Pattern.compile("[.!?:][\"')\\]\\u2019\\u201D]*$");


  private PageLayout() {}

  public static List<LayoutBlock> blocks(PageItems page, Map<PDContentStream, List<TextRun>> runs, InferenceSettings s) {
    return blocks(page, runs, s, false, Set.of());
  }

  /** As {@link #blocks(PageItems, Map, InferenceSettings)}; {@code sequential} keeps content order, {@code excluded} item indexes are skipped. */
  static List<LayoutBlock> blocks(PageItems page, Map<PDContentStream, List<TextRun>> runs, InferenceSettings s, boolean sequential, Set<Integer> excluded) {
    Map<COSBase, List<TextRun>> byStream = byStream(runs);
    Builder builder = new Builder(page.pageNumber(), s, sequential);
    for (ItemView item : page.items()) {
      if (excluded.contains(item.index())) {
        continue;
      }
      switch (item.kind()) {
        case TEXT -> {
          if (item.adopted()) {
            builder.closeAll();
            builder.single(item, runsOf(byStream, item));
          } else if (!item.outsideCropBox()) {
            builder.text(item, runsOf(byStream, item));
          }
        }
        case FIGURE, FORM_SHARED -> {
          builder.closeAll();
          builder.figure(item);
        }
        case FORM_ONCE -> builder.closeAll();
        case PATH, EXISTING_ARTIFACT -> {
          // never part of a block
        }
      }
    }
    builder.closeAll();
    List<LayoutBlock> out = new ArrayList<>();
    builder.blocks.sort(Comparator.comparingInt(b -> b.items.get(0)));
    for (BlockBuilder block : builder.blocks) {
      out.add(block.build(page.pageNumber()));
    }
    return out;
  }

  /** The runs keyed by the stream's COS object, so the walker's and the stripper's wrappers of one stream meet. */
  static Map<COSBase, List<TextRun>> byStream(Map<PDContentStream, List<TextRun>> runs) {
    Map<COSBase, List<TextRun>> out = new IdentityHashMap<>();
    for (Map.Entry<PDContentStream, List<TextRun>> e : runs.entrySet()) {
      COSBase key = cos(e.getKey());
      if (key != null) {
        out.put(key, e.getValue());
      }
    }
    return out;
  }

  static COSBase cos(PDContentStream stream) {
    return stream instanceof COSObjectable o ? o.getCOSObject() : null;
  }

  static TextRun runOf(Map<COSBase, List<TextRun>> byStream, ItemView item) {
    List<TextRun> list = byStream.get(cos(item.stream()));
    return list != null && item.textOrdinal() >= 0 && item.textOrdinal() < list.size() ? list.get(item.textOrdinal()) : null;
  }

  /**
   * Every text run of the item: its {@code textCount} operators from {@code textOrdinal} on (a marked
   * block that survived tag stripping, e.g. {@code /Span <</Lang ...>>}, holds several).
   */
  static List<TextRun> runsOf(Map<COSBase, List<TextRun>> byStream, ItemView item) {
    List<TextRun> list = byStream.get(cos(item.stream()));
    if (list == null || item.textOrdinal() < 0 || item.textOrdinal() >= list.size()) {
      return List.of();
    }
    return list.subList(item.textOrdinal(), Math.min(list.size(), item.textOrdinal() + Math.max(1, item.textCount())));
  }

  /**
   * One text item's share of a line. {@code runs} all of its runs; {@code baseline} its first glyph's
   * baseline, {@code lowest} its lowest; an item whose runs sit on several rows (a marked block across a
   * line break) ends on row {@code rowBaseline}, where its glyphs reach {@code rowX1}; {@code rows} how
   * many rows it covers. {@code size} the size that paints most of its characters.
   */
  private record Piece(int index, List<TextRun> runs, float x0, float x1, float baseline, float lowest, float rowBaseline, float rowX1, int rows,
      float size, boolean bold, String text) {
    int chars() {
      return Math.max(1, text.length());
    }
  }

  private static Piece piece(ItemView item, List<TextRun> runs, InferenceSettings s) {
    List<TextRun> glyphs = new ArrayList<>();
    for (TextRun run : runs) {
      if (run.hasGlyphs()) {
        glyphs.add(run);
      }
    }
    if (glyphs.isEmpty()) {
      StringBuilder text = new StringBuilder();
      runs.forEach(r -> text.append(r.text()));
      float size = positive(item.size(), 1f);
      return new Piece(item.index(), runs, item.x(), item.x(), item.y(), item.y(), item.y(), item.x(), 1, size, false, text.toString());
    }
    Map<Float, Integer> chars = new LinkedHashMap<>();
    int bold = 0;
    int all = 0;
    for (TextRun run : glyphs) {
      int n = Math.max(1, run.text().length());
      chars.merge(positive(run.fontSize(), item.size()), n, Integer::sum);
      all += n;
      bold += run.bold() ? n : 0;
    }
    float size = mostChars(chars);
    float x0 = Float.MAX_VALUE;
    float x1 = -Float.MAX_VALUE;
    float lowest = Float.MAX_VALUE;
    float rowBaseline = glyphs.get(0).baseline();
    float rowX1 = -Float.MAX_VALUE;
    int rows = 1;
    StringBuilder text = new StringBuilder();
    TextRun previous = null;
    for (TextRun run : glyphs) {
      x0 = Math.min(x0, run.x0());
      x1 = Math.max(x1, run.x1());
      lowest = Math.min(lowest, run.baseline());
      boolean newRow = previous != null && Math.abs(run.baseline() - rowBaseline) > s.sameLineFactor() * size;
      if (newRow) {
        rows++;
        rowBaseline = run.baseline();
        rowX1 = run.x1();
      } else {
        rowX1 = Math.max(rowX1, run.x1());
      }
      if (previous != null && !text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1)) && !run.text().isEmpty()
          && !Character.isWhitespace(run.text().charAt(0)) && (newRow || run.x0() - previous.x1() > 0.25f * size)) {
        text.append(' ');
      }
      text.append(run.text());
      previous = run;
    }
    return new Piece(item.index(), runs, x0, x1, glyphs.get(0).baseline(), lowest, rowBaseline, rowX1, rows, size, bold * 2 > all, text.toString());
  }

  /** The key with the most characters (the first on a tie). */
  private static float mostChars(Map<Float, Integer> chars) {
    float best = 0;
    int most = -1;
    for (Map.Entry<Float, Integer> e : chars.entrySet()) {
      if (e.getValue() > most) {
        most = e.getValue();
        best = e.getKey();
      }
    }
    return best;
  }

  private static float positive(float value, float fallback) {
    return value > 0 && !Float.isNaN(value) ? value : (fallback > 0 ? fallback : 1f);
  }

  /**
   * A line: pieces on one baseline next to each other. A piece that runs onto further rows (a marked
   * block across a line break) moves the line's current row ({@code rowBaseline}, {@code rowX1}) there, so
   * the text that continues that row joins the same line; {@code rows} counts the rows.
   */
  private static final class Line {
    final List<Piece> pieces = new ArrayList<>();
    float x0;
    float x1;
    float baseline;
    float rowBaseline;
    float rowX1;
    int rows;

    Line(Piece first) {
      pieces.add(first);
      x0 = first.x0();
      x1 = first.x1();
      baseline = first.baseline();
      rowBaseline = first.rowBaseline();
      rowX1 = first.rowX1();
      rows = first.rows();
    }

    void add(Piece piece) {
      pieces.add(piece);
      x0 = Math.min(x0, piece.x0());
      x1 = Math.max(x1, piece.x1());
      if (piece.rows() > 1) {
        rows += piece.rows() - 1;
        rowBaseline = piece.rowBaseline();
        rowX1 = piece.rowX1();
      } else {
        rowX1 = Math.max(rowX1, piece.x1());
      }
    }

    float lowest() {
      float lowest = Float.MAX_VALUE;
      for (Piece piece : pieces) {
        lowest = Math.min(lowest, piece.lowest());
      }
      return lowest;
    }

    float size() {
      return dominant(pieces).size();
    }

    boolean bold() {
      int bold = 0;
      int all = 0;
      for (Piece piece : pieces) {
        all += piece.chars();
        bold += piece.bold() ? piece.chars() : 0;
      }
      return bold * 2 > all;
    }

    float top() {
      float top = -Float.MAX_VALUE;
      for (Piece piece : pieces) {
        top = Math.max(top, piece.baseline() + piece.size());
      }
      return top;
    }

    /** Pieces joined left to right (in content order when the line covers several rows). */
    String text() {
      List<Piece> byX = new ArrayList<>(pieces);
      if (rows == 1) {
        byX.sort(Comparator.comparingDouble(Piece::x0));
      }
      StringBuilder sb = new StringBuilder();
      Piece previous = null;
      for (Piece piece : byX) {
        if (previous != null && !sb.isEmpty() && !Character.isWhitespace(sb.charAt(sb.length() - 1)) && !piece.text().isEmpty()
            && !Character.isWhitespace(piece.text().charAt(0)) && piece.x0() - previous.rowX1() > 0.25f * piece.size()) {
          sb.append(' ');
        }
        sb.append(piece.text());
        previous = piece;
      }
      return sb.toString();
    }
  }

  /** The piece that paints most characters (the first on a tie). */
  private static Piece dominant(List<Piece> pieces) {
    Map<Float, Integer> chars = new LinkedHashMap<>();
    for (Piece piece : pieces) {
      chars.merge(piece.size(), piece.chars(), Integer::sum);
    }
    float best = pieces.get(0).size();
    int most = -1;
    for (Map.Entry<Float, Integer> e : chars.entrySet()) {
      if (e.getValue() > most) {
        most = e.getValue();
        best = e.getKey();
      }
    }
    for (Piece piece : pieces) {
      if (piece.size() == best) {
        return piece;
      }
    }
    return pieces.get(0);
  }

  private static final class BlockBuilder {
    final List<Integer> items = new ArrayList<>();
    final List<Line> lines = new ArrayList<>();
    final Map<Float, Integer> charsBySize = new LinkedHashMap<>();
    /** Image / shared-form / adopted block. */
    ItemView single;
    Piece singlePiece;
    boolean bold;
    /** Where the item text of a first line that starts with a list marker begins (a hanging indent), else NaN. */
    float hangX = Float.NaN;

    /** The x where the text after a leading list marker starts: the next piece after a bare-marker piece, else estimated inside the piece. */
    private static float hang(Line line) {
      String text = line.text();
      if (!ListClassifier.startsWithMarker(text)) {
        return Float.NaN;
      }
      List<Piece> byX = new ArrayList<>();
      for (Piece piece : line.pieces) {
        if (!piece.text().isBlank()) {
          byX.add(piece); // a space run left of the marker is no text
        }
      }
      if (byX.isEmpty()) {
        return Float.NaN;
      }
      byX.sort(Comparator.comparingDouble(Piece::x0));
      Piece first = byX.get(0);
      if (ListClassifier.isBareMarker(first.text()) && byX.size() > 1) {
        return byX.get(1).x0();
      }
      int end = ListClassifier.markerEnd(first.text());
      return end < 0 || first.text().isEmpty() ? Float.NaN : first.x0() + (first.rowX1() - first.x0()) * end / first.text().length();
    }

    float bodySize() {
      float best = 0;
      int most = -1;
      for (Map.Entry<Float, Integer> e : charsBySize.entrySet()) {
        if (e.getValue() > most) {
          most = e.getValue();
          best = e.getKey();
        }
      }
      return best;
    }

    Line last() {
      return lines.get(lines.size() - 1);
    }

    void add(Line line) {
      if (lines.isEmpty()) {
        bold = line.bold();
        hangX = hang(line);
      }
      lines.add(line);
      for (Piece piece : line.pieces) {
        items.add(piece.index());
        boolean glyphs = false;
        for (TextRun run : piece.runs()) {
          if (run.hasGlyphs()) {
            glyphs = true;
            charsBySize.merge(positive(run.fontSize(), piece.size()), Math.max(1, run.text().length()), Integer::sum);
          }
        }
        if (!glyphs) {
          charsBySize.merge(piece.size(), piece.chars(), Integer::sum);
        }
      }
    }

    LayoutBlock build(int page) {
      if (single != null) {
        return buildSingle(page);
      }
      List<Piece> pieces = new ArrayList<>();
      float x0 = Float.MAX_VALUE;
      float x1 = -Float.MAX_VALUE;
      float y0 = Float.MAX_VALUE;
      float y1 = -Float.MAX_VALUE;
      StringBuilder text = new StringBuilder();
      int rows = 0;
      for (Line line : lines) {
        pieces.addAll(line.pieces);
        x0 = Math.min(x0, line.x0);
        x1 = Math.max(x1, line.x1);
        y0 = Math.min(y0, line.lowest());
        y1 = Math.max(y1, line.top());
        text.append(line.text()).append(' ');
        rows += line.rows;
      }
      pieces.sort(Comparator.comparingInt(Piece::index));
      List<Integer> indexes = new ArrayList<>();
      List<TextRun> runs = new ArrayList<>();
      for (Piece piece : pieces) {
        indexes.add(piece.index());
        runs.addAll(piece.runs());
      }
      return new LayoutBlock(page, indexes, runs, x0, y0, x1, y1, bodySize(), bold, rows, normalize(text.toString()));
    }

    private LayoutBlock buildSingle(int page) {
      if (single.kind() == com.koldera.pdf.structure.ContentTagger.ItemKind.TEXT) {
        Piece p = singlePiece;
        return new LayoutBlock(page, List.of(single.index()), p.runs(), p.x0(), p.lowest(), p.x1(), p.baseline() + p.size(), p.size(), p.bold(), p.rows(),
            normalize(p.text()));
      }
      PDRectangle box = single.bbox();
      float nan = Float.NaN;
      return new LayoutBlock(page, List.of(single.index()), List.of(), box == null ? nan : box.getLowerLeftX(), box == null ? nan : box.getLowerLeftY(),
          box == null ? nan : box.getUpperRightX(), box == null ? nan : box.getUpperRightY(), 0, false, 0, "");
    }
  }

  static String normalize(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  private static final class Builder {
    final int page;
    final InferenceSettings s;
    final boolean sequential;
    final List<BlockBuilder> blocks = new ArrayList<>();
    /** Open blocks, least recently extended first. */
    final List<BlockBuilder> open = new ArrayList<>();
    /** Finished lines not yet placed in a block (placed at {@link #closeAll}). */
    final List<Line> pending = new ArrayList<>();
    Line line;

    Builder(int page, InferenceSettings s, boolean sequential) {
      this.page = page;
      this.s = s;
      this.sequential = sequential;
    }

    void text(ItemView item, List<TextRun> runs) {
      Piece piece = piece(item, runs, s);
      boolean glyphs = runs.stream().anyMatch(TextRun::hasGlyphs);
      if (line != null && (sameLine(line, piece) || !glyphs)) {
        line.add(piece);
        return;
      }
      finishLine();
      line = new Line(piece);
    }

    private boolean sameLine(Line current, Piece piece) {
      float size = Math.max(current.size(), piece.size());
      float reach = s.blockLeftEdgeFactor() * size;
      if (piece.x0() > current.rowX1 && ListClassifier.isBareMarker(current.text())) {
        reach = Math.max(reach, s.listLabelGapFactor() * size); // a list label set off by a tab
      }
      float lineSize = current.size();
      boolean raised = piece.size() <= (1 - s.blockFontSizeChange()) * lineSize
          && Math.abs(piece.baseline() - current.rowBaseline) <= s.superscriptRiseFactor() * lineSize; // a footnote mark, a superscript
      return (Math.abs(piece.baseline() - current.rowBaseline) <= s.sameLineFactor() * size || raised)
          && piece.x0() - current.rowX1 <= reach
          && current.x0 - piece.x1() <= reach;
    }

    void finishLine() {
      if (line == null) {
        return;
      }
      pending.add(line);
      line = null;
    }

    /** Places the finished lines, each seeing the line after it (a hanging indent is known only from the next line). */
    private void flush() {
      for (int i = 0; i < pending.size(); i++) {
        place(pending.get(i), i + 1 < pending.size() ? pending.get(i + 1) : null);
      }
      pending.clear();
    }

    private void place(Line finished, Line next) {
      BlockBuilder target = null;
      int checked = 0;
      int limit = sequential ? 1 : MAX_OPEN_BLOCKS;
      for (int k = open.size() - 1; k >= 0 && checked < limit; k--, checked++) {
        if (joins(open.get(k), finished)) {
          target = open.get(k);
          break;
        }
      }
      if (target != null && ListClassifier.startsWithMarker(finished.text()) && startsItem(target, finished, next)) {
        target = null; // a list item starts its own block (ListClassifier decides whether it is one)
      }
      if (target == null) {
        target = new BlockBuilder();
        blocks.add(target);
      } else {
        open.remove(target);
      }
      target.add(finished);
      open.add(target);
    }

    private boolean joins(BlockBuilder block, Line next) {
      Line last = block.last();
      float size = block.bodySize();
      float gap = last.rowBaseline - next.baseline;
      float edge = s.blockLeftEdgeFactor() * size;
      boolean hangs = !Float.isNaN(block.hangX) && Math.abs(next.x0 - block.hangX) <= edge;
      return gap > 0
          && gap <= (hangs ? Math.max(s.blockGapFactor(), s.listItemGapFactor()) : s.blockGapFactor()) * size
          && (Math.abs(next.x0 - last.x0) <= edge || hangs)
          && Math.abs(next.size() - size) <= s.blockFontSizeChange() * size
          && boldFits(block, last, next);
    }

    /**
     * Bold does not flip; except that a list item's bold lead-in may continue in regular weight (a bold
     * line after a regular one still starts a block: a heading).
     */
    private static boolean boldFits(BlockBuilder block, Line last, Line next) {
      if (Float.isNaN(block.hangX)) {
        return next.bold() == block.bold;
      }
      return next.bold() ? block.bold && last.bold() : true;
    }

    /**
     * Whether a line starting with a list marker starts a new block: the marker is a run of its own, the next line hangs at
     * the item text, the previous line ends its paragraph, or the marker continues the item sequence ({@link ListClassifier#continues}).
     * Prose such as "v. Wade held" stays one paragraph.
     */
    private boolean startsItem(BlockBuilder block, Line line, Line next) {
      List<Piece> byX = new ArrayList<>();
      for (Piece piece : line.pieces) {
        if (!piece.text().isBlank()) {
          byX.add(piece);
        }
      }
      byX.sort(Comparator.comparingDouble(Piece::x0));
      if (!byX.isEmpty() && ListClassifier.isBareMarker(byX.get(0).text())) {
        return true;
      }
      float hang = BlockBuilder.hang(line);
      float size = line.size();
      if (next != null && !Float.isNaN(hang) && next.baseline < line.rowBaseline && next.x0 > line.x0 + s.listIndentTolerance()
          && Math.abs(next.x0 - hang) <= s.blockLeftEdgeFactor() * size) {
        return true;
      }
      String text = line.text().strip();
      if (text.startsWith("–") || text.startsWith("*")) {
        return false;
      }
      return ListClassifier.continues(block.lines.get(0).text(), text) || endsParagraph(block);
    }

    private boolean endsParagraph(BlockBuilder block) {
      Line last = block.last();
      if (PARAGRAPH_END.matcher(last.text().strip()).find()) {
        return true;
      }
      float x0 = Float.MAX_VALUE;
      float x1 = -Float.MAX_VALUE;
      for (Line l : block.lines) {
        x0 = Math.min(x0, l.x0);
        x1 = Math.max(x1, l.x1);
      }
      return x1 > x0 && (last.x1 - last.x0) < s.paragraphEndLineShare() * (x1 - x0);
    }

    void closeAll() {
      finishLine();
      flush();
      open.clear();
    }

    void single(ItemView item, List<TextRun> runs) {
      BlockBuilder block = new BlockBuilder();
      block.single = item;
      block.singlePiece = piece(item, runs, s);
      block.items.add(item.index());
      blocks.add(block);
    }

    void figure(ItemView item) {
      single(item, List.of());
    }

  }
}
