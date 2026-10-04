package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * Chromium writes each table attribute of a cell as its own object ({@code /A [<</O /Table /RowSpan 2>> <</O /Table /ColSpan 1>>]}),
 * which makes the PAC engine miss spans. Objects of owner {@code /Table} are merged into the first, in order; elements whose
 * objects give one key two values, or whose /A holds anything else, are left unchanged. Values are moved as they are.
 */
public final class TableAttributes {

  private static final COSName TABLE = COSName.getPDFName("Table");

  private TableAttributes() {}

  /** Returns the number of elements whose Table attribute objects were merged into one. */
  public static int merge(PDDocument doc) {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return 0;
    }
    List<COSDictionary> elements = new ArrayList<>();
    collect(root.getCOSObject(), elements, new IdentityHashMap<>(), 0);
    int merged = 0;
    for (COSDictionary element : elements) {
      if (mergeOne(element)) {
        merged++;
      }
    }
    return merged;
  }

  private static boolean mergeOne(COSDictionary element) {
    if (!(StructTree.resolve(element.getItem(COSName.A)) instanceof COSArray array)) {
      return false;
    }
    List<COSDictionary> all = new ArrayList<>();
    List<COSDictionary> table = new ArrayList<>();
    for (COSBase item : array) {
      if (!(StructTree.resolve(item) instanceof COSDictionary attribute)) {
        return false; // a revision number or anything else: not ours to reshape
      }
      all.add(attribute);
      if (TABLE.equals(attribute.getCOSName(COSName.O))) {
        table.add(attribute);
      }
    }
    if (table.size() < 2) {
      return false;
    }
    COSDictionary union = new COSDictionary();
    for (COSDictionary attribute : table) {
      for (Map.Entry<COSName, COSBase> entry : attribute.entrySet()) {
        COSBase existing = union.getItem(entry.getKey());
        if (existing != null && !same(existing, entry.getValue())) {
          return false;
        }
        union.setItem(entry.getKey(), entry.getValue());
      }
    }
    COSArray result = new COSArray();
    boolean placed = false;
    for (COSDictionary attribute : all) {
      if (!TABLE.equals(attribute.getCOSName(COSName.O))) {
        result.add(attribute);
      } else if (!placed) {
        result.add(union);
        placed = true;
      }
    }
    element.setItem(COSName.A, result.size() == 1 ? result.get(0) : result);
    return true;
  }

  private static boolean same(COSBase a, COSBase b) {
    COSBase x = StructTree.resolve(a);
    COSBase y = StructTree.resolve(b);
    return x == y || (x != null && x.equals(y)) || String.valueOf(x).equals(String.valueOf(y));
  }

  private static void collect(COSDictionary node, List<COSDictionary> out, Map<COSDictionary, Boolean> seen, int depth) {
    if (depth > 256) {
      return;
    }
    for (COSBase entry : StructTree.entries(node)) {
      if (StructTree.resolve(entry) instanceof COSDictionary kid && kid.getDictionaryObject(COSName.S) instanceof COSName && seen.put(kid, Boolean.TRUE) == null) {
        out.add(kid);
        collect(kid, out, seen, depth + 1);
      }
    }
  }
}
