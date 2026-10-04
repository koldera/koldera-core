package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.assertSameContent;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kidTypes;
import static com.koldera.pdf.generated.GeneratedStructure.kids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

class ListNormalizerTest {

  @Test
  void everyListItemOfTheGeneratedPdfGetsOneLBodyAfterItsLabelWithContentAndParentTreeIntact() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-lists.pdf")) {
      assertParentTreeMatches(doc);
      List<String> before = contentKeys(doc);
      int items = elements(doc, "LI").size();
      assertEquals(11, items, "fixture shape (Chromium: LI > [Lbl, content], no LBody)");

      assertEquals(items, ListNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        for (COSDictionary li : elements(after, "LI")) {
          List<String> types = kidTypes(li);
          assertTrue(types.equals(List.of("Lbl", "LBody")) || types.equals(List.of("LBody")), "LI kids " + types);
        }
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
        // Every nested list now sits inside an LBody.
        List<COSDictionary> lists = elements(after, "L");
        assertEquals(6, lists.size());
        for (COSDictionary list : lists) {
          COSDictionary parent = (COSDictionary) list.getDictionaryObject(COSName.P);
          String parentType = parent.getNameAsString(COSName.S);
          assertTrue(parentType.equals("NonStruct") || parentType.equals("LBody"), "L parent " + parentType);
        }
        // The item whose only text is a nested list: the marker Chromium drew stays the Lbl, the list goes into LBody.
        COSDictionary nestedOnly = elements(after, "LI").get(6);
        assertEquals(List.of("Lbl", "LBody"), kidTypes(nestedOnly));
        assertEquals(List.of("L"), kidTypes((COSDictionary) kids(nestedOnly).get(1)));
        // The list without markers (list-style:none) has no Lbl, and none is created.
        COSDictionary unmarked = elements(after, "LI").get(10);
        assertEquals(List.of("LBody"), kidTypes(unmarked));
        assertEquals(List.of("NonStruct"), kidTypes((COSDictionary) kids(unmarked).get(0)));
      }
    }
  }

  @Test
  void aNormalizedListIsLeftAlone() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-lists.pdf")) {
      ListNormalizer.normalize(doc);
      List<String> once = contentKeys(doc);
      assertEquals(0, ListNormalizer.normalize(doc));
      assertSameContent(once, doc);
    }
  }

  @Test
  void anItemWhoseOnlyContentIsANestedListGetsLBodyAroundItAndNoLabel() throws IOException {
    try (PDDocument doc = syntheticList()) {
      List<String> before = contentKeys(doc);
      assertParentTreeMatches(doc);

      assertEquals(3, ListNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> items = elements(after, "LI");
        COSDictionary nestedOnly = items.get(0);
        assertEquals(List.of("LBody"), kidTypes(nestedOnly), "no empty Lbl is created");
        COSDictionary body = (COSDictionary) kids(nestedOnly).get(0);
        assertEquals(List.of("L"), kidTypes(body));
        assertSame(body, ((COSDictionary) kids(body).get(0)).getDictionaryObject(COSName.P));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void contentHeldDirectlyByTheItemMovesWithItsParentTreeEntries() throws IOException {
    try (PDDocument doc = syntheticList()) {
      List<String> before = contentKeys(doc);

      ListNormalizer.normalize(doc);

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary mixed = elements(after, "LI").get(2);
        assertEquals(List.of("Lbl", "LBody"), kidTypes(mixed));
        COSDictionary body = (COSDictionary) kids(mixed).get(1);
        assertEquals(List.of("mcid", "objr", "NonStruct"), kidTypes(body));
        // The new element carries the page of the MCIDs it now holds.
        assertSame(mixed.getDictionaryObject(COSName.PG), body.getDictionaryObject(COSName.PG));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
      }
    }
  }

  /**
   * Document > L > [LI(L > LI(Lbl mcid0, NonStruct mcid1)), LI(Lbl mcid2, mcid3, OBJR link, NonStruct mcid4)];
   * the second top-level item holds an MCID and a link directly, like no Chromium output but valid PDF.
   */
  private static PDDocument syntheticList() {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage();
    doc.addPage(page);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    PDStructureElement document = element("Document", root, null);
    PDStructureElement list = element("L", document, null);
    PDStructureElement nestedOnly = element("LI", list, null);
    PDStructureElement inner = element("L", nestedOnly, null);
    PDStructureElement innerItem = element("LI", inner, null);
    PDStructureElement lbl0 = element("Lbl", innerItem, page);
    mcid(lbl0, 0);
    PDStructureElement text1 = element("NonStruct", innerItem, page);
    mcid(text1, 1);
    PDStructureElement mixed = element("LI", list, page);
    PDStructureElement lbl2 = element("Lbl", mixed, page);
    mcid(lbl2, 2);
    mcid(mixed, 3);
    PDAnnotationLink link = new PDAnnotationLink();
    link.setStructParent(1);
    page.getCOSObject().setItem(COSName.ANNOTS, new COSArray(List.of(link)));
    PDObjectReference objr = new PDObjectReference();
    objr.setReferencedObject(link);
    mixed.appendKid(objr);
    PDStructureElement text4 = element("NonStruct", mixed, page);
    mcid(text4, 4);

    COSArray pageParents = new COSArray(List.of(lbl0, text1, lbl2, mixed, text4));
    COSArray nums = new COSArray();
    nums.add(COSInteger.get(0));
    nums.add(pageParents);
    nums.add(COSInteger.get(1));
    nums.add(mixed);
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, nums);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.setParentTreeNextKey(2);
    return doc;
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
