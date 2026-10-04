package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Chromium tags a block holding only inline content as {@code NonStruct} and an image block as a {@code Figure} without
 * {@code /Placement}, which leaves inline elements directly in a grouping element (reported by the PAC engine).
 * {@link #paragraphs} retags such a {@code NonStruct} {@code P}; {@link #figurePlacement} adds {@code /Placement /Block};
 * {@link #spans} (run first) retags an all-inline {@code Div} inside inline containers {@code Span}. Only /S or Layout change.
 */
public final class InlineBlocks {

  /** Grouping elements: they may hold blocks, not inline content (ISO 32000-1 14.8.4.2; Aside maps to Div). */
  static final Set<String> GROUPING = Set.of("Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Aside", "DocumentFragment", "Index", "Private");

  /** Inline-level elements (ISO 32000-1 14.8.4.4; PDF 2.0 Strong/Em/Sub map to Span); a Figure without /Placement is inline. */
  static final Set<String> INLINE = Set.of(
      "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot", "Ruby", "Warichu", "Figure", "Formula", "Strong", "Em", "Sub");

  /** Elements that hold inline content only (paragraph-like and inline elements). */
  static final Set<String> INLINE_CONTAINERS = Set.of(
      "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "Lbl", "Span", "Quote", "Code", "Link", "Reference", "Strong", "Em", "Sub");

  private static final COSName NON_STRUCT = COSName.getPDFName("NonStruct");
  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName LAYOUT = COSName.getPDFName("Layout");
  private static final COSName PLACEMENT = COSName.getPDFName("Placement");
  private static final COSName BLOCK = COSName.getPDFName("Block");

  private InlineBlocks() {}

  /** Retags every all-inline {@code Div} in an inline container as {@code Span}; returns how many. */
  public static int spans(PDDocument doc) {
    List<COSDictionary> retag = new ArrayList<>();
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root != null) {
      walkDivs(root.getCOSObject(), "Document", retag, 0);
    }
    for (COSDictionary element : retag) {
      element.setItem(COSName.S, COSName.getPDFName("Span"));
    }
    return retag.size();
  }

  private static void walkDivs(COSDictionary node, String context, List<COSDictionary> retag, int depth) {
    if (depth > 256) {
      return;
    }
    for (COSBase entry : StructTree.entries(node)) {
      if (!(StructTree.resolve(entry) instanceof COSDictionary kid) || !isElement(kid)) {
        continue;
      }
      String type = kid.getNameAsString(COSName.S);
      String kidContext = NON_STRUCT.getName().equals(type) ? context : type;
      if ("Div".equals(type) && INLINE_CONTAINERS.contains(context) && (StructTree.entries(kid).isEmpty() || allInline(kid, 0))) {
        retag.add(kid);
        kidContext = "Span";
      }
      walkDivs(kid, kidContext, retag, depth + 1);
    }
  }

  /** Retags every all-inline {@code NonStruct} directly (through NonStruct only) inside a grouping element as {@code P}; returns how many. */
  public static int paragraphs(PDDocument doc) {
    List<COSDictionary> retag = new ArrayList<>();
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root != null) {
      walk(root.getCOSObject(), "Document", retag, new ArrayList<>(), 0);
    }
    for (COSDictionary element : retag) {
      element.setItem(COSName.S, COSName.P);
    }
    return retag.size();
  }

  /** Gives every block-level Figure without /Placement {@code /Placement /Block}; returns how many. */
  public static int figurePlacement(PDDocument doc) {
    List<COSDictionary> figures = new ArrayList<>();
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root != null) {
      walk(root.getCOSObject(), "Document", new ArrayList<>(), figures, 0);
    }
    int changed = 0;
    for (COSDictionary figure : figures) {
      if (hasPlacement(figure)) {
        continue;
      }
      COSDictionary layout = layoutAttribute(figure);
      layout.setItem(PLACEMENT, BLOCK);
      changed++;
    }
    return changed;
  }

  /**
   * Pre-order walk; {@code context} is the type of the nearest ancestor that is not NonStruct. A NonStruct
   * chosen for retagging makes its subtree's context {@code P}, so a nested NonStruct is left as it is.
   */
  private static void walk(COSDictionary node, String context, List<COSDictionary> paragraphs, List<COSDictionary> figures, int depth) {
    if (depth > 256) {
      return;
    }
    for (COSBase entry : StructTree.entries(node)) {
      if (!(StructTree.resolve(entry) instanceof COSDictionary kid) || !isElement(kid)) {
        continue;
      }
      String type = kid.getNameAsString(COSName.S);
      String kidContext = context;
      if (NON_STRUCT.getName().equals(type)) {
        if (GROUPING.contains(context) && allInline(kid, 0)) {
          paragraphs.add(kid);
          kidContext = "P";
        }
      } else {
        if ("Figure".equals(type) && GROUPING.contains(context)) {
          figures.add(kid);
        }
        kidContext = type;
      }
      walk(kid, kidContext, paragraphs, figures, depth + 1);
    }
  }

  /** True when the element has kids and every kid is marked content or an inline element (NonStruct: recursively). */
  static boolean allInline(COSDictionary element, int depth) {
    List<COSBase> kids = StructTree.entries(element);
    if (kids.isEmpty() || depth > 64) {
      return false;
    }
    for (COSBase entry : kids) {
      COSBase kid = StructTree.resolve(entry);
      if (kid instanceof COSInteger) {
        continue;
      }
      if (!(kid instanceof COSDictionary dict)) {
        return false;
      }
      if (MCR.equals(dict.getCOSName(COSName.TYPE))) {
        continue;
      }
      if (OBJR.equals(dict.getCOSName(COSName.TYPE)) || !isElement(dict)) {
        return false;
      }
      String type = dict.getNameAsString(COSName.S);
      if (NON_STRUCT.getName().equals(type) ? !allInline(dict, depth + 1) : !INLINE.contains(type)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isElement(COSDictionary dict) {
    return dict.getDictionaryObject(COSName.S) instanceof COSName && !MCR.equals(dict.getCOSName(COSName.TYPE)) && !OBJR.equals(dict.getCOSName(COSName.TYPE));
  }

  private static boolean hasPlacement(COSDictionary element) {
    return attributes(element).stream().anyMatch(a -> a.containsKey(PLACEMENT));
  }

  /** The element's attribute dictionaries (a revision number after one is skipped). */
  private static List<COSDictionary> attributes(COSDictionary element) {
    List<COSDictionary> out = new ArrayList<>();
    COSBase a = StructTree.resolve(element.getItem(COSName.A));
    if (a instanceof COSDictionary single) {
      out.add(single);
    } else if (a instanceof COSArray array) {
      for (COSBase item : array) {
        if (StructTree.resolve(item) instanceof COSDictionary dict) {
          out.add(dict);
        }
      }
    }
    return out;
  }

  /** The element's Layout attribute object, appended (revision 0 semantics kept: no revision number) when it has none. */
  private static COSDictionary layoutAttribute(COSDictionary element) {
    for (COSDictionary attribute : attributes(element)) {
      if (LAYOUT.equals(attribute.getCOSName(COSName.O))) {
        return attribute;
      }
    }
    COSDictionary layout = new COSDictionary();
    layout.setItem(COSName.O, LAYOUT);
    COSBase existing = element.getItem(COSName.A);
    if (existing == null) {
      element.setItem(COSName.A, layout);
    } else {
      COSArray array = new COSArray();
      if (StructTree.resolve(existing) instanceof COSArray old) {
        old.forEach(array::add);
      } else {
        array.add(existing);
      }
      array.add(layout);
      element.setItem(COSName.A, array);
    }
    return layout;
  }
}
