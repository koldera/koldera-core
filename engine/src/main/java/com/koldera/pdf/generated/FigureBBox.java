package com.koldera.pdf.generated;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * Gives every {@code Figure} whose content lies on one page a Layout {@code /BBox} in default user space: the union of
 * what is painted in its marked content (each clipped), intersected with the MediaBox, without a nested {@code Caption}.
 * Figures spanning pages, with content in form XObjects, with nothing painted, or with a Layout /BBox are left alone.
 */
public final class FigureBBox {

  private static final COSName LAYOUT = COSName.getPDFName("Layout");
  private static final COSName BBOX = COSName.getPDFName("BBox");
  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName STM = COSName.getPDFName("Stm");

  private FigureBBox() {}

  /** Returns the number of Figures given a BBox. */
  public static int apply(PDDocument doc) throws IOException {
    Map<COSDictionary, PDPage> pages = new IdentityHashMap<>();
    for (PDPage page : doc.getPages()) {
      pages.put(page.getCOSObject(), page);
    }
    Map<COSDictionary, Map<Integer, Rectangle2D>> painted = new IdentityHashMap<>();
    int changed = 0;
    for (PDStructureElement figure : StructTree.elementsOfType(doc, "Figure")) {
      COSDictionary dict = figure.getCOSObject();
      if (hasLayoutBBox(dict)) {
        continue;
      }
      Content content = new Content();
      collect(dict, content, 0);
      if (!content.ok || content.pages.size() != 1 || content.mcids.isEmpty()) {
        continue;
      }
      COSDictionary pageDict = content.pages.get(0);
      PDPage page = pages.get(pageDict);
      if (page == null) {
        continue;
      }
      Map<Integer, Rectangle2D> byMcid = painted.get(pageDict);
      if (byMcid == null) {
        byMcid = McidPainter.unions(page);
        painted.put(pageDict, byMcid);
      }
      Rectangle2D union = null;
      for (int mcid : content.mcids) {
        Rectangle2D box = byMcid.get(mcid);
        if (box != null) {
          union = union == null ? box : union.createUnion(box);
        }
      }
      if (union == null) {
        continue;
      }
      PDRectangle media = page.getMediaBox();
      Rectangle2D clipped = union.createIntersection(new Rectangle2D.Float(media.getLowerLeftX(), media.getLowerLeftY(), media.getWidth(), media.getHeight()));
      if (clipped.isEmpty()) {
        continue;
      }
      appendLayout(dict, clipped);
      changed++;
    }
    return changed;
  }

  private static final class Content {
    boolean ok = true;
    final List<COSDictionary> pages = new ArrayList<>();
    final List<Integer> mcids = new ArrayList<>();

    void page(COSBase page) {
      if (!(StructTree.resolve(page) instanceof COSDictionary dict)) {
        ok = false;
        return;
      }
      if (pages.stream().noneMatch(p -> p == dict)) {
        pages.add(dict);
      }
    }
  }

  private static void collect(COSDictionary node, Content content, int depth) {
    if (depth > 256) {
      content.ok = false;
      return;
    }
    COSBase page = node.getItem(COSName.PG);
    for (COSBase entry : StructTree.entries(node)) {
      COSBase kid = StructTree.resolve(entry);
      if (kid instanceof COSInteger mcid) {
        content.page(page);
        content.mcids.add(mcid.intValue());
      } else if (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE))) {
        if (d.containsKey(STM)) {
          content.ok = false;
          return;
        }
        content.page(d.containsKey(COSName.PG) ? d.getItem(COSName.PG) : page);
        content.mcids.add(d.getInt(COSName.MCID));
      } else if (kid instanceof COSDictionary d && OBJR.equals(d.getCOSName(COSName.TYPE))) {
        content.page(d.containsKey(COSName.PG) ? d.getItem(COSName.PG) : page);
      } else if (kid instanceof COSDictionary d && d.containsKey(COSName.S)) {
        if (!"Caption".equals(d.getNameAsString(COSName.S))) {
          collect(d, content, depth + 1);
        }
      }
    }
  }

  private static boolean hasLayoutBBox(COSDictionary element) {
    for (COSDictionary attribute : attributes(element)) {
      if (LAYOUT.equals(attribute.getCOSName(COSName.O)) && attribute.containsKey(BBOX)) {
        return true;
      }
    }
    return false;
  }

  private static List<COSDictionary> attributes(COSDictionary element) {
    List<COSDictionary> out = new ArrayList<>();
    COSBase a = StructTree.resolve(element.getItem(COSName.A));
    if (a instanceof COSDictionary d) {
      out.add(d);
    } else if (a instanceof COSArray array) {
      for (COSBase entry : array) {
        if (StructTree.resolve(entry) instanceof COSDictionary d) {
          out.add(d);
        }
      }
    }
    return out;
  }

  private static void appendLayout(COSDictionary element, Rectangle2D box) {
    COSDictionary layout = new COSDictionary();
    layout.setItem(COSName.O, LAYOUT);
    COSArray bbox = new COSArray();
    bbox.add(new COSFloat((float) box.getMinX()));
    bbox.add(new COSFloat((float) box.getMinY()));
    bbox.add(new COSFloat((float) box.getMaxX()));
    bbox.add(new COSFloat((float) box.getMaxY()));
    for (COSDictionary attribute : attributes(element)) {
      if (LAYOUT.equals(attribute.getCOSName(COSName.O)) && !attribute.containsKey(BBOX)) {
        // An existing Layout attribute gets the BBox; no second Layout object.
        attribute.setItem(BBOX, bbox);
        return;
      }
    }
    layout.setItem(BBOX, bbox);
    COSBase existing = element.getItem(COSName.A);
    COSBase resolved = StructTree.resolve(existing);
    if (resolved instanceof COSArray array) {
      array.add(layout);
    } else if (existing != null) {
      COSArray array = new COSArray();
      array.add(existing);
      array.add(layout);
      element.setItem(COSName.A, array);
    } else {
      element.setItem(COSName.A, layout);
    }
  }
}
