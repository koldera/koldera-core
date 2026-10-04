package com.koldera.pdf.generated;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * PDF/UA-1 (veraPDF 7.4.2-1) requires the first heading to be {@code H1} and no level to be skipped, but real pages break
 * both. Headings are renumbered by outline depth in structure order (relative nesting kept, gaps closed: {@code H2 H1 H3 H4
 * H2 H4} becomes {@code H1 H1 H2 H3 H2 H3}); a valid sequence and trees using {@code H} are left alone. Only /S changes
 * and the change is reported (finding {@code HEADING_LEVELS_RENUMBERED}).
 */
public final class HeadingLevels {

  private HeadingLevels() {}

  /** Headings retagged, and a summary such as {@code "H2→H1 ×1, H4→H3 ×2"} (null when none). */
  public record Outcome(int headings, String mapping) {}

  public static Outcome renumber(PDDocument doc) {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return new Outcome(0, null);
    }
    List<COSDictionary> headings = new ArrayList<>();
    if (!collect(root.getCOSObject(), headings, 0, StructTree.visitedSet())) {
      return new Outcome(0, null);
    }
    int[] levels = headings.stream().mapToInt(h -> Character.digit(h.getNameAsString(COSName.S).charAt(1), 10)).toArray();
    int[] renumbered = levels(levels);
    Map<String, Integer> changes = new TreeMap<>();
    for (int i = 0; i < headings.size(); i++) {
      if (renumbered[i] != levels[i]) {
        headings.get(i).setItem(COSName.S, COSName.getPDFName("H" + renumbered[i]));
        changes.merge("H" + levels[i] + "→H" + renumbered[i], 1, Integer::sum);
      }
    }
    int changed = changes.values().stream().mapToInt(Integer::intValue).sum();
    if (changed == 0) {
      return new Outcome(0, null);
    }
    List<String> parts = new ArrayList<>();
    changes.forEach((change, count) -> parts.add(change + " ×" + count));
    return new Outcome(changed, String.join(", ", parts));
  }

  /** Outline depth of each heading (1-based), from the original levels. */
  public static int[] levels(int[] original) {
    int[] out = new int[original.length];
    Deque<Integer> open = new ArrayDeque<>();
    for (int i = 0; i < original.length; i++) {
      while (!open.isEmpty() && open.peek() >= original[i]) {
        open.pop();
      }
      out[i] = open.size() + 1;
      open.push(original[i]);
    }
    return out;
  }

  /**
   * Collects H1-H6 in pre-order, each element once however often it is listed; false when the tree
   * uses H (strongly structured) or is too deep.
   */
  private static boolean collect(COSDictionary node, List<COSDictionary> out, int depth, Set<COSDictionary> visited) {
    if (depth > 256) {
      return false;
    }
    for (COSBase entry : StructTree.entries(node)) {
      if (StructTree.resolve(entry) instanceof COSDictionary kid && kid.getDictionaryObject(COSName.S) instanceof COSName s && visited.add(kid)) {
        String type = s.getName();
        if ("H".equals(type)) {
          return false;
        }
        if (type.matches("H[1-6]")) {
          out.add(kid);
        }
        if (!collect(kid, out, depth + 1, visited)) {
          return false;
        }
      }
    }
    return true;
  }
}
