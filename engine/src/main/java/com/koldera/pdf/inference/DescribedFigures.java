package com.koldera.pdf.inference;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Carries the descriptions of a {@code tagged_unusable} upload's described Figures to the Figures the rebuild creates over
 * the same images. {@link #read} (before stripping) records the image items each described Figure covers, keyed by page,
 * stream and image-operator ordinal; {@link #carry} (after the rebuild) copies {@code /Alt}, {@code /ActualText} and
 * {@code /Lang} only when each new Figure covers exactly the items of one described Figure (1:1); otherwise it refuses.
 */
final class DescribedFigures {

  /** The mapping is not exact; the reason is the clause the rule appends. */
  static final class Refused extends Exception {
    private Refused(String detail) {
      super(detail);
    }
  }

  /** A described Figure's description, copied as it is. */
  private record Description(COSString alt, COSString actualText, COSString lang) {}

  /** A marked-content reference: page index, stream id ({@link #PAGE_STREAM} or a form's), MCID. */
  private record McRef(int page, int stream, int mcid) {}

  /** An image-painting operator: page index, stream id, ordinal among the stream's image-painting operators. */
  private record ImageKey(int page, int stream, int ordinal) {}

  /** What {@link #read} recorded; stream ids stay valid for the same document object. */
  static final class Snapshot {
    private final Map<COSStream, Integer> streams;
    private final Map<COSDictionary, Description> descriptions = new IdentityHashMap<>();
    private final Map<COSDictionary, Set<ImageKey>> items = new IdentityHashMap<>();

    private Snapshot(Map<COSStream, Integer> streams) {
      this.streams = streams;
    }

    boolean isEmpty() {
      return descriptions.isEmpty();
    }
  }

  private static final int PAGE_STREAM = -1;
  private static final int MAX_FORM_DEPTH = 32;
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final String NOT_EXACT = "not exact";

  private DescribedFigures() {}

  /** The described Figures of {@code document}'s tree and the image items each covers; empty when there are none. */
  static Snapshot read(PDDocument document) throws Refused, IOException {
    Snapshot snapshot = new Snapshot(new IdentityHashMap<>());
    var root = document.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return snapshot;
    }
    COSDictionary roleMap = roleMap(root.getCOSObject());
    Map<COSDictionary, Integer> pages = pageIndexes(document);
    Map<McRef, COSDictionary> figureOfRef = new HashMap<>();
    // Frames: node, inherited /Pg, the described Figure it lies in (or null).
    Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Object[]> todo = new ArrayDeque<>();
    todo.push(new Object[] {root.getCOSObject().getItem(COSName.K), null, null});
    while (!todo.isEmpty()) {
      Object[] frame = todo.pop();
      COSBase node = resolve((COSBase) frame[0]);
      COSDictionary pg = (COSDictionary) frame[1];
      COSDictionary figure = (COSDictionary) frame[2];
      if (node == null) {
        continue;
      }
      if (node instanceof COSInteger mcid) {
        if (figure != null) {
          addRef(figureOfRef, new McRef(pageIndex(pages, pg), PAGE_STREAM, mcid.intValue()), figure);
        }
        continue;
      }
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof COSArray array) {
        for (int i = array.size() - 1; i >= 0; i--) {
          todo.push(new Object[] {array.get(i), pg, figure});
        }
      } else if (node instanceof COSDictionary dict) {
        if (dict.getDictionaryObject(COSName.S) instanceof COSName type) {
          COSDictionary ownPg = dict.getDictionaryObject(COSName.PG) instanceof COSDictionary page ? page : pg;
          COSDictionary inFigure = figure;
          if (mapsToFigure(type.getName(), roleMap)
              && (nonBlank(dict.getDictionaryObject(COSName.ALT)) || nonBlank(dict.getDictionaryObject(COSName.ACTUAL_TEXT)))) {
            if (figure != null) {
              throw new Refused("a described Figure lies inside another");
            }
            inFigure = dict;
            snapshot.descriptions.put(dict, new Description(string(dict, COSName.ALT), string(dict, COSName.ACTUAL_TEXT), string(dict, COSName.LANG)));
            snapshot.items.put(dict, new HashSet<>());
          }
          COSBase k = dict.getItem(COSName.K);
          if (k != null) {
            todo.push(new Object[] {k, ownPg, inFigure});
          }
        } else if (dict.containsKey(COSName.MCID) && figure != null) {
          addRef(figureOfRef, mcr(dict, pg, pages, snapshot.streams), figure);
        }
      }
    }
    if (snapshot.isEmpty()) {
      return snapshot;
    }
    for (Map.Entry<ImageKey, List<List<McRef>>> image : images(document, snapshot.streams).entrySet()) {
      COSDictionary owner = null;
      for (List<McRef> enclosing : image.getValue()) {
        for (McRef ref : enclosing) {
          COSDictionary figure = figureOfRef.get(ref);
          if (figure != null && owner != null && owner != figure) {
            throw new Refused("an image lies in two described Figures");
          }
          owner = figure != null ? figure : owner;
        }
      }
      if (owner != null) {
        snapshot.items.get(owner).add(image.getKey());
      }
    }
    for (Set<ImageKey> covered : snapshot.items.values()) {
      if (covered.isEmpty()) {
        throw new Refused("a described Figure covers no image");
      }
    }
    return snapshot;
  }

  /**
   * After the rebuild: copies each description onto the one new Figure over the same images, and
   * returns how many new Figures were described; refuses, writing nothing, when the mapping is not exact.
   */
  static int carry(PDDocument document, Snapshot snapshot) throws Refused, IOException {
    if (snapshot.isEmpty()) {
      return 0;
    }
    var root = document.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      throw new Refused("the rebuilt document has no tags");
    }
    COSDictionary roleMap = roleMap(root.getCOSObject());
    Map<McRef, COSDictionary> ownerOfRef = owners(root.getCOSObject(), pageIndexes(document), snapshot.streams);
    Map<ImageKey, COSDictionary> ownerOfImage = new HashMap<>();
    Set<ImageKey> ambiguous = new HashSet<>();
    for (Map.Entry<ImageKey, List<List<McRef>>> image : images(document, snapshot.streams).entrySet()) {
      Set<COSDictionary> owners = Collections.newSetFromMap(new IdentityHashMap<>());
      for (List<McRef> enclosing : image.getValue()) {
        COSDictionary innermost = null;
        for (McRef ref : enclosing) {
          innermost = ownerOfRef.getOrDefault(ref, innermost);
        }
        owners.add(innermost);
      }
      if (owners.size() != 1) {
        ambiguous.add(image.getKey());
      } else {
        ownerOfImage.put(image.getKey(), owners.iterator().next());
      }
    }
    Map<COSDictionary, COSDictionary> describedOfNew = new IdentityHashMap<>();
    for (Map.Entry<COSDictionary, Set<ImageKey>> old : snapshot.items.entrySet()) {
      COSDictionary target = null;
      for (ImageKey key : old.getValue()) {
        COSDictionary owner = ownerOfImage.get(key);
        if (ambiguous.contains(key) || owner == null || (target != null && target != owner)) {
          throw new Refused(NOT_EXACT);
        }
        target = owner;
      }
      if (!(target.getDictionaryObject(COSName.S) instanceof COSName type) || !mapsToFigure(type.getName(), roleMap)) {
        throw new Refused("a described image is no longer in a Figure");
      }
      if (describedOfNew.put(target, old.getKey()) != null) {
        throw new Refused("a rebuilt Figure covers the images of two described Figures");
      }
    }
    for (Map.Entry<COSDictionary, COSDictionary> entry : describedOfNew.entrySet()) {
      Set<ImageKey> covered = new HashSet<>();
      for (Map.Entry<ImageKey, COSDictionary> image : ownerOfImage.entrySet()) {
        if (image.getValue() == entry.getKey()) {
          covered.add(image.getKey());
        }
      }
      if (!covered.equals(snapshot.items.get(entry.getValue()))) {
        throw new Refused("a rebuilt Figure also covers images that were not described");
      }
    }
    // Every check passed: only now is anything written.
    for (Map.Entry<COSDictionary, COSDictionary> entry : describedOfNew.entrySet()) {
      Description description = snapshot.descriptions.get(entry.getValue());
      COSDictionary target = entry.getKey();
      if (nonBlank(description.alt())) {
        target.setItem(COSName.ALT, description.alt());
      }
      if (nonBlank(description.actualText())) {
        target.setItem(COSName.ACTUAL_TEXT, description.actualText());
      }
      if (nonBlank(description.lang())) {
        target.setItem(COSName.LANG, description.lang());
      }
    }
    return describedOfNew.size();
  }

  /** Every MCID of the tree → the element that holds it directly. */
  private static Map<McRef, COSDictionary> owners(COSDictionary root, Map<COSDictionary, Integer> pages, Map<COSStream, Integer> streams) throws Refused {
    Map<McRef, COSDictionary> out = new HashMap<>();
    Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Object[]> todo = new ArrayDeque<>();
    todo.push(new Object[] {root.getItem(COSName.K), null, null});
    while (!todo.isEmpty()) {
      Object[] frame = todo.pop();
      COSBase node = resolve((COSBase) frame[0]);
      COSDictionary pg = (COSDictionary) frame[1];
      COSDictionary parent = (COSDictionary) frame[2];
      if (node instanceof COSInteger mcid) {
        if (parent != null) {
          out.putIfAbsent(new McRef(pageIndex(pages, pg), PAGE_STREAM, mcid.intValue()), parent);
        }
        continue;
      }
      if (node == null || !seen.add(node)) {
        continue;
      }
      if (node instanceof COSArray array) {
        for (int i = array.size() - 1; i >= 0; i--) {
          todo.push(new Object[] {array.get(i), pg, parent});
        }
      } else if (node instanceof COSDictionary dict) {
        if (dict.containsKey(COSName.S)) {
          COSDictionary ownPg = dict.getDictionaryObject(COSName.PG) instanceof COSDictionary page ? page : pg;
          COSBase k = dict.getItem(COSName.K);
          if (k != null) {
            todo.push(new Object[] {k, ownPg, dict});
          }
        } else if (dict.containsKey(COSName.MCID) && parent != null) {
          out.putIfAbsent(mcr(dict, pg, pages, streams), parent);
        }
      }
    }
    return out;
  }

  /** Every image-painting operator of the document → the MCIDs around it, outermost first, once per time it is painted. */
  private static Map<ImageKey, List<List<McRef>>> images(PDDocument document, Map<COSStream, Integer> streams) throws Refused, IOException {
    Map<ImageKey, List<List<McRef>>> out = new LinkedHashMap<>();
    int p = 0;
    for (PDPage page : document.getPages()) {
      if (page.hasContents()) {
        walk(new PDFStreamParser(page).parse(), page.getResources(), p, PAGE_STREAM, new ArrayList<>(), streams, out, 0);
      }
      p++;
    }
    return out;
  }

  private static void walk(List<Object> tokens, PDResources resources, int page, int stream, List<McRef> outer, Map<COSStream, Integer> streams,
      Map<ImageKey, List<List<McRef>>> out, int depth) throws Refused, IOException {
    if (depth > MAX_FORM_DEPTH) {
      throw new Refused("forms are nested too deeply");
    }
    Deque<McRef> open = new ArrayDeque<>();
    int ordinal = 0;
    int operandStart = 0;
    for (int i = 0; i < tokens.size(); i++) {
      if (!(tokens.get(i) instanceof Operator op)) {
        continue;
      }
      List<Object> operands = tokens.subList(operandStart, i);
      operandStart = i + 1;
      switch (op.getName()) {
        case "BDC" -> {
          COSDictionary properties = operands.size() == 2 ? properties(operands.get(1), resources) : null;
          open.push(properties != null && properties.getDictionaryObject(COSName.MCID) instanceof COSInteger mcid
              ? new McRef(page, stream, mcid.intValue())
              : new McRef(page, stream, -1));
        }
        case "BMC" -> open.push(new McRef(page, stream, -1));
        case "EMC" -> {
          if (open.isEmpty()) {
            throw new Refused("marked content is not balanced");
          }
          open.pop();
        }
        case "BI" -> out.computeIfAbsent(new ImageKey(page, stream, ordinal++), k -> new ArrayList<>()).add(enclosing(outer, open));
        case "Do" -> {
          if (operands.isEmpty() || !(operands.get(operands.size() - 1) instanceof COSName name) || resources == null) {
            continue;
          }
          COSDictionary xObjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
          if (xObjects == null || !(xObjects.getDictionaryObject(name) instanceof COSStream xObject)) {
            continue;
          }
          if (COSName.IMAGE.equals(xObject.getCOSName(COSName.SUBTYPE))) {
            out.computeIfAbsent(new ImageKey(page, stream, ordinal++), k -> new ArrayList<>()).add(enclosing(outer, open));
          } else if (COSName.FORM.equals(xObject.getCOSName(COSName.SUBTYPE))) {
            COSDictionary own = xObject.getCOSDictionary(COSName.RESOURCES);
            PDFormXObject form = new PDFormXObject(xObject);
            walk(new PDFStreamParser(form).parse(), own != null ? new PDResources(own) : resources, page, streamId(streams, xObject),
                enclosing(outer, open), streams, out, depth + 1);
          }
        }
        default -> {
          // other operators paint no image
        }
      }
    }
    if (!open.isEmpty()) {
      throw new Refused("marked content is not balanced");
    }
  }

  /** The references around the current point, outermost first, without the MCID-less sequences. */
  private static List<McRef> enclosing(List<McRef> outer, Deque<McRef> open) {
    List<McRef> out = new ArrayList<>(outer);
    List<McRef> inner = new ArrayList<>(open);
    Collections.reverse(inner);
    for (McRef ref : inner) {
      if (ref.mcid() >= 0) {
        out.add(ref);
      }
    }
    return out;
  }

  private static COSDictionary properties(Object operand, PDResources resources) {
    if (operand instanceof COSDictionary inline) {
      return inline;
    }
    if (operand instanceof COSName named && resources != null) {
      COSDictionary all = resources.getCOSObject().getCOSDictionary(COSName.PROPERTIES);
      if (all != null && all.getDictionaryObject(named) instanceof COSDictionary dict) {
        return dict;
      }
    }
    return null;
  }

  private static McRef mcr(COSDictionary dict, COSDictionary pg, Map<COSDictionary, Integer> pages, Map<COSStream, Integer> streams) throws Refused {
    COSDictionary page = dict.getDictionaryObject(COSName.PG) instanceof COSDictionary own ? own : pg;
    int stream = dict.getDictionaryObject(STM) instanceof COSStream form ? streamId(streams, form) : PAGE_STREAM;
    if (!(dict.getDictionaryObject(COSName.MCID) instanceof COSInteger mcid)) {
      throw new Refused("a marked-content reference has no MCID");
    }
    return new McRef(pageIndex(pages, page), stream, mcid.intValue());
  }

  private static void addRef(Map<McRef, COSDictionary> figureOfRef, McRef ref, COSDictionary figure) throws Refused {
    COSDictionary before = figureOfRef.put(ref, figure);
    if (before != null && before != figure) {
      throw new Refused("an MCID belongs to two described Figures");
    }
  }

  private static int streamId(Map<COSStream, Integer> streams, COSStream stream) {
    return streams.computeIfAbsent(stream, s -> streams.size());
  }

  private static Map<COSDictionary, Integer> pageIndexes(PDDocument document) {
    Map<COSDictionary, Integer> out = new IdentityHashMap<>();
    int p = 0;
    for (PDPage page : document.getPages()) {
      out.put(page.getCOSObject(), p++);
    }
    return out;
  }

  private static int pageIndex(Map<COSDictionary, Integer> pages, COSDictionary page) throws Refused {
    Integer index = page == null ? null : pages.get(page);
    if (index == null) {
      throw new Refused("an MCID is not on a page of the document");
    }
    return index;
  }

  static COSDictionary roleMap(COSDictionary root) {
    return root.getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary map ? map : null;
  }

  /** Whether {@code type} is {@code Figure} or the /RoleMap maps it there, transitively; a cycle ends the chain. */
  static boolean mapsToFigure(String type, COSDictionary roleMap) {
    Set<String> chain = new HashSet<>();
    String current = type;
    while (current != null && chain.add(current)) {
      if ("Figure".equals(current)) {
        return true;
      }
      COSName mapped = roleMap == null ? null : roleMap.getCOSName(COSName.getPDFName(current));
      current = mapped == null ? null : mapped.getName();
    }
    return false;
  }

  private static COSString string(COSDictionary dict, COSName key) {
    return dict.getDictionaryObject(key) instanceof COSString value && !value.getString().isBlank() ? value : null;
  }

  private static boolean nonBlank(COSBase value) {
    return value instanceof COSString text && !text.getString().isBlank();
  }

  private static COSBase resolve(COSBase value) {
    return value instanceof COSObject ref ? ref.getObject() : value;
  }
}
