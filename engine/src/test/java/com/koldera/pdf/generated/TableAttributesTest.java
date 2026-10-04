package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.assertSameContent;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class TableAttributesTest {

  @Test
  void theTableAttributeObjectsOfEachCellBecomeOneWithEveryKeyAndValueKept() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-irregular-tables.pdf")) {
      List<COSDictionary> cells = new ArrayList<>(elements(doc, "TD"));
      cells.addAll(elements(doc, "TH"));
      List<Map<String, String>> before = cells.stream().map(TableAttributesTest::tableKeys).toList();
      assertTrue(cells.stream().allMatch(c -> tableObjects(c).size() == 3), "fixture shape: Chromium writes three Table objects per cell");
      List<String> content = contentKeys(doc);

      assertEquals(cells.size(), TableAttributes.merge(doc));
      assertEquals(0, TableAttributes.merge(doc), "merged objects stay merged");

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> merged = new ArrayList<>(elements(after, "TD"));
        merged.addAll(elements(after, "TH"));
        assertEquals(before, merged.stream().map(TableAttributesTest::tableKeys).toList(), "the same keys and values, cell by cell");
        assertTrue(merged.stream().allMatch(c -> tableObjects(c).size() == 1));
        assertSameContent(content, after);
        assertParentTreeMatches(after);
      }
    }
  }

  @Test
  void otherOwnersKeepTheirPlaceAndConflictsOrRevisionNumbersLeaveTheElementAlone() {
    COSDictionary layout = attribute("Layout", "Placement", COSName.getPDFName("Block"));
    COSDictionary first = attribute("Table", "RowSpan", COSInteger.get(2));
    COSDictionary second = attribute("Table", "ColSpan", COSInteger.get(3));
    COSDictionary cell = element(layout, first, second);
    assertTrue(merge(cell));
    COSArray a = (COSArray) cell.getDictionaryObject(COSName.A);
    assertEquals(2, a.size());
    assertTrue(a.getObject(0) == layout, "Layout keeps its place");
    assertEquals(Map.of("O", "COSName{Table}", "RowSpan", "COSInt{2}", "ColSpan", "COSInt{3}"), keys((COSDictionary) a.getObject(1)));

    COSDictionary conflicting = element(attribute("Table", "RowSpan", COSInteger.get(2)), attribute("Table", "RowSpan", COSInteger.get(3)));
    COSBase original = conflicting.getItem(COSName.A);
    assertTrue(!merge(conflicting) && conflicting.getItem(COSName.A) == original, "one key, two values: unchanged");

    COSArray withRevision = new COSArray();
    withRevision.add(attribute("Table", "RowSpan", COSInteger.get(2)));
    withRevision.add(COSInteger.ONE);
    withRevision.add(attribute("Table", "ColSpan", COSInteger.get(2)));
    COSDictionary revised = new COSDictionary();
    revised.setItem(COSName.S, COSName.getPDFName("TD"));
    revised.setItem(COSName.A, withRevision);
    assertTrue(!merge(revised) && revised.getItem(COSName.A) == withRevision, "revision numbers: unchanged");
  }

  private static boolean merge(COSDictionary cell) {
    try (PDDocument doc = new PDDocument()) {
      org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot root =
          new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot();
      root.getCOSObject().setItem(COSName.K, cell);
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      return TableAttributes.merge(doc) == 1;
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static COSDictionary element(COSDictionary... attributes) {
    COSDictionary cell = new COSDictionary();
    cell.setItem(COSName.S, COSName.getPDFName("TD"));
    COSArray a = new COSArray();
    for (COSDictionary attribute : attributes) {
      a.add(attribute);
    }
    cell.setItem(COSName.A, a);
    return cell;
  }

  private static COSDictionary attribute(String owner, String key, COSBase value) {
    COSDictionary d = new COSDictionary();
    d.setItem(COSName.O, COSName.getPDFName(owner));
    d.setItem(COSName.getPDFName(key), value);
    return d;
  }

  private static List<COSDictionary> tableObjects(COSDictionary cell) {
    List<COSDictionary> out = new ArrayList<>();
    COSBase a = GeneratedStructure.resolve(cell.getItem(COSName.A));
    List<COSBase> items = new ArrayList<>();
    if (a instanceof COSArray array) {
      array.forEach(items::add);
    } else if (a != null) {
      items.add(a);
    }
    for (COSBase item : items) {
      if (GeneratedStructure.resolve(item) instanceof COSDictionary d && "Table".equals(d.getNameAsString(COSName.O))) {
        out.add(d);
      }
    }
    return out;
  }

  private static Map<String, String> tableKeys(COSDictionary cell) {
    Map<String, String> out = new TreeMap<>();
    for (COSDictionary d : tableObjects(cell)) {
      out.putAll(keys(d));
    }
    return out;
  }

  private static Map<String, String> keys(COSDictionary d) {
    Map<String, String> out = new TreeMap<>();
    for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
      out.put(e.getKey().getName(), String.valueOf(GeneratedStructure.resolve(e.getValue())));
    }
    return out;
  }
}
