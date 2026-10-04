package com.koldera.pdf.generated;

import com.koldera.pdf.structure.NumberTreeView;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * Chromium tags a printed {@code <input>}, {@code <textarea>} or {@code <button>} as a {@code Form} with {@code /PrintField}
 * but without a widget annotation. A {@code Form} with no widget OBJR is retagged {@code Div} and loses its {@code /PrintField}
 * objects; text painted right after its own sequence and inside its painted box is wrapped with a new {@code Span} carrying
 * {@code /ActualText}. Nothing is invented; unattributed text is left to {@link UntaggedArtifacts}. Page streams only.
 */
public final class WidgetlessForms {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final COSName WIDGET = COSName.getPDFName("Widget");
  private static final COSName PRINT_FIELD = COSName.getPDFName("PrintField");
  private static final COSName SPAN = COSName.getPDFName("Span");
  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");
  /** Operators that neither paint nor start marked content: they may sit between the field's sequence and its value. */
  private static final Set<String> STATE_ONLY = Set.of("q", "Q", "cm", "gs", "w", "J", "j", "M", "d", "ri", "i", "CS", "cs", "SC", "SCN", "sc", "scn", "G", "g", "RG", "rg", "K", "k");

  /** Points a value's glyph bounds may extend past the field's painted box (anti-aliased borders, font boxes). */
  static final float BOUNDS_TOLERANCE = 2f;

  private WidgetlessForms() {}

  /**
   * @param fields {@code Form} elements retagged {@code Div}
   * @param values painted values attributed to one of them as a {@code Span} with {@code /ActualText}
   */
  public record Outcome(int fields, int values) {}

  public static Outcome apply(PDDocument doc) throws IOException {
    Map<COSDictionary, PDPage> pagesByDict = new IdentityHashMap<>();
    for (PDPage page : doc.getPages()) {
      pagesByDict.put(page.getCOSObject(), page);
    }
    // page -> (MCID -> the retagged element whose own /K holds it)
    Map<PDPage, Map<Integer, COSDictionary>> owners = new LinkedHashMap<>();
    int fields = 0;
    for (PDStructureElement form : StructTree.elementsOfType(doc, "Form")) {
      COSDictionary element = form.getCOSObject();
      if (hasWidget(element, 0)) {
        continue;
      }
      element.setItem(COSName.S, COSName.getPDFName("Div"));
      dropPrintField(element);
      fields++;
      COSBase elementPage = element.getItem(COSName.PG);
      for (COSBase entry : StructTree.entries(element)) {
        COSBase kid = StructTree.resolve(entry);
        Integer mcid = null;
        COSBase owner = elementPage;
        if (kid instanceof COSInteger number) {
          mcid = number.intValue();
        } else if (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE)) && !d.containsKey(STM)) {
          mcid = d.getInt(COSName.MCID);
          if (d.containsKey(COSName.PG)) {
            owner = d.getItem(COSName.PG);
          }
        }
        if (mcid != null && StructTree.resolve(owner) instanceof COSDictionary pageDict && pagesByDict.containsKey(pageDict)) {
          owners.computeIfAbsent(pagesByDict.get(pageDict), p -> new HashMap<>()).put(mcid, element);
        }
      }
    }
    int values = 0;
    for (Map.Entry<PDPage, Map<Integer, COSDictionary>> e : owners.entrySet()) {
      values += attributeValues(doc, e.getKey(), e.getValue());
    }
    return new Outcome(fields, values);
  }

  private static boolean hasWidget(COSDictionary node, int depth) {
    if (depth > 256) {
      return true; // fail closed: leave a runaway tree alone
    }
    for (COSBase entry : StructTree.entries(node)) {
      COSBase kid = StructTree.resolve(entry);
      if (kid instanceof COSDictionary d && OBJR.equals(d.getCOSName(COSName.TYPE))) {
        if (StructTree.resolve(d.getItem(COSName.OBJ)) instanceof COSDictionary annotation && WIDGET.equals(annotation.getCOSName(COSName.SUBTYPE))) {
          return true;
        }
      } else if (kid instanceof COSDictionary d && d.containsKey(COSName.S) && hasWidget(d, depth + 1)) {
        return true;
      }
    }
    return false;
  }

  /** Removes attribute objects owned by {@code /PrintField} (and the revision number that follows one in an array). */
  private static void dropPrintField(COSDictionary element) {
    COSBase a = StructTree.resolve(element.getItem(COSName.A));
    if (a instanceof COSDictionary single) {
      if (PRINT_FIELD.equals(single.getCOSName(COSName.O))) {
        element.removeItem(COSName.A);
      }
      return;
    }
    if (!(a instanceof COSArray array)) {
      return;
    }
    COSArray kept = new COSArray();
    boolean skipRevision = false;
    for (COSBase entry : array) {
      COSBase value = StructTree.resolve(entry);
      if (skipRevision && value instanceof COSNumber) {
        skipRevision = false;
        continue;
      }
      skipRevision = false;
      if (value instanceof COSDictionary d && PRINT_FIELD.equals(d.getCOSName(COSName.O))) {
        skipRevision = true;
        continue;
      }
      kept.add(entry);
    }
    if (kept.size() == 0) {
      element.removeItem(COSName.A);
    } else if (kept.size() != array.size()) {
      element.setItem(COSName.A, kept);
    }
  }

  /** A text object to wrap: tokens {@code [bt, et]} and the text it paints. */
  private record TextObject(int bt, int et, String shown) {}

  /** The text objects attributed to one element on this page. */
  private record Value(COSDictionary element, int afterMcid, List<TextObject> objects) {}

  private static int attributeValues(PDDocument doc, PDPage page, Map<Integer, COSDictionary> owners) throws IOException {
    if (!page.hasContents()) {
      return 0;
    }
    COSArray parentArray = parentTreeArray(doc, page);
    if (parentArray == null) {
      return 0;
    }
    List<Object> tokens = new PDFStreamParser(page).parse();
    PDResources resources = page.getResources();
    List<Value> values = new ArrayList<>();
    int maxMcid = -1;
    int depth = 0;
    List<Integer> openMcids = new ArrayList<>();
    Value pending = null; // the element whose sequence just closed, collecting its text objects
    int bt = -1;
    boolean btClean = false;
    StringBuilder shown = null;
    COSName font = null;
    List<COSName> savedFonts = new ArrayList<>();
    List<COSBase> operands = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      Object token = tokens.get(i);
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      String name = op.getName();
      switch (name) {
        case "BDC", "BMC" -> {
          Integer mcid = null;
          if ("BDC".equals(name) && operands.size() == 2 && operands.get(1) instanceof COSDictionary props && props.containsKey(COSName.MCID)) {
            mcid = props.getInt(COSName.MCID);
            maxMcid = Math.max(maxMcid, mcid);
          }
          openMcids.add(depth == 0 ? mcid : null);
          depth++;
          pending = close(pending, values);
          btClean = false;
        }
        case "EMC" -> {
          if (depth == 0) {
            return 0; // unbalanced: leave the page alone
          }
          depth--;
          Integer closed = openMcids.remove(openMcids.size() - 1);
          pending = close(pending, values);
          btClean = false;
          if (depth == 0 && closed != null && owners.containsKey(closed)) {
            pending = new Value(owners.get(closed), closed, new ArrayList<>());
          }
        }
        case "BT" -> {
          bt = i;
          btClean = depth == 0 && pending != null;
          shown = new StringBuilder();
        }
        case "ET" -> {
          if (btClean && shown != null) {
            pending.objects().add(new TextObject(bt, i, shown.toString()));
          } else {
            pending = close(pending, values);
          }
          bt = -1;
          btClean = false;
        }
        case "q" -> savedFonts.add(font);
        case "Q" -> font = savedFonts.isEmpty() ? font : savedFonts.remove(savedFonts.size() - 1);
        case "Tf" -> font = !operands.isEmpty() && operands.get(0) instanceof COSName fontName ? fontName : null;
        default -> {
          if (TEXT_SHOWING.contains(name)) {
            if (btClean && shown != null) {
              String text = ShownText.decode(resources, font, operands);
              if (text == null) {
                shown = null; // not decodable: this object is not attributed
              } else {
                shown.append(text);
              }
            }
          } else if (bt == -1 && !STATE_ONLY.contains(name)) {
            pending = close(pending, values); // painting or anything else between: the link to the field is lost
          }
        }
      }
      operands.clear();
    }
    if (depth != 0) {
      return 0;
    }
    close(pending, values);
    values.removeIf(v -> v.objects().isEmpty() || isBlank(joined(v)));
    if (values.isEmpty()) {
      return 0;
    }
    values = insideFieldBounds(doc, page, tokens, values, owners, maxMcid + 1);
    values.removeIf(v -> v.objects().isEmpty() || isBlank(joined(v)));
    if (values.isEmpty()) {
      return 0;
    }
    int next = Math.max(maxMcid + 1, parentArray.size());
    while (parentArray.size() < next) {
      parentArray.add(COSNull.NULL);
    }
    Map<Integer, Integer> startAt = new HashMap<>(); // bt token -> new MCID
    Map<Integer, Integer> endAt = new HashMap<>(); // et token -> new MCID
    for (Value value : values) {
      COSDictionary span = new COSDictionary();
      span.setItem(COSName.TYPE, COSName.STRUCT_ELEM);
      span.setItem(COSName.S, SPAN);
      span.setItem(COSName.P, value.element());
      span.setItem(COSName.PG, page.getCOSObject());
      span.setString(COSName.ACTUAL_TEXT, joined(value));
      COSArray kids = new COSArray();
      for (TextObject object : value.objects()) {
        int mcid = next++;
        startAt.put(object.bt(), mcid);
        endAt.put(object.et(), mcid);
        kids.add(COSInteger.get(mcid));
        parentArray.add(span);
      }
      span.setItem(COSName.K, kids.size() == 1 ? kids.get(0) : kids);
      insertAfter(value.element(), value.afterMcid(), page.getCOSObject(), span);
    }
    List<Object> out = wrapped(tokens, startAt, endAt);
    PDStream replacement = new PDStream(doc);
    try (OutputStream os = replacement.createOutputStream(COSName.FLATE_DECODE)) {
      new ContentStreamWriter(os).writeTokens(out);
    }
    page.setContents(replacement);
    return values.size();
  }

  /** {@code tokens} with {@code /Span <</MCID n>> BDC} before each start token and {@code EMC} after each end token. */
  private static List<Object> wrapped(List<Object> tokens, Map<Integer, Integer> startAt, Map<Integer, Integer> endAt) {
    List<Object> out = new ArrayList<>(tokens.size() + startAt.size() * 4);
    for (int i = 0; i < tokens.size(); i++) {
      Integer begin = startAt.get(i);
      if (begin != null) {
        COSDictionary props = new COSDictionary();
        props.setInt(COSName.MCID, begin);
        out.add(SPAN);
        out.add(props);
        out.add(Operator.getOperator("BDC"));
      }
      out.add(tokens.get(i));
      if (endAt.containsKey(i)) {
        out.add(Operator.getOperator("EMC"));
      }
    }
    return out;
  }

  /**
   * Each value cut to its leading text objects that lie inside the field's own painted box. The page
   * is measured on a throw-away copy whose content wraps every candidate text object in a probe MCID
   * (numbered from {@code firstProbe}, above every MCID the page uses); the page itself is untouched.
   */
  private static List<Value> insideFieldBounds(PDDocument doc, PDPage page, List<Object> tokens, List<Value> values, Map<Integer, COSDictionary> owners, int firstProbe)
      throws IOException {
    Map<Integer, Integer> startAt = new HashMap<>();
    Map<Integer, Integer> endAt = new HashMap<>();
    Map<TextObject, Integer> probeOf = new IdentityHashMap<>();
    int probe = firstProbe;
    for (Value value : values) {
      for (TextObject object : value.objects()) {
        startAt.put(object.bt(), probe);
        endAt.put(object.et(), probe);
        probeOf.put(object, probe++);
      }
    }
    COSStream probeStream = doc.getDocument().createCOSStream();
    try (OutputStream os = probeStream.createOutputStream()) {
      new ContentStreamWriter(os).writeTokens(wrapped(tokens, startAt, endAt));
    }
    COSDictionary probePage = new COSDictionary(page.getCOSObject());
    probePage.setItem(COSName.CONTENTS, probeStream);
    Map<Integer, Rectangle2D> painted = McidPainter.unions(new PDPage(probePage));

    Map<COSDictionary, Rectangle2D> fieldBox = new IdentityHashMap<>();
    owners.forEach((mcid, element) -> {
      Rectangle2D box = painted.get(mcid);
      if (box != null) {
        fieldBox.merge(element, box, Rectangle2D::createUnion);
      }
    });
    List<Value> kept = new ArrayList<>();
    for (Value value : values) {
      Rectangle2D box = fieldBox.get(value.element());
      List<TextObject> inside = new ArrayList<>();
      for (TextObject object : value.objects()) {
        Rectangle2D glyphs = painted.get(probeOf.get(object));
        if (box == null || glyphs == null || !grown(box).contains(glyphs)) {
          break;
        }
        inside.add(object);
      }
      kept.add(new Value(value.element(), value.afterMcid(), inside));
    }
    return kept;
  }

  private static Rectangle2D grown(Rectangle2D box) {
    return new Rectangle2D.Double(
        box.getX() - BOUNDS_TOLERANCE, box.getY() - BOUNDS_TOLERANCE, box.getWidth() + 2 * BOUNDS_TOLERANCE, box.getHeight() + 2 * BOUNDS_TOLERANCE);
  }

  private static Value close(Value pending, List<Value> values) {
    if (pending != null && !pending.objects().isEmpty()) {
      values.add(pending);
    }
    return null;
  }

  private static String joined(Value value) {
    StringBuilder out = new StringBuilder();
    value.objects().forEach(o -> out.append(o.shown()));
    return out.toString().replaceAll("\\s+", " ").trim();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Puts {@code span} into {@code element}'s /K right after the kid that holds {@code mcid} (at the end if not found). */
  private static void insertAfter(COSDictionary element, int mcid, COSDictionary page, COSDictionary span) {
    List<COSBase> entries = StructTree.entries(element);
    int at = entries.size();
    for (int i = 0; i < entries.size(); i++) {
      COSBase kid = StructTree.resolve(entries.get(i));
      if ((kid instanceof COSInteger n && n.intValue() == mcid)
          || (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE)) && d.getInt(COSName.MCID) == mcid)) {
        at = i + 1;
      }
    }
    COSArray kids = new COSArray();
    entries.forEach(kids::add);
    kids.add(at, span);
    element.setItem(COSName.K, kids);
  }

  /** The ParentTree array of the page's MCIDs, or null when the page has none. */
  private static COSArray parentTreeArray(PDDocument doc, PDPage page) {
    COSDictionary pageDict = page.getCOSObject();
    if (!pageDict.containsKey(COSName.STRUCT_PARENTS) || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
      return null;
    }
    COSBase tree = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
    COSBase value = StructTree.resolve(NumberTreeView.read(tree).get(pageDict.getInt(COSName.STRUCT_PARENTS)));
    return value instanceof COSArray array ? array : null;
  }
}
