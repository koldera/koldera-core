package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.assertSameContent;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kidTypes;
import static com.koldera.pdf.generated.GeneratedStructure.kids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
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

class FigureNormalizerTest {

  @Test
  void aFigureWithOneImageBecomesOneFigureCarryingTheImageAltAndOtherFiguresAreUntouched() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-figures.pdf")) {
      assertParentTreeMatches(doc);
      List<String> before = contentKeys(doc);
      List<COSDictionary> figures = elements(doc, "Figure");
      assertEquals(10, figures.size(), "fixture shape (Chromium: Figure > [Figure /Alt, Caption])");
      assertEquals(List.of("Figure", "Caption"), kidTypes(figures.get(0)));
      assertNull(figures.get(0).getDictionaryObject(COSName.ALT));

      assertEquals(5, FigureNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> result = elements(after, "Figure");
        assertEquals(5, result.size());
        // <figure><img alt><figcaption>, raster and SVG: one Figure holding the image content and the caption.
        assertCollapsed(result.get(0), "Sales chart for 2026");
        assertCollapsed(result.get(1), "Green panel with a white circle");
        List<COSDictionary> divs = elements(after, "Div");
        assertEquals(3, divs.size());
        // <figure> with two images: a Div with the two image Figures (each keeping its Alt) and a P caption; no Alt invented.
        COSDictionary twoImages = divs.get(0);
        assertEquals(List.of("Figure", "Figure", "P"), kidTypes(twoImages));
        assertNull(twoImages.getDictionaryObject(COSName.ALT));
        assertSame(twoImages, result.get(2).getDictionaryObject(COSName.P));
        assertEquals("First of two images", result.get(2).getString(COSName.ALT));
        assertEquals("Second of two images", result.get(3).getString(COSName.ALT));
        assertEquals(List.of("NonStruct"), kidTypes((COSDictionary) kids(twoImages).get(2)));
        // <figure> with a table then a figcaption: Div > Table, the figcaption becomes the Table's LAST kid (order kept).
        COSDictionary tableDiv = divs.get(1);
        assertEquals(List.of("Table"), kidTypes(tableDiv));
        assertNull(tableDiv.getDictionaryObject(COSName.ALT));
        COSDictionary table = (COSDictionary) kids(tableDiv).get(0);
        assertEquals(List.of("TR", "TR", "Caption"), kidTypes(table));
        assertEquals(List.of("NonStruct"), kidTypes((COSDictionary) kids(table).get(2)));
        // <figure> with a figcaption then a table: the figcaption becomes the Table's FIRST kid (order kept).
        COSDictionary captionFirstDiv = divs.get(2);
        assertEquals(List.of("Table"), kidTypes(captionFirstDiv));
        assertNull(captionFirstDiv.getDictionaryObject(COSName.ALT));
        COSDictionary captionFirstTable = (COSDictionary) kids(captionFirstDiv).get(0);
        assertEquals(List.of("Caption", "TR", "TR"), kidTypes(captionFirstTable));
        assertEquals(List.of("NonStruct"), kidTypes((COSDictionary) kids(captionFirstTable).get(0)));
        assertEquals(4, elements(after, "Caption").size(), "the two collapsed figures' captions and the two table captions");
        // A bare <img alt> in a paragraph: unchanged.
        COSDictionary inline = result.get(4);
        assertEquals("P", ((COSDictionary) inline.getDictionaryObject(COSName.P)).getNameAsString(COSName.S));
        assertEquals("Company logo", inline.getString(COSName.ALT));
        assertEquals(List.of("mcid"), kidTypes(inline));

        // Reading order is identical, including inside both table figures.
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aNormalizedDocumentIsLeftAlone() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-figures.pdf")) {
      FigureNormalizer.normalize(doc);
      List<String> once = contentKeys(doc);
      assertEquals(0, FigureNormalizer.normalize(doc));
      assertSameContent(once, doc);
    }
  }

  @Test
  void anInnerFigureHoldingAnMcidAndALinkMovesBothWithTheirParentTreeEntries() throws IOException {
    try (PDDocument doc = synthetic(true, true, false)) {
      List<String> before = contentKeys(doc);
      assertParentTreeMatches(doc);

      assertEquals(1, FigureNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> figures = elements(after, "Figure");
        assertEquals(1, figures.size());
        COSDictionary figure = figures.get(0);
        assertEquals("A diagram", figure.getString(COSName.ALT));
        assertEquals(List.of("mcid", "objr", "Caption"), kidTypes(figure));
        // The outer element takes the page of the integer MCID it now holds.
        assertSame(after.getPage(0).getCOSObject(), figure.getDictionaryObject(COSName.PG));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void anOuterFigureWithAnAltIsLeftUntouched() throws IOException {
    try (PDDocument doc = synthetic(true, true, true)) {
      assertEquals(0, FigureNormalizer.normalize(doc));
      assertEquals(2, elements(doc, "Figure").size());
      assertEquals(List.of("Figure", "Caption"), kidTypes(elements(doc, "Figure").get(0)));
    }
  }

  @Test
  void anOuterFigureWhoseInnerHasNoAltBecomesADivWithAPCaptionAndNoAltIsInvented() throws IOException {
    try (PDDocument doc = synthetic(false, true, false)) {
      List<String> before = contentKeys(doc);

      assertEquals(1, FigureNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary div = elements(after, "Div").get(0);
        assertEquals(List.of("Figure", "P"), kidTypes(div));
        assertNull(div.getDictionaryObject(COSName.ALT));
        COSDictionary inner = elements(after, "Figure").get(0);
        assertNull(inner.getDictionaryObject(COSName.ALT), "the inner Figure is not given an Alt");
        assertEquals(List.of("mcid", "objr"), kidTypes(inner));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void anInnerFigureWithAnIdIsNotCollapsedAndTheOuterBecomesADiv() throws IOException {
    try (PDDocument doc = synthetic(true, true, false)) {
      COSDictionary inner = elements(doc, "Figure").get(1);
      inner.setString(COSName.ID, "fig-1");
      List<String> before = contentKeys(doc);

      assertEquals(1, FigureNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary div = elements(after, "Div").get(0);
        assertEquals(List.of("Figure", "P"), kidTypes(div));
        assertNull(div.getDictionaryObject(COSName.ALT));
        COSDictionary kept = elements(after, "Figure").get(0);
        assertEquals("A diagram", kept.getString(COSName.ALT));
        assertEquals("fig-1", kept.getString(COSName.ID));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aTableFigureWhoseTableHasItsOwnCaptionKeepsItAndTheFigureCaptionBecomesP() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDStructureElement document = element("Document", root, null);
      PDStructureElement figure = element("Figure", document, null);
      PDStructureElement table = element("Table", figure, null);
      element("Caption", table, null);
      element("TR", table, null);
      element("Caption", figure, null);

      assertEquals(1, FigureNormalizer.normalize(doc));

      COSDictionary div = figure.getCOSObject();
      assertEquals("Div", div.getNameAsString(COSName.S));
      assertEquals(List.of("Table", "P"), kidTypes(div));
      assertEquals(List.of("Caption", "TR"), kidTypes(table.getCOSObject()));
    }
  }

  @Test
  void aMissingParentTreeEntryLeavesTheFigureUnchanged() throws IOException {
    try (PDDocument doc = synthetic(true, false, false)) {
      COSDictionary outer = elements(doc, "Figure").get(0);
      List<String> kidsBefore = kidTypes(outer);
      COSDictionary inner = (COSDictionary) kids(outer).get(0);
      List<String> innerBefore = kidTypes(inner);

      assertEquals(0, FigureNormalizer.normalize(doc));

      assertEquals(kidsBefore, kidTypes(outer));
      assertEquals(innerBefore, kidTypes(inner));
      assertNull(outer.getDictionaryObject(COSName.ALT));
      assertNull(outer.getDictionaryObject(COSName.PG));
      assertSame(outer, inner.getDictionaryObject(COSName.P));
      assertEquals("Figure", outer.getNameAsString(COSName.S), "a figure that cannot be collapsed safely is not retagged either");
    }
  }

  private static void assertCollapsed(COSDictionary figure, String alt) {
    assertEquals(alt, figure.getString(COSName.ALT));
    assertEquals(List.of("mcid", "Caption"), kidTypes(figure));
    assertFalse(kids(figure).stream().anyMatch(k -> k instanceof COSDictionary d && "Figure".equals(d.getNameAsString(COSName.S))));
    assertEquals(List.of("NonStruct"), kidTypes((COSDictionary) kids(figure).get(1)), "the caption is kept as it was");
  }

  /**
   * Document > Figure(no Alt) > [Figure(Alt? "A diagram", Pg) > [mcid0, OBJR link], Caption > NonStruct mcid1].
   * Without {@code withParentTree} the /ParentTree has no entry for the page's MCIDs.
   */
  private static PDDocument synthetic(boolean innerAlt, boolean withParentTree, boolean outerAlt) {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage();
    doc.addPage(page);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    PDStructureElement document = element("Document", root, null);
    PDStructureElement outer = element("Figure", document, null);
    if (outerAlt) {
      outer.setAlternateDescription("Outer");
    }
    PDStructureElement inner = element("Figure", outer, page);
    if (innerAlt) {
      inner.setAlternateDescription("A diagram");
    }
    inner.getCOSObject().setItem(COSName.K, COSInteger.get(0));
    PDAnnotationLink link = new PDAnnotationLink();
    link.setStructParent(1);
    page.getCOSObject().setItem(COSName.ANNOTS, new COSArray(List.of(link)));
    PDObjectReference objr = new PDObjectReference();
    objr.setReferencedObject(link);
    inner.appendKid(objr);
    PDStructureElement caption = element("Caption", outer, null);
    PDStructureElement text = element("NonStruct", caption, page);
    text.getCOSObject().setItem(COSName.K, COSInteger.get(1));

    COSArray nums = new COSArray();
    if (withParentTree) {
      nums.add(COSInteger.get(0));
      nums.add(new COSArray(List.of(inner, text)));
    }
    nums.add(COSInteger.get(1));
    nums.add(inner);
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
}
