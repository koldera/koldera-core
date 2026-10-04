package com.koldera.pdf.inference;

import com.koldera.pdf.generated.HeadingLevels;
import com.koldera.pdf.structure.ContentTagger;
import com.koldera.pdf.structure.ContentTagger.ItemKind;
import com.koldera.pdf.structure.ContentTagger.ItemView;
import com.koldera.pdf.structure.ContentTagger.Node;
import com.koldera.pdf.structure.ContentTagger.PageAssignment;
import com.koldera.pdf.structure.ContentTagger.PageItems;
import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDLayoutAttributeObject;

/**
 * The layout grouping of {@code PDF_STRUCTURE_INFERRED_V1}: text runs joined to walked items, blocks per page, running
 * headers/footers as {@code /Artifact /Pagination}, reading order, then tables, headings, lists, figures; every other
 * text block is a {@code P}, weak evidence falls back to {@code P}. Items the basic tagger makes artifacts stay artifacts.
 * Refusals ({@link UnsupportedContentException}) make the rule fall back to basic tags. Deterministic.
 */
public final class LayoutGrouping implements ContentTagger.Grouping {

  /** What the grouping inferred, for the action reason and the {@code STRUCTURE_INFERRED} finding. */
  public record Counts(int paragraphs, int headings, int lists, int listItems, int tables, int runningArtifacts, int pagesReordered, int pagesKept,
      int fallbacks, String runningSample) {}

  /** Source of a page's text runs; {@link TextRuns#of} in production. */
  interface RunsSource {
    Map<PDContentStream, List<TextRun>> of(PDPage page) throws IOException;
  }

  static final String MISMATCH = "text runs could not be matched to the content";
  static final int SAMPLE_MAX_CHARS = 80;
  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");
  private static final String PAGINATION = "Pagination";
  private static final String LAYOUT = "Layout";

  private final InferenceSettings settings;
  private final boolean rightToLeft;
  private final RunsSource runsSource;
  private Counts counts;

  public LayoutGrouping(InferenceSettings settings, boolean rightToLeft) {
    this(settings, rightToLeft, TextRuns::of);
  }

  LayoutGrouping(InferenceSettings settings, boolean rightToLeft, RunsSource runsSource) {
    this.settings = settings;
    this.rightToLeft = rightToLeft;
    this.runsSource = runsSource;
  }

  /** The counts of the last {@link #assign}; fails before one. */
  public Counts counts() {
    if (counts == null) {
      throw new IllegalStateException("counts are known after assign");
    }
    return counts;
  }

  @Override
  public List<PageAssignment> assign(PDDocument document, List<PageItems> pages) throws IOException, UnsupportedContentException {
    counts = null;
    if (pages.size() > settings.maxPages()) {
      throw new UnsupportedContentException(pages.size() + " pages exceed the cap of " + settings.maxPages());
    }
    int textRuns = 0;
    for (PageItems page : pages) {
      for (ItemView item : page.items()) {
        if (item.kind() == ItemKind.TEXT) {
          textRuns++;
        }
      }
    }
    if (textRuns > settings.maxTextRuns()) {
      throw new UnsupportedContentException(textRuns + " text runs exceed the cap of " + settings.maxTextRuns());
    }

    // The basic tagger's artifact decisions, kept exactly (no item it would hide becomes content): paths
    // (pagination when repeated in the page band), text outside the crop box, text repeated at the same
    // place in the page band on at least three pages, and shared forms drawn as page furniture.
    Map<String, Set<Integer>> bandPages = new HashMap<>();
    for (int p = 0; p < pages.size(); p++) {
      for (ItemView item : pages.get(p).items()) {
        if (item.band() != null && (item.kind() == ItemKind.TEXT || item.kind() == ItemKind.PATH)) {
          bandPages.computeIfAbsent(item.band() + "|" + item.digest(), k -> new HashSet<>()).add(p);
        }
      }
    }
    List<Set<Integer>> furniture = ContentTagger.sharedFormFurniture(pages);
    List<Map<Integer, String>> artifactsPerPage = new ArrayList<>();
    List<Map<Integer, ItemView>> itemsPerPage = new ArrayList<>();
    // Band-repeated text stays in the layout blocks so a running header/footer is still recognised (and counted) as one.
    List<Set<Integer>> bandTextPerPage = new ArrayList<>();
    for (int p = 0; p < pages.size(); p++) {
      Set<Integer> bandText = new HashSet<>();
      bandTextPerPage.add(bandText);
      Map<Integer, ItemView> byIndex = new HashMap<>();
      Map<Integer, String> artifactOfItem = new LinkedHashMap<>();
      for (ItemView item : pages.get(p).items()) {
        byIndex.put(item.index(), item);
        boolean repeated = item.band() != null && bandPages.getOrDefault(item.band() + "|" + item.digest(), Set.of()).size() >= ContentTagger.paginationMinPages();
        if (item.kind() == ItemKind.PATH) {
          artifactOfItem.put(item.index(), repeated ? PAGINATION : LAYOUT);
        } else if (item.kind() == ItemKind.TEXT && !item.adopted() && item.outsideCropBox()) {
          artifactOfItem.put(item.index(), LAYOUT);
        } else if (item.kind() == ItemKind.TEXT && !item.adopted() && repeated) {
          artifactOfItem.put(item.index(), PAGINATION);
          bandText.add(item.index());
        } else if (item.kind() == ItemKind.FORM_SHARED && furniture.get(p).contains(item.index())) {
          artifactOfItem.put(item.index(), PAGINATION);
        }
      }
      artifactsPerPage.add(artifactOfItem);
      itemsPerPage.add(byIndex);
    }

    List<Map<PDContentStream, List<TextRun>>> runsPerPage = new ArrayList<>();
    List<List<LayoutBlock>> blocksPerPage = new ArrayList<>();
    for (int p = 0; p < pages.size(); p++) {
      PageItems page = pages.get(p);
      Map<PDContentStream, List<TextRun>> runs = runsSource.of(page.page());
      requireMatchingRuns(page, runs);
      runsPerPage.add(runs);
      Set<Integer> excluded = new TreeSet<>(artifactsPerPage.get(p).keySet());
      excluded.removeAll(bandTextPerPage.get(p));
      blocksPerPage.add(PageLayout.blocks(page, runs, settings, false, excluded));
    }
    // Running headers/footers: only blocks in the page's top or bottom band, never author-marked content.
    Set<LayoutBlock> running = RunningArtifacts.find(blocksPerPage, settings, block -> {
      Map<Integer, ItemView> items = itemsPerPage.get(block.page() - 1);
      for (int index : block.itemIndexes()) {
        ItemView item = items.get(index);
        if (item == null || item.band() == null || item.adopted()) {
          return false;
        }
      }
      return true;
    });
    if (!running.isEmpty() && everyTextBlockIn(running, blocksPerPage)) {
      // Page furniture needs a page body: when every text block of the document would be furniture (one
      // numbered line per page, nothing else), the repetition is the content itself and nothing is hidden.
      running = Set.of();
    }

    int reordered = 0;
    int kept = 0;
    int tableFallbacks = 0;
    String sample = null;
    List<List<LayoutBlock>> orderPerPage = new ArrayList<>();
    List<LayoutBlock> documentBlocks = new ArrayList<>();
    // Tables are classified first: their blocks (and fallback rows) are never heading or list candidates.
    Map<LayoutBlock, TableDetector.DetectedTable> tableOfBlock = new IdentityHashMap<>();
    Set<LayoutBlock> rowParagraphs = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int p = 0; p < pages.size(); p++) {
      PageItems page = pages.get(p);
      Map<Integer, String> artifactOfItem = artifactsPerPage.get(p);
      Set<Integer> bandText = bandTextPerPage.get(p);
      List<LayoutBlock> content = new ArrayList<>();
      Set<Integer> runningItems = new TreeSet<>();
      for (LayoutBlock block : blocksPerPage.get(p)) {
        if (running.contains(block)) {
          if (sample == null) {
            sample = sample(block.text());
          }
          for (int index : block.itemIndexes()) {
            artifactOfItem.put(index, PAGINATION);
            runningItems.add(index);
          }
        } else {
          content.add(block);
        }
      }
      // Tables over the content blocks that hold no artifact (band-repeated text stays an artifact).
      List<LayoutBlock> tableInput = new ArrayList<>();
      for (LayoutBlock block : content) {
        if (block.itemIndexes().stream().noneMatch(bandText::contains)) {
          tableInput.add(block);
        }
      }
      List<LayoutBlock> units = new ArrayList<>();
      Set<Integer> tableItems = new TreeSet<>();
      for (TableDetector.DetectedTable table : TableDetector.detect(page, tableInput, runsPerPage.get(p), settings)) {
        if (table.confidence() >= settings.minConfidence()) {
          LayoutBlock unit = unit(page.pageNumber(), table.items(), table.rows(), table.caption());
          tableOfBlock.put(unit, table);
          units.add(unit);
          tableItems.addAll(table.items());
        } else {
          tableFallbacks++; // below the confidence: each row is one paragraph
          for (int r = 0; r < table.rows().size(); r++) {
            LayoutBlock row = unit(page.pageNumber(), table.rowItems(r), List.of(table.rows().get(r)), null);
            rowParagraphs.add(row);
            units.add(row);
            tableItems.addAll(row.itemIndexes());
          }
        }
      }
      if (!units.isEmpty()) {
        Set<Integer> excluded = new TreeSet<>(artifactOfItem.keySet());
        for (int index : bandText) {
          if (!runningItems.contains(index)) {
            excluded.remove(index); // as in the first layout pass: band-repeated text stays in the blocks
          }
        }
        excluded.addAll(tableItems);
        content = withUnits(PageLayout.blocks(page, runsPerPage.get(p), settings, false, excluded), units);
      }
      boolean holdsBandText = false;
      for (LayoutBlock block : content) {
        holdsBandText |= block.itemIndexes().stream().anyMatch(bandText::contains);
      }
      ReadingOrder.Result result = ReadingOrder.order(content, rightToLeft, settings);
      List<LayoutBlock> order;
      if (result.reordered() && !holdsBandText) {
        order = result.order();
        reordered++;
      } else {
        // Content order (also when the columns cannot be separated cleanly): regrouped as the content comes, so a
        // block never joins lines across other blocks (a superscript, a label/value row) and nothing is reordered.
        Set<Integer> excluded = new TreeSet<>(artifactOfItem.keySet());
        excluded.addAll(tableItems);
        order = withUnits(PageLayout.blocks(page, runsPerPage.get(p), settings, true, excluded), units);
        kept++;
      }
      orderPerPage.add(order);
      for (LayoutBlock block : order) {
        if (!tableOfBlock.containsKey(block) && !rowParagraphs.contains(block)) {
          documentBlocks.add(block);
        }
      }
    }

    // Headings over the whole document in reading order, lists per page; a list item is never a heading.
    List<HeadingClassifier.Classified> classified = HeadingClassifier.classify(documentBlocks, settings);
    int fallbacks = tableFallbacks;
    List<Map<LayoutBlock, ListClassifier.ListRun>> listStartsPerPage = new ArrayList<>();
    Set<LayoutBlock> listed = Collections.newSetFromMap(new IdentityHashMap<>());
    for (List<LayoutBlock> order : orderPerPage) {
      Map<LayoutBlock, ListClassifier.ListRun> starts = new IdentityHashMap<>();
      List<LayoutBlock> flow = new ArrayList<>();
      for (LayoutBlock block : order) {
        if (!tableOfBlock.containsKey(block) && !rowParagraphs.contains(block)) {
          flow.add(block);
        }
      }
      for (ListClassifier.ListRun run : ListClassifier.lists(flow, settings)) {
        if (run.confidence() >= settings.minConfidence()) {
          starts.put(run.items().get(0).block(), run);
          for (ListClassifier.Entry entry : run.items()) {
            listed.add(entry.block());
            listed.addAll(entry.more());
          }
        } else if (run.confidence() > 0) {
          fallbacks++;
        }
      }
      listStartsPerPage.add(starts);
    }
    // The levels of the headings that stay headings, renumbered in reading order again (a list item taken out
    // of the headings must not leave a skipped level).
    List<HeadingClassifier.Classified> headings = new ArrayList<>();
    for (HeadingClassifier.Classified c : classified) {
      if (listed.contains(c.block())) {
        continue;
      }
      if (c.level() > 0) {
        headings.add(c);
      } else if (c.fellBack()) {
        fallbacks++;
      }
    }
    int[] levels = HeadingLevels.levels(headings.stream().mapToInt(HeadingClassifier.Classified::level).toArray());
    Map<LayoutBlock, String> headingType = new IdentityHashMap<>();
    for (int i = 0; i < headings.size(); i++) {
      headingType.put(headings.get(i).block(), "H" + Math.min(6, levels[i]));
    }

    Tally tally = new Tally();
    List<PageAssignment> out = new ArrayList<>();
    for (int p = 0; p < pages.size(); p++) {
      Map<Integer, ItemView> byIndex = itemsPerPage.get(p);
      Map<LayoutBlock, ListClassifier.ListRun> starts = listStartsPerPage.get(p);
      PageBuilder builder = new PageBuilder(itemsByRun(pages.get(p), runsPerPage.get(p)), tally);
      for (LayoutBlock block : orderPerPage.get(p)) {
        ListClassifier.ListRun run = starts.get(block);
        TableDetector.DetectedTable table = tableOfBlock.get(block);
        if (table != null) {
          tally.tables++;
          builder.roots.add(builder.table(table));
        } else if (rowParagraphs.contains(block)) {
          tally.paragraphs++;
          builder.roots.add(builder.leaf("P", block.itemIndexes()));
        } else if (run != null) {
          builder.roots.add(builder.list(run.items(), run.numbering(), 0, new int[] {0}));
        } else if (listed.contains(block)) {
          continue; // an item of a list already emitted at the list's first item
        } else if (headingType.containsKey(block)) {
          tally.headings++;
          builder.roots.add(builder.leaf(headingType.get(block), block.itemIndexes()));
        } else {
          Node node = node(block, byIndex.get(block.itemIndexes().get(0)), builder.leaves);
          if ("P".equals(node.type())) {
            tally.paragraphs++;
          }
          builder.roots.add(builder.claim(node, block.itemIndexes()));
        }
      }
      out.add(new PageAssignment(builder.leafOfItem, artifactsPerPage.get(p), builder.roots));
    }
    counts = new Counts(tally.paragraphs, tally.headings, tally.lists, tally.listItems, tally.tables, running.size(), reordered, kept, fallbacks, sample);
    return out;
  }

  /** Element counts of the assignment. */
  private static final class Tally {
    int paragraphs;
    int headings;
    int lists;
    int listItems;
    int tables;
  }

  /** The item index of the run of every one-operator text item on the page, by run identity (the blocks hold these very run objects). */
  private static Map<TextRun, Integer> itemsByRun(PageItems page, Map<PDContentStream, List<TextRun>> runs) {
    Map<COSBase, List<TextRun>> byStream = PageLayout.byStream(runs);
    Map<TextRun, Integer> out = new IdentityHashMap<>();
    for (ItemView item : page.items()) {
      // Only an item of one text operator can be a label on its own (a marked block holds more than the marker).
      if (item.kind() == ItemKind.TEXT && item.textCount() == 1) {
        TextRun run = PageLayout.runOf(byStream, item);
        if (run != null) {
          out.put(run, item.index());
        }
      }
    }
    return out;
  }

  /** Builds one page's roots: leaf ids in creation order, every block item assigned to exactly one leaf. */
  private static final class PageBuilder {
    final Map<TextRun, Integer> itemOfRun;
    final Tally tally;
    final Map<Integer, Integer> leafOfItem = new LinkedHashMap<>();
    final List<Node> roots = new ArrayList<>();
    int leaves;

    PageBuilder(Map<TextRun, Integer> itemOfRun, Tally tally) {
      this.itemOfRun = itemOfRun;
      this.tally = tally;
    }

    /** Registers {@code node} (a leaf whose id is the next one) as holding {@code items}. */
    Node claim(Node node, List<Integer> items) {
      int leaf = leaves++;
      if (node.leaf() != leaf) {
        throw new IllegalStateException("leaf ids out of step");
      }
      for (int index : items) {
        leafOfItem.put(index, leaf);
      }
      return node;
    }

    Node leaf(String type, List<Integer> items) {
      return claim(Node.leaf(type, leaves), items);
    }

    /**
     * {@code Table > (Caption) (THead > TR > TH…, TBody > TR > TD… | TR > TD…) (Caption)}: a header row's cells are
     * {@code TH} with {@code /A <</O /Table /Scope /Column>>}; an empty cell is an element without content.
     */
    Node table(TableDetector.DetectedTable table) {
      List<Node> kids = new ArrayList<>();
      if (table.caption() != null && table.captionFirst()) {
        kids.add(leaf("Caption", table.caption().itemIndexes()));
      }
      List<Node> rows = new ArrayList<>();
      for (int r = 0; r < table.rows().size(); r++) {
        boolean header = r < table.headerRows();
        List<Node> cells = new ArrayList<>();
        for (TableDetector.Cell cell : table.rows().get(r)) {
          COSDictionary attributes = null;
          if (header) {
            attributes = new COSDictionary();
            attributes.setName(COSName.O, "Table");
            attributes.setName(COSName.getPDFName("Scope"), "Column");
          }
          String type = header ? "TH" : "TD";
          if (cell.itemIndexes().isEmpty()) {
            cells.add(new Node(type, -1, List.of(), attributes));
          } else {
            cells.add(claim(new Node(type, leaves, List.of(), attributes), cell.itemIndexes()));
          }
        }
        rows.add(Node.group("TR", cells));
      }
      if (table.headerRows() > 0) {
        kids.add(Node.group("THead", rows.subList(0, table.headerRows())));
        kids.add(Node.group("TBody", rows.subList(table.headerRows(), rows.size())));
      } else {
        kids.addAll(rows);
      }
      if (table.caption() != null && !table.captionFirst()) {
        kids.add(leaf("Caption", table.caption().itemIndexes()));
      }
      return Node.group("Table", kids);
    }

    /**
     * The {@code L} of the entries from {@code at[0]} at {@code depth}: {@code LI > (Lbl) LBody} per item,
     * a following deeper run of entries nested as an {@code L} inside the item's {@code LBody} (the item's
     * own text is then a {@code P} in it). The list's {@code /ListNumbering} comes from its markers.
     * Advances {@code at[0]} past the entries it took.
     */
    Node list(List<ListClassifier.Entry> entries, List<String> numbering, int depth, int[] at) {
      String listNumbering = numbering.isEmpty() ? null : numbering.get(at[0]);
      List<Node> items = new ArrayList<>();
      while (at[0] < entries.size() && entries.get(at[0]).depth() == depth) {
        ListClassifier.Entry entry = entries.get(at[0]++);
        List<Integer> body = new ArrayList<>(entry.block().itemIndexes());
        for (LayoutBlock more : entry.more()) {
          body.addAll(more.itemIndexes()); // prose that only looked like a nested item ("v. Wade") stays in the item's text
        }
        List<Node> kids = new ArrayList<>();
        Integer labelItem = entry.label() == null ? null : itemOfRun.get(entry.label());
        if (labelItem != null && body.size() > 1 && body.remove(labelItem)) {
          kids.add(leaf("Lbl", List.of(labelItem)));
        }
        if (at[0] < entries.size() && entries.get(at[0]).depth() > depth) {
          Node text = leaf("P", body);
          tally.paragraphs++;
          Node nested = list(entries, numbering, depth + 1, at);
          kids.add(Node.group("LBody", List.of(text, nested)));
        } else {
          kids.add(leaf("LBody", body));
        }
        tally.listItems++;
        items.add(Node.group("LI", kids));
      }
      tally.lists++;
      if (listNumbering == null) {
        return Node.group("L", items);
      }
      COSDictionary attributes = new COSDictionary();
      attributes.setName(COSName.O, "List");
      attributes.setName(COSName.getPDFName("ListNumbering"), listNumbering);
      return new Node("L", -1, List.copyOf(items), attributes);
    }
  }

  /**
   * A table (its rows and caption) or one fallback row as one block of the page's order: the items, runs and box of
   * its cells (and caption), one line per row.
   */
  private static LayoutBlock unit(int page, List<Integer> items, List<List<TableDetector.Cell>> rows, LayoutBlock caption) {
    List<TextRun> runs = new ArrayList<>();
    if (caption != null) {
      runs.addAll(caption.runs());
    }
    for (List<TableDetector.Cell> row : rows) {
      for (TableDetector.Cell cell : row) {
        runs.addAll(cell.runs());
      }
    }
    float x0 = Float.MAX_VALUE;
    float y0 = Float.MAX_VALUE;
    float x1 = -Float.MAX_VALUE;
    float y1 = -Float.MAX_VALUE;
    StringBuilder text = new StringBuilder();
    Map<Float, Integer> chars = new TreeMap<>();
    for (TextRun run : runs) {
      if (!run.hasGlyphs()) {
        continue;
      }
      x0 = Math.min(x0, run.x0());
      x1 = Math.max(x1, run.x1());
      y0 = Math.min(y0, run.baseline());
      y1 = Math.max(y1, run.top());
      chars.merge(run.fontSize(), Math.max(1, run.text().length()), Integer::sum);
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
    int lines = rows.size() + (caption == null ? 0 : caption.lines());
    return new LayoutBlock(page, items, runs, x0, y0, x1, y1, size, false, lines, PageLayout.normalize(text.toString()));
  }

  /** {@code blocks} and {@code units} in content order of their first item. */
  private static List<LayoutBlock> withUnits(List<LayoutBlock> blocks, List<LayoutBlock> units) {
    List<LayoutBlock> out = new ArrayList<>(blocks);
    out.addAll(units);
    out.sort(Comparator.comparingInt(b -> b.itemIndexes().get(0)));
    return out;
  }

  private static boolean everyTextBlockIn(Set<LayoutBlock> running, List<List<LayoutBlock>> pages) {
    for (List<LayoutBlock> page : pages) {
      for (LayoutBlock block : page) {
        if (block.lines() > 0 && !running.contains(block)) {
          return false;
        }
      }
    }
    return true;
  }

  /** A text block that is neither heading nor list item is a {@code P}; an image a {@code Figure} with its box; a shared form's draw a {@code P} when it shows text, else a {@code Figure}. */
  private static Node node(LayoutBlock block, ItemView first, int leaf) {
    boolean figure = block.lines() == 0 && (first.kind() == ItemKind.FIGURE || (first.kind() == ItemKind.FORM_SHARED && !first.formText()));
    if (!figure) {
      return Node.leaf("P", leaf);
    }
    if (first.bbox() == null) {
      return Node.leaf("Figure", leaf);
    }
    PDLayoutAttributeObject layout = new PDLayoutAttributeObject();
    layout.setBBox(rounded(first.bbox()));
    return new Node("Figure", leaf, List.of(), layout.getCOSObject());
  }

  /**
   * Every stream holding a text item must have as many text runs as it has text-showing operators in
   * token order (the walker's count), else the ordinals cannot be trusted and the document is refused.
   */
  private static void requireMatchingRuns(PageItems page, Map<PDContentStream, List<TextRun>> runs) throws IOException, UnsupportedContentException {
    Map<COSBase, List<TextRun>> byStream = PageLayout.byStream(runs);
    Map<COSBase, Boolean> checked = new IdentityHashMap<>();
    for (ItemView item : page.items()) {
      if (item.kind() != ItemKind.TEXT || item.textOrdinal() < 0) {
        continue;
      }
      COSBase key = PageLayout.cos(item.stream());
      if (key == null || checked.put(key, Boolean.TRUE) != null) {
        continue;
      }
      List<TextRun> list = byStream.get(key);
      if (list == null || list.size() != textOperators(item.stream())) {
        throw new UnsupportedContentException(MISMATCH);
      }
    }
  }

  private static int textOperators(PDContentStream stream) throws IOException {
    List<Object> tokens = new PDFStreamParser(stream).parse();
    int count = 0;
    for (Object token : tokens) {
      if (token instanceof Operator op && TEXT_SHOWING.contains(op.getName())) {
        count++;
      }
    }
    return count;
  }

  /** At most {@link #SAMPLE_MAX_CHARS} characters, ending in {@code …} when cut. */
  static String sample(String text) {
    if (text.codePointCount(0, text.length()) <= SAMPLE_MAX_CHARS) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, SAMPLE_MAX_CHARS - 1)) + "…";
  }

  private static PDRectangle rounded(PDRectangle box) {
    return new PDRectangle(round(box.getLowerLeftX()), round(box.getLowerLeftY()), round(box.getWidth()), round(box.getHeight()));
  }

  private static float round(float value) {
    return Math.round(value * 100f) / 100f;
  }
}
