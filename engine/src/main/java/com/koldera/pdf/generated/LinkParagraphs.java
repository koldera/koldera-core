package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Chromium puts a link outside any paragraph-like box directly into {@code Document} or {@code Caption}, which the PAC
 * engine reports and {@link StructureRules} treats as blocking the identifier. For such a {@code Link}, the contiguous
 * run of inline siblings around it is moved into one new {@code P} ({@link StructTree#wrap}); a run that cannot be moved
 * is left. Order, painting and text are unchanged. Runs before {@link InlineBlocks#paragraphs}.
 */
public final class LinkParagraphs {

  private static final Set<String> INLINE_RUN = Set.of("Span", "Link", "Code", "Quote", "Note", "Reference", "BibEntry", "Em", "Strong", "Sub");

  private LinkParagraphs() {}

  private record Run(PDStructureElement holder, List<Object> kids) {}

  /** Returns the number of new paragraphs. */
  public static int apply(PDDocument doc) {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return 0;
    }
    List<Run> runs = new ArrayList<>();
    for (Object kid : root.getKids()) {
      if (kid instanceof PDStructureElement element) {
        collect(element, "Document", runs, new IdentityHashMap<>(), 0);
      }
    }
    int wrapped = 0;
    for (Run run : runs) {
      if (run.kids() == null) {
        run.holder().setStructureType("P");
        wrapped++;
        continue;
      }
      try {
        StructTree.wrap(run.holder(), run.kids(), "P");
        wrapped++;
      } catch (IllegalArgumentException e) {
        // fail closed: the run stays where it is
      }
    }
    return wrapped;
  }

  /** {@code parentContext}: the type of the nearest ancestor of {@code node} that is not NonStruct. */
  private static void collect(PDStructureElement node, String parentContext, List<Run> runs, Map<COSDictionary, Boolean> seen, int depth) {
    if (depth > 256 || seen.put(node.getCOSObject(), Boolean.TRUE) != null) {
      return;
    }
    String type = node.getStructureType();
    String context = "NonStruct".equals(type) ? parentContext : type;
    List<Object> kids = node.getKids();
    // Kids that end up inside a paragraph (moved by a run, or under a NonStruct that is one whole run)
    // are walked with context P, so nothing below them becomes a paragraph too (no P inside a P).
    Map<Object, Boolean> inParagraph = new IdentityHashMap<>();
    boolean wholeRun = false;
    int runsBefore = runs.size();
    if (InlineBlocks.GROUPING.contains(context) || "Caption".equals(context)) {
      List<Object> current = new ArrayList<>();
      boolean hasLink = false;
      for (Object kid : kids) {
        if (inline(kid)) {
          current.add(kid);
          hasLink |= kid instanceof PDStructureElement e && "Link".equals(e.getStructureType());
        } else {
          if (hasLink) {
            runs.add(new Run(node, current));
          }
          current = new ArrayList<>();
          hasLink = false;
        }
      }
      if (hasLink) {
        runs.add(new Run(node, current));
      }
      if (!runs.isEmpty() && runs.get(runs.size() - 1).holder() == node && "NonStruct".equals(type) && current.size() == kids.size()) {
        // The whole NonStruct is one inline run: it is itself the paragraph. In a grouping element
        // InlineBlocks.paragraphs retags it; in a Caption (which that step leaves alone) this step does.
        runs.remove(runs.size() - 1);
        wholeRun = true;
        if ("Caption".equals(parentContext)) {
          runs.add(new Run(node, null));
        }
      }
      for (int i = runsBefore; i < runs.size(); i++) {
        if (runs.get(i).kids() != null) {
          runs.get(i).kids().forEach(kid -> inParagraph.put(kid, Boolean.TRUE));
        }
      }
    }
    for (Object kid : kids) {
      if (kid instanceof PDStructureElement element) {
        collect(element, wholeRun || inParagraph.containsKey(kid) ? "P" : context, runs, seen, depth + 1);
      }
    }
  }

  private static boolean inline(Object kid) {
    if (kid instanceof Integer || kid instanceof PDMarkedContentReference) {
      return true;
    }
    if (!(kid instanceof PDStructureElement element)) {
      return false;
    }
    String type = element.getStructureType();
    return INLINE_RUN.contains(type) || ("NonStruct".equals(type) && InlineBlocks.allInline(element.getCOSObject(), 0));
  }
}
