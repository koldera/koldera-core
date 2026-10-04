package com.koldera.pdf.generated;

import com.koldera.pdf.structure.NumberTreeView;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Chromium tags the borders of a collapsed-border table as marked content of the {@code Table} itself, which blocks grouping.
 * When every direct marked-content kid paints only non-text graphics, they are removed from /K, their /ParentTree
 * entries nulled and their sequences retagged {@code /Artifact BMC}. Fail closed: any doubtful sequence leaves the table
 * unchanged ({@link Outcome#tablesLeftUnchanged()}). {@link #rowsAndLists} and {@link #containers} do the same for lists, rows and boxes.
 */
public final class TableDecoration {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final Set<String> NOT_DECORATION = Set.of("Tj", "TJ", "'", "\"", "Do", "BI", "BMC", "BDC", "MP", "DP");

  private TableDecoration() {}

  /** Border sequences retagged as artifacts, and tables left unchanged because a direct sequence was not pure decoration. */
  public record Outcome(int sequences, int tablesLeftUnchanged) {}

  /** One direct marked-content kid of a Table: the /K entry, and the content stream that holds its MCID. */
  private record Direct(COSBase entry, COSDictionary owner, int mcid) {}

  /** A parsed content stream and, per MCID to retag, the index of its BDC operator. */
  private static final class Stream {
    final COSDictionary owner;
    final List<Object> tokens;
    final Map<Integer, Integer> topLevelBdc = new LinkedHashMap<>();
    final Map<Integer, Boolean> decoration = new LinkedHashMap<>();
    final Map<Integer, Integer> duplicates = new LinkedHashMap<>();

    Stream(COSDictionary owner, List<Object> tokens) {
      this.owner = owner;
      this.tokens = tokens;
    }
  }

  /** Rows, row groups and lists: their own decoration must go before tables are padded and grouped. */
  static final Set<String> ROWS_AND_LISTS = Set.of("TR", "THead", "TBody", "TFoot", "L");

  /**
   * Divs and grouping elements: run after {@link WidgetlessForms}, which turns printed controls into Divs.
   * {@code Aside} and {@code BlockQuote}: a shaded {@code <aside>}'s background
   * gave PAC "Content is present in admissible locations".
   */
  static final Set<String> CONTAINERS = Set.of("Div", "Sect", "Part", "Art", "Document", "Aside", "BlockQuote");

  public static Outcome apply(PDDocument doc) throws IOException {
    return apply(doc, List.of("Table"));
  }

  /**
   * The same step for {@link #ROWS_AND_LISTS} (a shaded row's or a list's background); returns how many
   * sequences became artifacts. Run right after the table step: a row holding its own marked content
   * is neither padded ({@link TablePadding}) nor recognised as a header row ({@link TableNormalizer}).
   */
  public static int rowsAndLists(PDDocument doc) throws IOException {
    return apply(doc, ROWS_AND_LISTS.stream().sorted().toList()).sequences();
  }

  /**
   * The same step for {@link #CONTAINERS}; returns how many sequences became artifacts. A container
   * whose own marked content is not pure decoration is left as it is (not counted anywhere).
   */
  public static int containers(PDDocument doc) throws IOException {
    return apply(doc, CONTAINERS.stream().sorted().toList()).sequences();
  }

  private static Outcome apply(PDDocument doc, List<String> types) throws IOException {
    COSDictionary parentTree = doc.getDocumentCatalog().getStructureTreeRoot() == null
        ? null
        : doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE) instanceof COSDictionary t ? t : null;
    if (parentTree == null) {
      return new Outcome(0, 0);
    }
    Map<COSDictionary, Stream> streams = new IdentityHashMap<>();
    Map<COSDictionary, PDPage> pages = new IdentityHashMap<>();
    for (PDPage page : doc.getPages()) {
      pages.put(page.getCOSObject(), page);
    }

    Map<Integer, COSBase> numbers = NumberTreeView.read(parentTree);
    List<PDStructureElement> tables = new ArrayList<>();
    for (String type : types) {
      tables.addAll(StructTree.elementsOfType(doc, type));
    }
    Map<PDStructureElement, List<Direct>> plans = new LinkedHashMap<>();
    int refused = 0;
    for (PDStructureElement table : tables) {
      List<Direct> direct = directMarkedContent(table.getCOSObject());
      if (direct == null) {
        refused++;
        continue;
      }
      if (direct.isEmpty()) {
        continue;
      }
      boolean ok = true;
      for (Direct d : direct) {
        Stream stream = streams.get(d.owner());
        if (stream == null) {
          stream = parse(d.owner(), pages);
          if (stream == null) {
            ok = false;
            break;
          }
          streams.put(d.owner(), stream);
        }
        if (!Boolean.TRUE.equals(stream.decoration.get(d.mcid())) || stream.duplicates.containsKey(d.mcid())
            || parentTreeArray(numbers, d, table.getCOSObject()) == null) {
          ok = false;
          break;
        }
      }
      if (ok) {
        plans.put(table, direct);
      } else {
        refused++;
      }
    }

    Map<COSDictionary, List<Integer>> retag = new IdentityHashMap<>();
    int sequences = 0;
    for (Map.Entry<PDStructureElement, List<Direct>> plan : plans.entrySet()) {
      COSDictionary tableDict = plan.getKey().getCOSObject();
      List<COSBase> removing = plan.getValue().stream().map(Direct::entry).toList();
      COSArray kept = new COSArray();
      for (COSBase entry : StructTree.entries(tableDict)) {
        if (removing.stream().noneMatch(r -> r == entry)) {
          kept.add(entry);
        }
      }
      tableDict.setItem(COSName.K, kept);
      for (Direct d : plan.getValue()) {
        parentTreeArray(numbers, d, tableDict).set(d.mcid(), COSNull.NULL);
        retag.computeIfAbsent(d.owner(), k -> new ArrayList<>()).add(d.mcid());
        sequences++;
      }
    }

    for (Map.Entry<COSDictionary, List<Integer>> e : retag.entrySet()) {
      Stream stream = streams.get(e.getKey());
      List<Object> tokens = new ArrayList<>(stream.tokens);
      for (int mcid : e.getValue()) {
        int bdc = stream.topLevelBdc.get(mcid);
        // "/Tag <<...>> BDC" becomes "/Artifact BMC" (tag and property list replaced by one name); its EMC stays, so nesting is unchanged.
        tokens.set(bdc - 2, COSName.ARTIFACT);
        tokens.set(bdc - 1, null);
        tokens.set(bdc, Operator.getOperator("BMC"));
      }
      tokens.removeIf(t -> t == null);
      write(doc, e.getKey(), pages, tokens);
    }
    return new Outcome(sequences, refused);
  }

  /** The ParentTree array holding {@code d}'s MCID when its entry names {@code table}, else null. */
  private static COSArray parentTreeArray(Map<Integer, COSBase> numbers, Direct d, COSDictionary table) {
    COSBase value = StructTree.resolve(numbers.get(d.owner().getInt(COSName.STRUCT_PARENTS)));
    return value instanceof COSArray array && d.mcid() >= 0 && d.mcid() < array.size() && StructTree.resolve(array.get(d.mcid())) == table ? array : null;
  }

  /**
   * The Table's direct marked-content kids with the stream that holds each, an empty list when it
   * has none, or null when one cannot be attributed to a page or form stream (fail closed).
   */
  private static List<Direct> directMarkedContent(COSDictionary table) {
    List<Direct> out = new ArrayList<>();
    COSBase tablePage = StructTree.resolve(table.getItem(COSName.PG));
    for (COSBase entry : StructTree.entries(table)) {
      COSBase resolved = StructTree.resolve(entry);
      if (resolved instanceof COSInteger mcid) {
        if (!(tablePage instanceof COSDictionary page) || !page.containsKey(COSName.STRUCT_PARENTS)) {
          return null;
        }
        out.add(new Direct(entry, page, mcid.intValue()));
      } else if (resolved instanceof COSDictionary dict && MCR.equals(dict.getCOSName(COSName.TYPE))) {
        COSBase stm = StructTree.resolve(dict.getItem(STM));
        COSBase owner = stm != null ? stm : dict.containsKey(COSName.PG) ? StructTree.resolve(dict.getItem(COSName.PG)) : tablePage;
        if (!(owner instanceof COSDictionary ownerDict) || !ownerDict.containsKey(COSName.STRUCT_PARENTS) || !dict.containsKey(COSName.MCID)) {
          return null;
        }
        out.add(new Direct(entry, ownerDict, dict.getInt(COSName.MCID)));
      }
    }
    return out;
  }

  /** Parses a page or form stream and classifies every top-level MCID sequence; null when the stream is unbalanced or unknown. */
  private static Stream parse(COSDictionary owner, Map<COSDictionary, PDPage> pages) throws IOException {
    PDContentStream content;
    if (pages.containsKey(owner)) {
      content = pages.get(owner);
    } else if (owner instanceof COSStream cos && COSName.FORM.equals(cos.getCOSName(COSName.SUBTYPE))) {
      content = new PDFormXObject(cos);
    } else {
      return null;
    }
    Stream stream = new Stream(owner, new PDFStreamParser(content).parse());
    List<Object> tokens = stream.tokens;
    int depth = 0;
    Integer open = null;
    boolean clean = true;
    for (int i = 0; i < tokens.size(); i++) {
      if (!(tokens.get(i) instanceof Operator op)) {
        continue;
      }
      String name = op.getName();
      if (open != null && depth >= 1 && NOT_DECORATION.contains(name)) {
        clean = false;
      }
      if ("BMC".equals(name) || "BDC".equals(name)) {
        if (depth == 0 && "BDC".equals(name) && i >= 2 && tokens.get(i - 2) instanceof COSName
            && tokens.get(i - 1) instanceof COSDictionary props && props.containsKey(COSName.MCID)) {
          open = props.getInt(COSName.MCID);
          clean = true;
          if (stream.topLevelBdc.putIfAbsent(open, i) != null) {
            stream.duplicates.put(open, i);
          }
        }
        depth++;
      } else if ("EMC".equals(name)) {
        depth--;
        if (depth < 0) {
          return null;
        }
        if (depth == 0 && open != null) {
          stream.decoration.put(open, clean);
          open = null;
        }
      }
    }
    return depth == 0 ? stream : null;
  }

  private static void write(PDDocument doc, COSDictionary owner, Map<COSDictionary, PDPage> pages, List<Object> tokens) throws IOException {
    PDPage page = pages.get(owner);
    if (page != null) {
      PDStream replacement = new PDStream(doc);
      try (OutputStream out = replacement.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(out).writeTokens(tokens);
      }
      page.setContents(replacement);
      return;
    }
    PDFormXObject form = new PDFormXObject((COSStream) owner);
    form.getCOSObject().removeItem(COSName.DECODE_PARMS);
    form.getCOSObject().removeItem(COSName.DP);
    form.getCOSObject().removeItem(COSName.FILTER);
    try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
      new ContentStreamWriter(out).writeTokens(tokens);
    }
  }
}
