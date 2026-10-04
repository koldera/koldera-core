package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * A table whose rows span different column counts is irregular (veraPDF 7.2-42/7.2-43). Each short row gets empty
 * {@code TD}s up to the column count computed from RowSpan/ColSpan with HTML placement. {@link #clampRowSpans} first clamps
 * a span past its row group, or 0, to the rows left. Fail closed: unexpected children or spans outside 1..1000 leave the
 * table unchanged. Nothing painted and no /ParentTree entry changes.
 */
public final class TablePadding {

  private static final Set<String> ROW_GROUPS = Set.of("THead", "TBody", "TFoot");
  private static final Set<String> CELLS = Set.of("TD", "TH");
  private static final COSName TABLE_OWNER = COSName.getPDFName("Table");
  private static final COSName ROW_SPAN = COSName.getPDFName("RowSpan");
  private static final COSName COL_SPAN = COSName.getPDFName("ColSpan");
  private static final int MAX_SPAN = 1000;

  private TablePadding() {}

  /** Returns the number of empty cells added. */
  public static int apply(PDDocument doc) {
    int added = 0;
    for (PDStructureElement table : StructTree.elementsOfType(doc, "Table")) {
      List<PDStructureElement> rows = rows(table);
      if (rows == null || rows.isEmpty()) {
        continue;
      }
      int[] missing = missingSlots(rows);
      if (missing == null) {
        continue;
      }
      for (int r = 0; r < rows.size(); r++) {
        PDStructureElement row = rows.get(r);
        for (int i = 0; i < missing[r]; i++) {
          row.appendKid(new PDStructureElement("TD", row));
          added++;
        }
      }
    }
    return added;
  }

  /** Clamps each row span to the rows left in its group (0 = all of them); returns how many values changed. */
  public static int clampRowSpans(PDDocument doc) {
    int changed = 0;
    for (PDStructureElement table : StructTree.elementsOfType(doc, "Table")) {
      if (rows(table) == null) {
        continue;
      }
      List<List<PDStructureElement>> groups = new ArrayList<>();
      List<PDStructureElement> direct = new ArrayList<>();
      for (Object kid : StructTree.kids(table)) {
        PDStructureElement element = (PDStructureElement) kid;
        if ("TR".equals(element.getStructureType())) {
          direct.add(element);
        } else if (ROW_GROUPS.contains(element.getStructureType())) {
          groups.add(StructTree.kids(element).stream().map(k -> (PDStructureElement) k).toList());
        }
      }
      groups.add(direct);
      for (List<PDStructureElement> group : groups) {
        for (int r = 0; r < group.size(); r++) {
          int left = group.size() - r;
          for (Object kid : StructTree.kids(group.get(r))) {
            if (kid instanceof PDStructureElement cell && CELLS.contains(cell.getStructureType())) {
              COSDictionary attribute = spanAttribute(cell.getCOSObject(), ROW_SPAN);
              if (attribute != null && attribute.getDictionaryObject(ROW_SPAN) instanceof COSNumber n && n.floatValue() == n.intValue()
                  && (n.intValue() == 0 || n.intValue() > left)) {
                attribute.setInt(ROW_SPAN, left);
                changed++;
              }
            }
          }
        }
      }
    }
    return changed;
  }

  /** The table's rows in order (directly or in row groups), or null when it holds anything else. */
  private static List<PDStructureElement> rows(PDStructureElement table) {
    List<PDStructureElement> rows = new ArrayList<>();
    for (Object kid : StructTree.kids(table)) {
      if (!(kid instanceof PDStructureElement element)) {
        return null;
      }
      String type = element.getStructureType();
      if ("TR".equals(type)) {
        rows.add(element);
      } else if (ROW_GROUPS.contains(type)) {
        for (Object groupKid : StructTree.kids(element)) {
          if (!(groupKid instanceof PDStructureElement row) || !"TR".equals(row.getStructureType())) {
            return null;
          }
          rows.add(row);
        }
      } else if (!"Caption".equals(type)) {
        return null;
      }
    }
    return rows;
  }

  /** Per row, the free slots below the table's column count; null when a row or a span is not understood. */
  private static int[] missingSlots(List<PDStructureElement> rows) {
    Map<Integer, BitSet> occupied = new HashMap<>();
    for (int r = 0; r < rows.size(); r++) {
      int column = 0;
      for (Object kid : StructTree.kids(rows.get(r))) {
        if (!(kid instanceof PDStructureElement cell) || !CELLS.contains(cell.getStructureType())) {
          return null;
        }
        int rowSpan = span(cell.getCOSObject(), ROW_SPAN);
        int colSpan = span(cell.getCOSObject(), COL_SPAN);
        if (rowSpan < 0 || colSpan < 0) {
          return null;
        }
        BitSet own = occupied.computeIfAbsent(r, k -> new BitSet());
        column = own.nextClearBit(column);
        for (int dr = 0; dr < rowSpan && r + dr < rows.size(); dr++) {
          occupied.computeIfAbsent(r + dr, k -> new BitSet()).set(column, column + colSpan);
        }
        column += colSpan;
      }
    }
    int width = 0;
    for (BitSet row : occupied.values()) {
      width = Math.max(width, row.length());
    }
    int[] missing = new int[rows.size()];
    for (int r = 0; r < rows.size(); r++) {
      BitSet row = occupied.getOrDefault(r, new BitSet());
      missing[r] = width - row.get(0, width).cardinality();
    }
    return missing;
  }

  /** The cell's Table-owner span attribute: 1 when absent, -1 when not an integer from 1 to {@link #MAX_SPAN}. */
  static int span(COSDictionary cell, COSName key) {
    COSDictionary attribute = spanAttribute(cell, key);
    if (attribute == null) {
      return 1;
    }
    if (!(attribute.getDictionaryObject(key) instanceof COSNumber n) || n.floatValue() != n.intValue() || n.intValue() < 1 || n.intValue() > MAX_SPAN) {
      return -1;
    }
    return n.intValue();
  }

  /** The Table-owner attribute object holding {@code key}, or null. */
  private static COSDictionary spanAttribute(COSDictionary cell, COSName key) {
    COSBase a = StructTree.resolve(cell.getItem(COSName.A));
    List<COSDictionary> attributes = new ArrayList<>();
    if (a instanceof COSDictionary single) {
      attributes.add(single);
    } else if (a instanceof COSArray array) {
      for (COSBase item : array) {
        if (StructTree.resolve(item) instanceof COSDictionary dict) {
          attributes.add(dict);
        }
      }
    }
    for (COSDictionary attribute : attributes) {
      if (TABLE_OWNER.equals(attribute.getCOSName(COSName.O)) && attribute.containsKey(key)) {
        return attribute;
      }
    }
    return null;
  }
}
