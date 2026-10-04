package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.assertSameContent;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kidTypes;
import static com.koldera.pdf.generated.GeneratedStructure.kids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.junit.jupiter.api.Test;

class TablePaddingTest {

  @Test
  void rowsShortInTheSourceGetEmptyCellsUpToTheTableWidthAndTheRestIsUntouched() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-irregular-tables.pdf")) {
      List<String> before = contentKeys(doc);
      List<COSDictionary> tables = elements(doc, "Table");
      assertEquals(List.of(3, 3, 3), widths(tables.get(0)), "the renderer kept the empty cells: already regular");
      assertEquals(List.of(3, 3, 1, 2), widths(tables.get(1)), "fixture shape: two rows short in the HTML");
      assertEquals(List.of(3, 3, 2), widths(tables.get(2)), "fixture shape: a row span leaves the middle slot of the last row free");
      assertEquals(List.of(2, 2), widths(tables.get(3)));

      assertEquals(4, TablePadding.apply(doc));
      assertEquals(0, TablePadding.apply(doc), "a padded table is regular");

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> padded = elements(after, "Table");
        for (COSDictionary table : padded) {
          assertEquals(1, widths(table).stream().distinct().count(), "every row spans the table's width: " + widths(table));
        }
        List<COSDictionary> rows = rows(padded.get(1));
        assertEquals(List.of("TD", "TD", "TD"), kidTypes(rows.get(2)), "Bob + two empty cells");
        assertEquals(List.of("TD", "TD", "TD"), kidTypes(rows.get(3)), "Cleo, Author + one empty cell");
        COSDictionary appended = (COSDictionary) kids(rows.get(2)).get(2);
        assertTrue(kids(appended).isEmpty(), "an appended cell has no content");
        assertEquals(null, appended.getDictionaryObject(COSName.A), "and no attributes");
        assertEquals(List.of("TD", "TD"), kidTypes(rows(padded.get(2)).get(2)), "Bag + one empty cell in the slot the row span left");
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aRowSpanPastTheEndOfTheTableOrZeroIsClampedToTheRowsLeftAsHtmlLaysItOut() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-irregular-tables.pdf")) {
      COSDictionary spanTable = elements(doc, "Table").get(2);
      COSDictionary spanning = (COSDictionary) kids(rows(spanTable).get(1)).get(2);
      rowSpanAttribute(spanning).setInt(COSName.getPDFName("RowSpan"), 21);
      COSDictionary first = (COSDictionary) kids(rows(spanTable).get(0)).get(0);
      rowSpanAttribute(first).setInt(COSName.getPDFName("RowSpan"), 0);

      assertEquals(2, TablePadding.clampRowSpans(doc));
      assertEquals(0, TablePadding.clampRowSpans(doc));
      assertEquals(2, spanOf(spanning, "RowSpan"), "two rows left from the second row");
      assertEquals(3, spanOf(first, "RowSpan"), "0 = to the end of the table's rows");
    }
  }

  @Test
  void aTableWithASpanItCannotReadOrARowWithSomethingOtherThanCellsIsLeftUnchanged() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-irregular-tables.pdf")) {
      COSDictionary shortTable = elements(doc, "Table").get(1);
      COSDictionary firstCell = (COSDictionary) kids(rows(shortTable).get(0)).get(0);
      spanAttribute(firstCell).setString(COSName.getPDFName("ColSpan"), "2");
      COSDictionary spanTable = elements(doc, "Table").get(2);
      COSDictionary lastRow = rows(spanTable).get(2);
      PDStructureElement row = new PDStructureElement(lastRow);
      row.appendKid(new PDStructureElement("P", row));

      assertEquals(0, TablePadding.apply(doc));
      assertEquals(List.of(1, 2), rows(shortTable).subList(2, 4).stream().map(r -> kids(r).size()).toList());
    }
  }

  /** The TRs of a table, directly or in row groups. */
  private static List<COSDictionary> rows(COSDictionary table) {
    List<COSDictionary> out = new ArrayList<>();
    for (COSBase kid : kids(table)) {
      if (GeneratedStructure.isElement(kid)) {
        String s = GeneratedStructure.type(kid);
        if ("TR".equals(s)) {
          out.add((COSDictionary) kid);
        } else if (List.of("THead", "TBody", "TFoot").contains(s)) {
          kids((COSDictionary) kid).stream().filter(GeneratedStructure::isElement).map(k -> (COSDictionary) k).forEach(out::add);
        }
      }
    }
    return out;
  }

  /** Effective width of each row with the table model's placement (the test's own reading of RowSpan/ColSpan). */
  private static List<Integer> widths(COSDictionary table) {
    List<COSDictionary> rows = rows(table);
    Map<Integer, BitSet> grid = new HashMap<>();
    for (int r = 0; r < rows.size(); r++) {
      int col = 0;
      for (COSBase kid : kids(rows.get(r))) {
        COSDictionary cell = (COSDictionary) kid;
        BitSet own = grid.computeIfAbsent(r, k -> new BitSet());
        col = own.nextClearBit(col);
        int rs = spanOf(cell, "RowSpan");
        int cs = spanOf(cell, "ColSpan");
        for (int dr = 0; dr < rs && r + dr < rows.size(); dr++) {
          grid.computeIfAbsent(r + dr, k -> new BitSet()).set(col, col + cs);
        }
        col += cs;
      }
    }
    List<Integer> out = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
      out.add(grid.getOrDefault(r, new BitSet()).cardinality());
    }
    return out;
  }

  private static int spanOf(COSDictionary cell, String name) {
    for (COSDictionary a : attributes(cell)) {
      if (a.getDictionaryObject(COSName.getPDFName(name)) instanceof COSNumber n) {
        return n.intValue();
      }
    }
    return 1;
  }

  private static COSDictionary rowSpanAttribute(COSDictionary cell) {
    return attributes(cell).stream().filter(a -> a.containsKey(COSName.getPDFName("RowSpan"))).findFirst().orElseThrow();
  }

  private static COSDictionary spanAttribute(COSDictionary cell) {
    return attributes(cell).stream().filter(a -> a.containsKey(COSName.getPDFName("ColSpan"))).findFirst().orElseThrow();
  }

  private static List<COSDictionary> attributes(COSDictionary cell) {
    List<COSDictionary> out = new ArrayList<>();
    COSBase a = GeneratedStructure.resolve(cell.getItem(COSName.A));
    if (a instanceof COSDictionary d) {
      out.add(d);
    } else if (a instanceof COSArray array) {
      for (COSBase x : array) {
        if (GeneratedStructure.resolve(x) instanceof COSDictionary d) {
          out.add(d);
        }
      }
    }
    return out;
  }
}
