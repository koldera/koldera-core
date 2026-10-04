package com.koldera.pdf.generated;

import java.util.ArrayList;
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
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Structure rules the PAC engine checks and veraPDF's PDF/UA-1 profile does not, limited to those decidable exactly from
 * the tree: Ruby, heading order, list, table, TBody without THead, figure/link/Div placement, marked content in legal
 * position, blank figure alt. The identifier step writes {@code pdfuaid:part=1} only when these report nothing.
 * {@code NonStruct} is transparent and types go through /RoleMap. When in doubt a rule reports nothing. Read-only.
 */
public final class StructureRules {

  public static final String RUBY = "RUBY";
  public static final String HEADING_ORDER = "HEADING_ORDER";
  public static final String LIST = "LIST";
  public static final String TABLE = "TABLE";
  public static final String TBODY_WITHOUT_THEAD = "TBODY_WITHOUT_THEAD";
  public static final String FIGURE_PLACEMENT = "FIGURE_PLACEMENT";
  public static final String LINK_PLACEMENT = "LINK_PLACEMENT";
  public static final String CONTENT_POSITION = "CONTENT_POSITION";
  public static final String FIGURE_ALT_BLANK = "FIGURE_ALT_BLANK";
  public static final String DIV_PLACEMENT = "DIV_PLACEMENT";

  private static final int MAX_DEPTH = 256;
  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName PLACEMENT = COSName.getPDFName("Placement");
  private static final COSName CLASS_MAP = COSName.getPDFName("ClassMap");
  private static final String NON_STRUCT = "NonStruct";

  private static final Set<String> STANDARD = Set.of(
      "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index", "NonStruct", "Private",
      "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot",
      "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP",
      "Figure", "Formula", "Form");

  private static final Set<String> RUBY_PARTS = Set.of("RB", "RT", "RP");
  private static final List<String> RUBY_SEQUENCE = List.of("RB", "RP", "RT", "RP");
  private static final Set<String> LIST_FAMILY = Set.of("L", "LI", "Lbl", "LBody");
  private static final Set<String> TABLE_FAMILY = Set.of("TR", "TH", "TD", "THead", "TBody", "TFoot");
  private static final Set<String> ROW_GROUPS = Set.of("THead", "TBody", "TFoot");
  private static final Set<String> NO_DIRECT_CONTENT = Set.of("Table", "THead", "TBody", "TFoot", "TR", "L");

  private StructureRules() {}

  /** Findings per rule id, sorted by id; empty when the document has no structure tree or breaks none of the rules. */
  public static Map<String, Integer> check(PDDocument doc) {
    Map<String, Integer> out = new TreeMap<>();
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return out;
    }
    COSDictionary rootDict = root.getCOSObject();
    Walk walk = new Walk(
        rootDict.getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary roleMap ? roleMap : null,
        rootDict.getDictionaryObject(CLASS_MAP) instanceof COSDictionary classMap ? classMap : null,
        out);
    walk.visit(rootDict, "", 0);
    walk.headings();
    return out;
  }

  /** {@code "FIGURE_PLACEMENT ×2, RUBY ×1"}, in id order; null when there are no findings. */
  public static String summary(Map<String, Integer> findings) {
    if (findings.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    new TreeMap<>(findings).forEach((rule, count) -> parts.add(rule + " ×" + count));
    return String.join(", ", parts);
  }

  private static final class Walk {
    private final COSDictionary roleMap;
    private final COSDictionary classMap;
    private final Map<String, Integer> out;
    private final Map<COSDictionary, Boolean> seen = new IdentityHashMap<>();
    private final List<String> headingLevels = new ArrayList<>();
    private boolean stronglyStructured;

    Walk(COSDictionary roleMap, COSDictionary classMap, Map<String, Integer> out) {
      this.roleMap = roleMap;
      this.classMap = classMap;
      this.out = out;
    }

    private void report(String rule) {
      out.merge(rule, 1, Integer::sum);
    }

    /** {@code context}: the standard type of the nearest ancestor that is not NonStruct ("" for the tree root). */
    void visit(COSDictionary node, String context, int depth) {
      if (depth > MAX_DEPTH || seen.put(node, Boolean.TRUE) != null) {
        return;
      }
      for (COSBase entry : StructTree.entries(node)) {
        if (!(StructTree.resolve(entry) instanceof COSDictionary kid) || !isElement(kid)) {
          continue;
        }
        String type = type(kid);
        if (NON_STRUCT.equals(type)) {
          visit(kid, context, depth + 1);
          continue;
        }
        if (seen.containsKey(kid)) {
          continue;
        }
        checkPlacement(kid, type, context);
        checkKids(kid, type);
        if ("Figure".equals(type) && kid.getDictionaryObject(COSName.ALT) instanceof COSString alt && isBlank(alt.getString())) {
          report(FIGURE_ALT_BLANK);
        }
        if ("H".equals(type)) {
          stronglyStructured = true;
        } else if (type.matches("H[1-6]")) {
          headingLevels.add(type);
        }
        visit(kid, type, depth + 1);
      }
    }

    /** Rules about where an element may sit, from its parent's (NonStruct-transparent) type. */
    private void checkPlacement(COSDictionary element, String type, String parent) {
      if (RUBY_PARTS.contains(type) && !"Ruby".equals(parent)) {
        report(RUBY);
      } else if ("LI".equals(type) && !"L".equals(parent)) {
        report(LIST);
      } else if ("LBody".equals(type) && !"LI".equals(parent)) {
        report(LIST);
      } else if (("TH".equals(type) || "TD".equals(type)) && !"TR".equals(parent)) {
        report(TABLE);
      } else if ("TR".equals(type) && !"Table".equals(parent) && !ROW_GROUPS.contains(parent)) {
        report(TABLE);
      } else if (ROW_GROUPS.contains(type) && !"Table".equals(parent)) {
        report(TABLE);
      } else if ("Figure".equals(type) && InlineBlocks.GROUPING.contains(parent) && !hasPlacement(element)) {
        report(FIGURE_PLACEMENT);
      } else if ("Link".equals(type) && (InlineBlocks.GROUPING.contains(parent) || "Caption".equals(parent))) {
        report(LINK_PLACEMENT);
      } else if ("Div".equals(type) && InlineBlocks.INLINE_CONTAINERS.contains(parent)) {
        report(DIV_PLACEMENT);
      }
    }

    /** Rules about what an element may hold. A kid of the same family is judged by its own placement rule, never twice. */
    private void checkKids(COSDictionary element, String type) {
      if ("Ruby".equals(type)) {
        List<String> kids = new ArrayList<>();
        for (COSBase entry : StructTree.entries(element)) {
          COSBase kid = StructTree.resolve(entry);
          kids.add(kid instanceof COSDictionary dict && isElement(dict) ? type(dict) : "content");
        }
        if (!RUBY_SEQUENCE.equals(kids)) {
          report(RUBY);
        }
        return;
      }
      if (NO_DIRECT_CONTENT.contains(type) || InlineBlocks.GROUPING.contains(type)) {
        for (COSBase entry : StructTree.entries(element)) {
          COSBase kid = StructTree.resolve(entry);
          if (kid instanceof COSInteger || (kid instanceof COSDictionary dict && MCR.equals(dict.getCOSName(COSName.TYPE)))) {
            report(CONTENT_POSITION);
          }
        }
      }
      List<String> kids = elementKids(element, 0);
      switch (type) {
        case "L" -> kids.stream().filter(k -> !LIST_FAMILY.contains(k) && !"Caption".equals(k)).forEach(k -> report(LIST));
        case "LI" -> kids.stream().filter(k -> !LIST_FAMILY.contains(k)).forEach(k -> report(LIST));
        case "Table" -> {
          kids.stream().filter(k -> !TABLE_FAMILY.contains(k) && !"Caption".equals(k)).forEach(k -> report(TABLE));
          if (kids.contains("TBody") && !kids.contains("THead")) {
            report(TBODY_WITHOUT_THEAD);
          }
        }
        case "THead", "TBody", "TFoot", "TR" -> kids.stream().filter(k -> !TABLE_FAMILY.contains(k)).forEach(k -> report(TABLE));
        default -> {
          // other types hold anything as far as these rules go
        }
      }
    }

    /** Types of the element kids, NonStruct kids replaced by their own element kids. */
    private List<String> elementKids(COSDictionary element, int depth) {
      List<String> out = new ArrayList<>();
      if (depth > MAX_DEPTH) {
        return out;
      }
      for (COSBase entry : StructTree.entries(element)) {
        if (StructTree.resolve(entry) instanceof COSDictionary kid && isElement(kid) && kid != element) {
          String type = type(kid);
          if (NON_STRUCT.equals(type)) {
            out.addAll(elementKids(kid, depth + 1));
          } else {
            out.add(type);
          }
        }
      }
      return out;
    }

    void headings() {
      if (stronglyStructured || headingLevels.isEmpty()) {
        return;
      }
      int previous = 0;
      for (String heading : headingLevels) {
        int level = heading.charAt(1) - '0';
        if (level > previous + 1) {
          report(HEADING_ORDER);
        }
        previous = level;
      }
    }

    /** The element's standard type through the /RoleMap (the type itself when it is standard or does not map to one). */
    private String type(COSDictionary element) {
      String type = element.getNameAsString(COSName.S);
      String current = type;
      for (int i = 0; i < 16 && current != null && !STANDARD.contains(current) && roleMap != null; i++) {
        current = roleMap.getDictionaryObject(COSName.getPDFName(current)) instanceof COSName mapped ? mapped.getName() : null;
      }
      return current != null && STANDARD.contains(current) ? current : type;
    }

    /** /Placement in one of the element's attribute objects, directly in /A or through a /C class. */
    private boolean hasPlacement(COSDictionary element) {
      List<COSDictionary> attributes = new ArrayList<>();
      addAttributes(StructTree.resolve(element.getItem(COSName.A)), attributes);
      if (classMap != null) {
        COSBase classes = StructTree.resolve(element.getItem(COSName.C));
        List<COSBase> names = new ArrayList<>();
        if (classes instanceof COSArray array) {
          array.forEach(names::add);
        } else if (classes != null) {
          names.add(classes);
        }
        for (COSBase name : names) {
          if (StructTree.resolve(name) instanceof COSName className) {
            addAttributes(classMap.getDictionaryObject(className), attributes);
          }
        }
      }
      return attributes.stream().anyMatch(a -> a.containsKey(PLACEMENT));
    }

    private static void addAttributes(COSBase a, List<COSDictionary> out) {
      if (a instanceof COSDictionary single) {
        out.add(single);
      } else if (a instanceof COSArray array) {
        for (COSBase item : array) {
          if (StructTree.resolve(item) instanceof COSDictionary dict) {
            out.add(dict);
          }
        }
      }
    }
  }

  /** Empty, or only white space and space separators (no-break spaces included, as JavaScript's trim() treats them). */
  private static boolean isBlank(String text) {
    return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
  }

  private static boolean isElement(COSDictionary dict) {
    return dict.getDictionaryObject(COSName.S) instanceof COSName && !MCR.equals(dict.getCOSName(COSName.TYPE)) && !OBJR.equals(dict.getCOSName(COSName.TYPE));
  }
}
