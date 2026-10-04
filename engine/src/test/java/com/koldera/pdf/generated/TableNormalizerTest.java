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
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Test;

class TableNormalizerTest {

  @Test
  void leadingAllHeaderRowsGoIntoTHeadAndTheRestIntoTBodyWithCellsAndParentTreeIntact() throws IOException {
    // A table without a header row keeps its rows directly under Table: the PAC engine warns about a TBody
    // in a table with no THead or TFoot ("TBody" structure elements).
    try (PDDocument doc = GeneratedStructure.fixture("generated-tables.pdf")) {
      assertParentTreeMatches(doc);
      List<String> before = contentKeys(doc);
      List<List<String>> cellsBefore = cellTypes(doc);
      assertEquals(List.of("Caption", "TR", "TR", "TR", "TR"), kidTypes(elements(doc, "Table").get(0)), "fixture shape (Chromium: no THead/TBody)");

      assertEquals(2, TableNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> tables = elements(after, "Table");
        // <thead> with two header rows (rowspan/colspan): both rows are the head.
        assertEquals(List.of("Caption", "THead", "TBody"), kidTypes(tables.get(0)));
        assertEquals(List.of("TR", "TR"), kidTypes((COSDictionary) kids(tables.get(0)).get(1)));
        assertEquals(List.of("TR", "TR"), kidTypes((COSDictionary) kids(tables.get(0)).get(2)));
        // No <thead>, first row all <th scope=col>: that row is the head.
        assertEquals(List.of("Caption", "THead", "TBody"), kidTypes(tables.get(1)));
        assertEquals(List.of("TR"), kidTypes((COSDictionary) kids(tables.get(1)).get(1)));
        assertEquals(List.of("TR", "TR"), kidTypes((COSDictionary) kids(tables.get(1)).get(2)));
        // No header row, and a table whose rows start with a row header: rows stay directly in the Table.
        assertEquals(List.of("TR", "TR"), kidTypes(tables.get(2)));
        assertEquals(List.of("TR", "TR"), kidTypes(tables.get(3)));
        // The collapsed-border table holds its own border MCID: TableNormalizer alone leaves it as it is
        // (TableDecoration, run before it by the rule, removes that MCID first; see TableDecorationTest).
        assertEquals(List.of("mcid", "Caption", "TR", "TR", "TR"), kidTypes(tables.get(4)));
        for (COSDictionary table : tables.subList(0, 2)) {
          List<String> types = kidTypes(table);
          assertTrue(List.of("Caption", "THead", "TBody").containsAll(types) && types.contains("TBody"), "Table kids " + types);
        }
        assertEquals(cellsBefore, cellTypes(after), "cells unchanged, in order");
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aNormalizedTableIsLeftAlone() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-tables.pdf")) {
      TableNormalizer.normalize(doc);
      List<String> once = contentKeys(doc);
      assertEquals(0, TableNormalizer.normalize(doc));
      assertSameContent(once, doc);
    }
  }

  @Test
  void captionLastStaysLastAndATableOfOnlyHeaderRowsIsLeftAsItIs() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDStructureElement document = element("Document", root, null);
      List<PDStructureElement> parents = new ArrayList<>();
      // Table 1: TR(TH) TR(TD) Caption.
      PDStructureElement t1 = element("Table", document, null);
      parents.add(cell(row(t1), "TH", page, parents.size()));
      parents.add(cell(row(t1), "TD", page, parents.size()));
      PDStructureElement caption = element("Caption", t1, page);
      mcid(caption, parents.size());
      parents.add(caption);
      // Table 2: two rows, every cell TH.
      PDStructureElement t2 = element("Table", document, null);
      parents.add(cell(row(t2), "TH", page, parents.size()));
      parents.add(cell(row(t2), "TH", page, parents.size()));
      // Table 3: already has a TBody.
      PDStructureElement t3 = element("Table", document, null);
      PDStructureElement body = element("TBody", t3, null);
      parents.add(cell(row(body), "TD", page, parents.size()));
      parentTree(root, parents);
      List<String> before = contentKeys(doc);

      assertEquals(1, TableNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> tables = elements(after, "Table");
        assertEquals(List.of("THead", "TBody", "Caption"), kidTypes(tables.get(0)));
        assertEquals(List.of("TR", "TR"), kidTypes(tables.get(1)), "no header row can be told from the body: no row groups");
        assertEquals(List.of("TBody"), kidTypes(tables.get(2)));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aHeaderRowWhoseCellSpansIntoTheBodyIsNotSplitFromIt() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-tables.pdf")) {
      COSDictionary table = elements(doc, "Table").get(1);
      COSDictionary headerCell = (COSDictionary) kids((COSDictionary) kids(table).get(1)).get(0);
      COSBase a = GeneratedStructure.resolve(headerCell.getItem(COSName.A));
      List<COSDictionary> attributes = new ArrayList<>();
      if (a instanceof COSArray array) {
        array.forEach(x -> attributes.add((COSDictionary) GeneratedStructure.resolve(x)));
      } else {
        attributes.add((COSDictionary) a);
      }
      attributes.stream().filter(d -> d.containsKey(COSName.getPDFName("RowSpan"))).findFirst().orElseThrow().setInt(COSName.getPDFName("RowSpan"), 2);

      TableNormalizer.normalize(doc);

      assertEquals(List.of("Caption", "TR", "TR", "TR"), kidTypes(table), "a THead/TBody split would cut the spanning header cell");
    }
  }

  private static List<List<String>> cellTypes(PDDocument doc) {
    return elements(doc, "TR").stream().map(GeneratedStructure::kidTypes).toList();
  }

  private static PDStructureElement row(PDStructureElement parent) {
    return element("TR", parent, null);
  }

  private static PDStructureElement cell(PDStructureElement row, String type, PDPage page, int mcid) {
    PDStructureElement cell = element(type, row, page);
    mcid(cell, mcid);
    return cell;
  }

  private static void parentTree(PDStructureTreeRoot root, List<PDStructureElement> parents) {
    COSArray nums = new COSArray();
    nums.add(COSInteger.get(0));
    nums.add(new COSArray(parents));
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, nums);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.setParentTreeNextKey(1);
  }

  private static PDStructureElement element(String type, PDStructureNode parent, PDPage page) {
    PDStructureElement el = new PDStructureElement(type, parent);
    if (page != null) {
      el.setPage(page);
    }
    parent.appendKid(el);
    return el;
  }

  private static void mcid(PDStructureElement el, int mcid) {
    COSDictionary d = el.getCOSObject();
    COSBase k = d.getDictionaryObject(COSName.K);
    COSArray array = k instanceof COSArray a ? a : new COSArray();
    if (k != null && !(k instanceof COSArray)) {
      array.add(k);
    }
    array.add(COSInteger.get(mcid));
    d.setItem(COSName.K, array);
  }
}
