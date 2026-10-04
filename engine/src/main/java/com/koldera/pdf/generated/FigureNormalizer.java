package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * Chromium tags {@code <figure>} as a Figure without /Alt holding the elements. An outer Figure with exactly one inner
 * Figure with non-blank /Alt (and at most one Caption) is collapsed into one Figure carrying that /Alt; if the
 * ParentTree cannot be resolved it is left unchanged. Any other outer Figure without /Alt is a grouping and becomes
 * {@code Div} (its Caption moves into a single Table, else becomes {@code P}). No /Alt is ever invented.
 */
public final class FigureNormalizer {

  private static final String FIGURE = "Figure";
  private static final String CAPTION = "Caption";
  private static final String TABLE = "Table";
  private static final List<COSName> CARRIED = List.of(COSName.LANG, COSName.getPDFName("ActualText"), COSName.E);

  private FigureNormalizer() {}

  /** Returns the number of figures changed (collapsed or retagged). */
  public static int normalize(PDDocument doc) {
    int changed = 0;
    for (PDStructureElement outer : StructTree.elementsOfType(doc, FIGURE)) {
      if (outer.getCOSObject().containsKey(COSName.ALT) || outer.getParent() == null || elementKids(outer).isEmpty()) {
        continue;
      }
      PDStructureElement inner = collapsibleInner(outer);
      if (inner != null) {
        if (collapse(outer, inner)) {
          changed++;
        }
        continue;
      }
      retagAsDiv(outer);
      changed++;
    }
    return changed;
  }

  private static boolean collapse(PDStructureElement outer, PDStructureElement inner) {
    COSDictionary innerDict = inner.getCOSObject();
    COSDictionary outerDict = outer.getCOSObject();
    if (!StructTree.unwrap(outer, inner)) {
      return false;
    }
    outerDict.setItem(COSName.ALT, innerDict.getItem(COSName.ALT));
    for (COSName key : CARRIED) {
      if (innerDict.containsKey(key) && !outerDict.containsKey(key)) {
        outerDict.setItem(key, innerDict.getItem(key));
      }
    }
    return true;
  }

  private static void retagAsDiv(PDStructureElement figure) {
    figure.setStructureType("Div");
    List<PDStructureElement> captions = new ArrayList<>();
    List<Object> others = new ArrayList<>();
    boolean captionFirst = false;
    for (Object kid : StructTree.kids(figure)) {
      if (kid instanceof PDStructureElement e && CAPTION.equals(e.getStructureType())) {
        captionFirst = others.isEmpty();
        captions.add(e);
      } else {
        others.add(kid);
      }
    }
    // The Caption goes first or last in the Table, on the side where it was, so reading order is unchanged.
    if (captions.size() == 1 && others.size() == 1 && others.get(0) instanceof PDStructureElement table
        && TABLE.equals(table.getStructureType())
        && elementKids(table).stream().noneMatch(e -> CAPTION.equals(e.getStructureType()))
        && StructTree.moveElement(figure, captions.get(0), table, captionFirst ? 0 : StructTree.kids(table).size())) {
      return;
    }
    for (PDStructureElement caption : captions) {
      caption.setStructureType("P");
    }
  }

  private static List<PDStructureElement> elementKids(PDStructureElement element) {
    List<PDStructureElement> out = new ArrayList<>();
    for (Object kid : StructTree.kids(element)) {
      if (kid instanceof PDStructureElement e) {
        out.add(e);
      }
    }
    return out;
  }

  private static PDStructureElement collapsibleInner(PDStructureElement outer) {
    PDStructureElement inner = null;
    int captions = 0;
    for (Object kid : StructTree.kids(outer)) {
      if (!(kid instanceof PDStructureElement element)) {
        return null;
      }
      if (FIGURE.equals(element.getStructureType()) && inner == null) {
        inner = element;
      } else if (CAPTION.equals(element.getStructureType()) && captions == 0) {
        captions++;
      } else {
        return null;
      }
    }
    if (inner == null || inner.getCOSObject().containsKey(COSName.ID)) {
      return null;
    }
    String alt = inner.getAlternateDescription();
    if (alt == null || alt.isBlank()) {
      return null;
    }
    List<Object> content = StructTree.kids(inner);
    if (content.isEmpty() || content.stream().anyMatch(k -> k instanceof PDStructureElement)) {
      return null;
    }
    return inner;
  }
}
