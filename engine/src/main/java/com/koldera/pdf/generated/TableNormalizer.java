package com.koldera.pdf.generated;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;

/**
 * Chromium tags a table as {@code Table > [Caption?, TR...]} without row groups. Leading all-{@code TH} rows become
 * {@code THead}, the rest {@code TBody} (never a guessed {@code TFoot}); a Caption stays. A table without a header row keeps
 * rows directly under the Table, and tables with row groups or other children are left alone.
 */
public final class TableNormalizer {

  private TableNormalizer() {}

  /** True when a header-row cell spans rows below the head: a THead/TBody split would cut it. */
  private static boolean spansPastHead(List<PDStructureElement> rows, int head) {
    for (int r = 0; r < head; r++) {
      for (Object kid : StructTree.kids(rows.get(r))) {
        if (kid instanceof PDStructureElement cell && TablePadding.span(cell.getCOSObject(), ROW_SPAN) > head - r) {
          return true;
        }
      }
    }
    return false;
  }

  private static final org.apache.pdfbox.cos.COSName ROW_SPAN = org.apache.pdfbox.cos.COSName.getPDFName("RowSpan");

  /** Returns the number of tables changed. */
  public static int normalize(PDDocument doc) {
    int changed = 0;
    for (PDStructureElement table : StructTree.elementsOfType(doc, "Table")) {
      List<Object> kids = StructTree.kids(table);
      List<PDStructureElement> rows = new ArrayList<>();
      boolean plain = true;
      for (int i = 0; i < kids.size() && plain; i++) {
        Object kid = kids.get(i);
        if (kid instanceof PDStructureElement e && "TR".equals(e.getStructureType())) {
          rows.add(e);
        } else {
          boolean captionAtEdge = kid instanceof PDStructureElement e && "Caption".equals(e.getStructureType()) && (i == 0 || i == kids.size() - 1);
          plain = captionAtEdge;
        }
      }
      if (!plain || rows.isEmpty()) {
        continue;
      }
      int head = 0;
      while (head < rows.size() && StructTree.allChildrenOfType(rows.get(head), "TH")) {
        head++;
      }
      if (head == 0 || head == rows.size() || spansPastHead(rows, head)) {
        continue;
      }
      StructTree.wrap(table, List.copyOf(rows.subList(0, head)), "THead");
      StructTree.wrap(table, List.copyOf(rows.subList(head, rows.size())), "TBody");
      changed++;
    }
    return changed;
  }
}
