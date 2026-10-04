package com.koldera.pdf.repair;

import com.koldera.pdf.generated.StructTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Tagged uploads ({@code PDF_TAGGED_STRUCTURE_REPAIR_V1}): a structure element with an empty or missing /K is removed from
 * its parent unless it is a {@code TD}/{@code TH}/{@code TR}, has an /ID, is a direct kid of the root, or is named by the
 * /ParentTree ({@link StructTree#removableWhenEmpty}). Removals go through {@link StructTree#remove}; passes repeat up to
 * {@link #MAX_PASSES} and stop below depth 256, so cyclic or fanned-out /K cannot loop. Only structure dictionaries change.
 */
public final class EmptyElements {

  static final int MAX_PASSES = 64;
  private static final int MAX_DEPTH = 256;

  private EmptyElements() {}

  /** Removes the empty elements; returns how many were removed. */
  public static int remove(PDDocument doc) {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return 0;
    }
    Set<COSDictionary> referenced = StructTree.referencedByParentTree(root.getCOSObject());
    int removed = 0;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      List<COSDictionary[]> candidates = new ArrayList<>(); // {parent, element}
      Set<COSDictionary> visited = identitySet();
      visited.add(root.getCOSObject());
      collect(root.getCOSObject(), true, referenced, candidates, visited, 0);
      int thisPass = 0;
      for (COSDictionary[] candidate : candidates) {
        if (StructTree.remove(new PDStructureElement(candidate[0]), new PDStructureElement(candidate[1]))) {
          thisPass++;
        }
      }
      removed += thisPass;
      if (thisPass == 0) {
        break;
      }
    }
    return removed;
  }

  /** Empty removable elements below {@code node} with the parent they were found under, in pre-order. */
  private static void collect(
      COSDictionary node, boolean isRoot, Set<COSDictionary> referenced, List<COSDictionary[]> out, Set<COSDictionary> visited, int depth) {
    if (depth > MAX_DEPTH) {
      return;
    }
    for (COSBase entry : StructTree.entries(node)) {
      if (StructTree.resolve(entry) instanceof COSDictionary kid && kid.containsKey(COSName.S) && visited.add(kid)) {
        if (!isRoot && StructTree.removableWhenEmpty(kid) && !referenced.contains(kid)) {
          out.add(new COSDictionary[] {node, kid});
        } else {
          collect(kid, false, referenced, out, visited, depth + 1);
        }
      }
    }
  }

  private static Set<COSDictionary> identitySet() {
    return Collections.newSetFromMap(new IdentityHashMap<>());
  }
}
