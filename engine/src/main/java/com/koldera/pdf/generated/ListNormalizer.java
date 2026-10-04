package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * Chromium tags a list item as {@code LI > [Lbl, content...]} without {@code LBody}. Each {@code LI} keeps a leading
 * {@code Lbl} and everything after moves into one new {@code LBody}; no label is created. {@link #wrapOrphans} wraps
 * runs of {@code LI} siblings whose parent is not an {@code L} (veraPDF 7.2-17) in one new {@code L}.
 */
public final class ListNormalizer {

  private ListNormalizer() {}

  /** Returns the number of list items changed. */
  public static int normalize(PDDocument doc) {
    int changed = 0;
    for (PDStructureElement item : StructTree.elementsOfType(doc, "LI")) {
      List<Object> kids = StructTree.kids(item);
      int start = !kids.isEmpty() && isType(kids.get(0), "Lbl") ? 1 : 0;
      List<Object> body = kids.subList(start, kids.size());
      // Empty body (a bare label) or any existing Lbl/LBody after the leading label: not ours to reshape.
      if (body.isEmpty() || body.stream().anyMatch(k -> isType(k, "LBody") || isType(k, "Lbl"))) {
        continue;
      }
      StructTree.wrap(item, List.copyOf(body), "LBody");
      changed++;
    }
    return changed;
  }

  /** Wraps each run of consecutive LI kids of a non-L element in a new L; returns how many lists were created. */
  public static int wrapOrphans(PDDocument doc) {
    List<PDStructureElement> parents = new ArrayList<>();
    for (PDStructureElement item : StructTree.elementsOfType(doc, "LI")) {
      if (item.getParent() instanceof PDStructureElement parent && !"L".equals(parent.getStructureType())
          && parents.stream().noneMatch(p -> p.getCOSObject() == parent.getCOSObject())) {
        parents.add(parent);
      }
    }
    int created = 0;
    for (PDStructureElement parent : parents) {
      List<List<Object>> runs = new ArrayList<>();
      List<Object> run = new ArrayList<>();
      for (Object kid : StructTree.kids(parent)) {
        if (isType(kid, "LI")) {
          run.add(kid);
        } else if (!run.isEmpty()) {
          runs.add(run);
          run = new ArrayList<>();
        }
      }
      if (!run.isEmpty()) {
        runs.add(run);
      }
      for (List<Object> items : runs) {
        StructTree.wrap(parent, items, "L");
        created++;
      }
    }
    return created;
  }

  private static boolean isType(Object kid, String type) {
    return kid instanceof PDStructureElement e && type.equals(e.getStructureType());
  }
}
