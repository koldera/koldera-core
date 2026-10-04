package com.koldera.pdf.repair;

import com.koldera.pdf.generated.LinkContents;
import com.koldera.pdf.generated.McidPainter;
import com.koldera.pdf.generated.StructTree;
import com.koldera.pdf.structure.NumberTreeAppend;
import com.koldera.pdf.structure.NumberTreeView;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripperByArea;

/**
 * Puts the {@code Link} annotations of a tagged document into its structure tree (PDF/UA-1 7.18.1, 7.18.5). An annotation
 * an OBJR already references only gets its {@code /StructParent}; otherwise the element whose marked content lies under its
 * rectangle is found ({@link McidPainter}) and a new {@code Link} element holding the OBJR is placed after that MCID. Missing
 * {@code /Contents} come from the text under the rectangle or the link target. Marked content is never split. Deterministic.
 */
public final class LinkTagger {

  private static final COSName LINK = COSName.getPDFName("Link");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final Set<String> BESIDE_NOT_INSIDE = Set.of("Figure", "Formula", "Form");
  private static final int HIDDEN_FLAG = 2;
  private static final int MAX_DESCRIPTION = 1000;

  /**
   * Counts: refusal reason (null when done), new Link elements, annotations joined to a Link, annotations that only got their
   * /StructParent, annotations given /Contents, and annotations that could not be placed.
   */
  public record Outcome(String refusal, int tagged, int joined, int repaired, int described, int left) {
    static Outcome refused(String reason) {
      return new Outcome(reason, 0, 0, 0, 0, 0);
    }
  }

  private LinkTagger() {}

  /** Link annotations without /StructParent, the work {@link #apply} would do (0 for an untagged document too). */
  public static int untaggedLinks(PDDocument doc) {
    int n = 0;
    for (PDPage page : doc.getPages()) {
      for (COSDictionary annot : linkAnnotations(page, false)) {
        if (!annot.containsKey(COSName.STRUCT_PARENT) && !hidden(annot)) {
          n++;
        }
      }
    }
    return n;
  }

  public static Outcome apply(PDDocument doc) throws IOException {
    if (doc.getDocumentCatalog().getStructureTreeRoot() == null) {
      return Outcome.refused("document is not tagged");
    }
    COSDictionary root = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
    NumberTreeAppend.Appender appender = root.getDictionaryObject(COSName.PARENT_TREE) instanceof COSDictionary tree ? NumberTreeAppend.open(tree) : null;
    if (appender == null) {
      return Outcome.refused("the structure tree has no ParentTree that can be added to");
    }
    COSDictionary parentTree = (COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE);
    Map<Integer, COSBase> numbers = NumberTreeView.read(parentTree);
    Set<COSDictionary> inTree = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    Map<COSDictionary, COSDictionary> objrHolders = objrHolders(root, inTree);
    Map<COSDictionary, Deque<Integer>> reusable = unclaimedKeys(doc, numbers);
    COSDictionary roleMap = root.getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary m ? m : null;
    int nextKey = Math.max(appender.nextKey(), Math.max(0, root.getInt(COSName.PARENT_TREE_NEXT_KEY, 0)));

    int tagged = 0;
    int joined = 0;
    int repaired = 0;
    int described = 0;
    int left = 0;
    for (PDPage page : doc.getPages()) {
      List<COSDictionary> pending = new ArrayList<>();
      for (COSDictionary annot : linkAnnotations(page, true)) {
        if (annot.containsKey(COSName.STRUCT_PARENT) || hidden(annot)) {
          continue;
        }
        COSDictionary holder = objrHolders.get(annot);
        if (holder != null) {
          Deque<Integer> free = reusable.get(holder);
          if (free != null && !free.isEmpty()) {
            annot.setInt(COSName.STRUCT_PARENT, free.poll());
          } else {
            if (!appender.append(nextKey, holder)) {
              left++;
              continue;
            }
            annot.setInt(COSName.STRUCT_PARENT, nextKey++);
          }
          repaired++;
          continue;
        }
        pending.add(annot);
      }
      if (pending.isEmpty()) {
        continue;
      }
      PageContent content = PageContent.of(page, numbers, inTree);
      PDFTextStripperByArea underLinks = content.isEmpty() ? null : textUnder(page, pending);
      for (int i = 0; i < pending.size(); i++) {
        COSDictionary annot = pending.get(i);
        Rectangle2D rect = rectangle(annot);
        Placement placement = rect == null ? null : content.place(rect);
        if (placement == null) {
          left++;
          continue;
        }
        String type = standardType(placement.element(), roleMap);
        COSDictionary linkElement;
        if ("Link".equals(type)) {
          linkElement = placement.element();
          addKid(linkElement, objr(annot, page), -1);
          joined++;
        } else {
          COSDictionary parent = placement.element();
          int at = kidIndexOfMcid(parent, placement.mcid(), page);
          if (BESIDE_NOT_INSIDE.contains(type) && parent.getDictionaryObject(COSName.P) instanceof COSDictionary grand
              && grand.containsKey(COSName.S)) {
            at = kidIndexOf(grand, parent);
            parent = grand;
          }
          linkElement = new COSDictionary();
          linkElement.setItem(COSName.TYPE, COSName.STRUCT_ELEM);
          linkElement.setItem(COSName.S, LINK);
          linkElement.setItem(COSName.P, parent);
          linkElement.setItem(COSName.PG, page.getCOSObject());
          COSArray kids = new COSArray();
          kids.add(objr(annot, page));
          linkElement.setItem(COSName.K, kids);
          addKid(parent, linkElement, at < 0 ? -1 : at + 1);
          tagged++;
        }
        if (!appender.append(nextKey, linkElement)) {
          throw new IllegalStateException("the ParentTree refused a key it accepted before");
        }
        annot.setInt(COSName.STRUCT_PARENT, nextKey++);
        if (describe(doc, annot, underLinks, i)) {
          described++;
        }
      }
    }
    if (tagged + joined + repaired > 0) {
      root.setInt(COSName.PARENT_TREE_NEXT_KEY, nextKey);
    }
    described += LinkContents.apply(doc);
    return new Outcome(null, tagged, joined, repaired, described, left);
  }

  /** The Link annotation dictionaries of a page, in /Annots order; with {@code indirect}, a direct one is made indirect. */
  private static List<COSDictionary> linkAnnotations(PDPage page, boolean indirect) {
    List<COSDictionary> out = new ArrayList<>();
    if (!(page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots)) {
      return out;
    }
    for (COSBase entry : annots) {
      COSBase resolved = entry instanceof COSObject ref ? ref.getObject() : entry;
      if (resolved instanceof COSDictionary dict && LINK.equals(dict.getCOSName(COSName.SUBTYPE))) {
        if (indirect && !(entry instanceof COSObject)) {
          dict.setDirect(false);
        }
        out.add(dict);
      }
    }
    return out;
  }

  private static boolean hidden(COSDictionary annot) {
    return (annot.getInt(COSName.F, 0) & HIDDEN_FLAG) != 0;
  }

  private static Rectangle2D rectangle(COSDictionary annot) {
    if (!(annot.getDictionaryObject(COSName.RECT) instanceof COSArray array) || array.size() != 4) {
      return null;
    }
    PDRectangle r = new PDRectangle(array);
    return new Rectangle2D.Float(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
  }

  private static COSDictionary objr(COSDictionary annot, PDPage page) {
    COSDictionary objr = new COSDictionary();
    objr.setItem(COSName.TYPE, OBJR);
    objr.setItem(COSName.OBJ, annot);
    objr.setItem(COSName.PG, page.getCOSObject());
    return objr;
  }

  /** Inserts {@code kid} into {@code parent}'s /K at {@code index} (-1: at the end), turning a single /K into an array. */
  private static void addKid(COSDictionary parent, COSBase kid, int index) {
    List<COSBase> entries = StructTree.entries(parent);
    COSArray kids = new COSArray();
    entries.forEach(kids::add);
    if (index < 0 || index > kids.size()) {
      kids.add(kid);
    } else {
      kids.add(index, kid);
    }
    parent.setItem(COSName.K, kids);
  }

  private static int kidIndexOfMcid(COSDictionary element, int mcid, PDPage page) {
    List<COSBase> entries = StructTree.entries(element);
    COSBase elementPage = StructTree.resolve(element.getItem(COSName.PG));
    for (int i = 0; i < entries.size(); i++) {
      COSBase kid = StructTree.resolve(entries.get(i));
      if (kid instanceof COSInteger n && n.intValue() == mcid && elementPage == page.getCOSObject()) {
        return i;
      }
      if (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE)) && !d.containsKey(STM) && d.getInt(COSName.MCID) == mcid) {
        COSBase mcrPage = d.containsKey(COSName.PG) ? StructTree.resolve(d.getItem(COSName.PG)) : elementPage;
        if (mcrPage == page.getCOSObject()) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int kidIndexOf(COSDictionary parent, COSDictionary child) {
    List<COSBase> entries = StructTree.entries(parent);
    for (int i = 0; i < entries.size(); i++) {
      if (StructTree.resolve(entries.get(i)) == child) {
        return i;
      }
    }
    return -1;
  }

  /** The standard type an element's /S maps to through the /RoleMap (the /S itself when unmapped). */
  private static String standardType(COSDictionary element, COSDictionary roleMap) {
    String type = element.getNameAsString(COSName.S);
    for (int i = 0; roleMap != null && type != null && i < 16; i++) {
      if (!(roleMap.getDictionaryObject(COSName.getPDFName(type)) instanceof COSName next)) {
        break;
      }
      type = next.getName();
    }
    return type;
  }

  /** Sets /Contents when the annotation has none: the text under it, else its target; true when set. */
  private static boolean describe(PDDocument doc, COSDictionary annot, PDFTextStripperByArea underLinks, int index) throws IOException {
    String existing = annot.getString(COSName.CONTENTS);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    String text = underLinks == null ? "" : underLinks.getTextForRegion(Integer.toString(index));
    text = text == null ? "" : text.strip().replaceAll("\\s+", " ");
    if (text.length() > MAX_DESCRIPTION) {
      text = text.substring(0, MAX_DESCRIPTION);
    }
    String value = text.isEmpty() ? LinkContents.fallbackDescription(doc.getDocumentCatalog(), PDAnnotation.createAnnotation(annot)) : text;
    if (value == null || value.isBlank()) {
      return false;
    }
    annot.setString(COSName.CONTENTS, value);
    return true;
  }

  /**
   * One text region per pending annotation, named by its index; null on a rotated page, whose text
   * coordinates this does not convert (the target is used instead).
   */
  private static PDFTextStripperByArea textUnder(PDPage page, List<COSDictionary> pending) throws IOException {
    if (page.getRotation() % 360 != 0) {
      return null;
    }
    PDRectangle crop = page.getCropBox();
    PDFTextStripperByArea stripper = new PDFTextStripperByArea();
    stripper.setSortByPosition(true);
    for (int i = 0; i < pending.size(); i++) {
      Rectangle2D r = rectangle(pending.get(i));
      if (r != null) {
        stripper.addRegion(Integer.toString(i), new Rectangle2D.Double(
            r.getMinX() - crop.getLowerLeftX(), crop.getUpperRightY() - r.getMaxY(), r.getWidth(), r.getHeight()));
      }
    }
    stripper.extractRegions(page);
    return stripper;
  }

  /**
   * Every annotation dictionary an OBJR of the tree references, and the element holding that OBJR;
   * {@code inTree} receives every structure element reachable from the root.
   */
  private static Map<COSDictionary, COSDictionary> objrHolders(COSDictionary root, Set<COSDictionary> inTree) {
    Map<COSDictionary, COSDictionary> out = new IdentityHashMap<>();
    Set<COSDictionary> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<COSDictionary> todo = new ArrayDeque<>();
    todo.push(root);
    while (!todo.isEmpty()) {
      COSDictionary node = todo.pop();
      if (!seen.add(node) || seen.size() > 1_000_000) {
        continue;
      }
      for (COSBase entry : StructTree.entries(node)) {
        if (!(StructTree.resolve(entry) instanceof COSDictionary kid)) {
          continue;
        }
        if (OBJR.equals(kid.getCOSName(COSName.TYPE))) {
          if (StructTree.resolve(kid.getItem(COSName.OBJ)) instanceof COSDictionary target) {
            out.putIfAbsent(target, node);
          }
        } else if (!MCR.equals(kid.getCOSName(COSName.TYPE))) {
          inTree.add(kid);
          todo.push(kid);
        }
      }
    }
    return out;
  }

  /**
   * ParentTree keys whose value is a single element and that no annotation, page or form claims as its
   * /StructParent(s): an OBJR holder whose annotation lost its /StructParent gets its old key back.
   */
  private static Map<COSDictionary, Deque<Integer>> unclaimedKeys(PDDocument doc, Map<Integer, COSBase> numbers) {
    Set<Integer> claimed = new HashSet<>();
    Set<COSBase> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (PDPage page : doc.getPages()) {
      COSDictionary dict = page.getCOSObject();
      if (dict.getDictionaryObject(COSName.STRUCT_PARENTS) instanceof COSNumber n) {
        claimed.add(n.intValue());
      }
      claimFormKeys(dict.getDictionaryObject(COSName.RESOURCES), claimed, seen, 0);
      if (dict.getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots) {
        for (COSBase a : annots) {
          if (StructTree.resolve(a) instanceof COSDictionary annot) {
            if (annot.getDictionaryObject(COSName.STRUCT_PARENT) instanceof COSNumber n) {
              claimed.add(n.intValue());
            }
            claimAppearanceKeys(annot.getDictionaryObject(COSName.AP), claimed, seen);
          }
        }
      }
    }
    Map<COSDictionary, Deque<Integer>> out = new IdentityHashMap<>();
    for (Map.Entry<Integer, COSBase> e : new TreeMap<>(numbers).entrySet()) {
      if (!claimed.contains(e.getKey()) && StructTree.resolve(e.getValue()) instanceof COSDictionary element && element.containsKey(COSName.S)) {
        out.computeIfAbsent(element, k -> new ArrayDeque<>()).add(e.getKey());
      }
    }
    return out;
  }

  /** Adds the /StructParent(s) keys of every form XObject reachable from {@code resources} (forms nest through their own /Resources). */
  private static void claimFormKeys(COSBase resources, Set<Integer> claimed, Set<COSBase> seen, int depth) {
    if (!(StructTree.resolve(resources) instanceof COSDictionary res) || depth > 32 || !seen.add(res)
        || !(res.getDictionaryObject(COSName.XOBJECT) instanceof COSDictionary xobjects)) {
      return;
    }
    for (COSName name : xobjects.keySet()) {
      if (xobjects.getDictionaryObject(name) instanceof org.apache.pdfbox.cos.COSStream form && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE))) {
        claimForm(form, claimed, seen, depth);
      }
    }
  }

  private static void claimForm(org.apache.pdfbox.cos.COSStream form, Set<Integer> claimed, Set<COSBase> seen, int depth) {
    if (form.getDictionaryObject(COSName.STRUCT_PARENT) instanceof COSNumber n) {
      claimed.add(n.intValue());
    }
    if (form.getDictionaryObject(COSName.STRUCT_PARENTS) instanceof COSNumber n) {
      claimed.add(n.intValue());
    }
    claimFormKeys(form.getDictionaryObject(COSName.RESOURCES), claimed, seen, depth + 1);
  }

  /** Appearance streams (/N, /R, /D; each a stream or a dictionary of states) are forms too. */
  private static void claimAppearanceKeys(COSBase ap, Set<Integer> claimed, Set<COSBase> seen) {
    if (!(StructTree.resolve(ap) instanceof COSDictionary appearances)) {
      return;
    }
    for (COSName kind : List.of(COSName.N, COSName.R, COSName.D)) {
      COSBase entry = appearances.getDictionaryObject(kind);
      if (entry instanceof org.apache.pdfbox.cos.COSStream stream) {
        if (seen.add(stream)) {
          claimForm(stream, claimed, seen, 1);
        }
      } else if (entry instanceof COSDictionary states) {
        for (COSName state : states.keySet()) {
          if (states.getDictionaryObject(state) instanceof org.apache.pdfbox.cos.COSStream stream && seen.add(stream)) {
            claimForm(stream, claimed, seen, 1);
          }
        }
      }
    }
  }

  private record Placement(COSDictionary element, int mcid) {}

  /** What each tagged page-level MCID paints, and which structure element holds it. */
  private static final class PageContent {
    private final Map<Integer, COSDictionary> holders = new TreeMap<>();
    private final Map<Integer, List<Rectangle2D>> text = new HashMap<>();
    private final Map<Integer, List<Rectangle2D>> other = new HashMap<>();

    /** Only elements in {@code inTree} count: a ParentTree slot may still name an element the tree no longer holds. */
    static PageContent of(PDPage page, Map<Integer, COSBase> numbers, Set<COSDictionary> inTree) throws IOException {
      PageContent content = new PageContent();
      COSDictionary dict = page.getCOSObject();
      if (!(dict.getDictionaryObject(COSName.STRUCT_PARENTS) instanceof COSNumber key)
          || !(StructTree.resolve(numbers.get(key.intValue())) instanceof COSArray slots)) {
        return content;
      }
      for (int mcid = 0; mcid < slots.size(); mcid++) {
        if (StructTree.resolve(slots.get(mcid)) instanceof COSDictionary element && element.containsKey(COSName.S) && inTree.contains(element)) {
          content.holders.put(mcid, element);
        }
      }
      if (!content.holders.isEmpty()) {
        McidPainter.paint(page, (mcid, visible, glyph) -> {
          if (content.holders.containsKey(mcid) && visible.getWidth() >= 0 && visible.getHeight() >= 0) {
            (glyph ? content.text : content.other).computeIfAbsent(mcid, k -> new ArrayList<>()).add(visible);
          }
        });
      }
      return content;
    }

    boolean isEmpty() {
      return text.isEmpty() && other.isEmpty();
    }

    /** The element with the most text under {@code rect}, else the most painting, else the nearest; null when nothing is painted. */
    Placement place(Rectangle2D rect) {
      Placement byText = mostOverlap(text, rect);
      if (byText != null) {
        return byText;
      }
      Placement byPaint = mostOverlap(other, rect);
      return byPaint != null ? byPaint : nearest(rect);
    }

    private Placement mostOverlap(Map<Integer, List<Rectangle2D>> boxes, Rectangle2D rect) {
      Map<COSDictionary, Double> perElement = new IdentityHashMap<>();
      Map<COSDictionary, Integer> bestMcid = new IdentityHashMap<>();
      Map<COSDictionary, Double> bestMcidArea = new IdentityHashMap<>();
      List<COSDictionary> order = new ArrayList<>();
      for (Map.Entry<Integer, COSDictionary> h : holders.entrySet()) {
        double area = 0;
        for (Rectangle2D box : boxes.getOrDefault(h.getKey(), List.of())) {
          area += overlap(box, rect);
        }
        if (area <= 0) {
          continue;
        }
        COSDictionary element = h.getValue();
        if (!perElement.containsKey(element)) {
          order.add(element);
        }
        perElement.merge(element, area, Double::sum);
        if (area > bestMcidArea.getOrDefault(element, 0.0)) {
          bestMcidArea.put(element, area);
          bestMcid.put(element, h.getKey());
        }
      }
      COSDictionary best = null;
      for (COSDictionary element : order) {
        if (best == null || perElement.get(element) > perElement.get(best)) {
          best = element;
        }
      }
      return best == null ? null : new Placement(best, bestMcid.get(best));
    }

    private Placement nearest(Rectangle2D rect) {
      Placement best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Map.Entry<Integer, COSDictionary> h : holders.entrySet()) {
        List<Rectangle2D> all = new ArrayList<>(text.getOrDefault(h.getKey(), List.of()));
        all.addAll(other.getOrDefault(h.getKey(), List.of()));
        for (Rectangle2D box : all) {
          double d = distance(box, rect);
          if (d < bestDistance) {
            bestDistance = d;
            best = new Placement(h.getValue(), h.getKey());
          }
        }
      }
      return best;
    }

    private static double overlap(Rectangle2D a, Rectangle2D b) {
      double w = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
      double h = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
      return w > 0 && h > 0 ? w * h : 0;
    }

    private static double distance(Rectangle2D a, Rectangle2D b) {
      double dx = Math.max(0, Math.max(a.getMinX() - b.getMaxX(), b.getMinX() - a.getMaxX()));
      double dy = Math.max(0, Math.max(a.getMinY() - b.getMaxY(), b.getMinY() - a.getMaxY()));
      return Math.hypot(dx, dy);
    }
  }
}
