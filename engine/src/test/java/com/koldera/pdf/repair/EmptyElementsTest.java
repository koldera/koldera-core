package com.koldera.pdf.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Test;

class EmptyElementsTest {

  @Test
  void emptyElementsGoButCellsAndIdsStay() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Text."))))) {
      PDStructureElement document = document(doc);
      document.appendKid(new PDStructureElement("Sect", document));
      PDStructureElement div = new PDStructureElement("Div", document);
      div.appendKid(new PDStructureElement("Span", div));
      document.appendKid(div);
      PDStructureElement table = new PDStructureElement("Table", document);
      PDStructureElement row = new PDStructureElement("TR", table);
      row.appendKid(new PDStructureElement("TD", row));
      table.appendKid(row);
      document.appendKid(table);
      PDStructureElement note = new PDStructureElement("Note", document);
      note.getCOSObject().setString(COSName.ID, "n1");
      document.appendKid(note);

      assertEquals(3, EmptyElements.remove(doc));

      assertEquals("Document(P, Table(TR(TD)), Note)", TaggedStructureRepairRuleTest.shape(document));
    }
  }

  @Test
  void aCyclicTreeIsLeftAlone() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Text."))))) {
      PDStructureElement document = document(doc);
      PDStructureElement div = new PDStructureElement("Div", document);
      PDStructureElement sect = new PDStructureElement("Sect", div);
      div.appendKid(sect);
      COSArray back = new COSArray();
      back.add(div.getCOSObject());
      sect.getCOSObject().setItem(COSName.K, back);
      document.appendKid(div);
      document.appendKid(new PDStructureElement("Span", document));

      assertEquals(1, EmptyElements.remove(doc), "the empty Span beside the cycle goes; the cycle is left alone");
      assertEquals(List.of("P", "Div"), document.getKids().stream().map(k -> ((PDStructureElement) k).getStructureType()).toList());
    }
  }

  @Test
  void anEmptyDirectKidOfTheRootStays() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Text."))))) {
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      root.appendKid(new PDStructureElement("Sect", root));

      assertEquals(0, EmptyElements.remove(doc));
      assertEquals(2, root.getKids().size());
    }
  }

  @Test
  void anEmptyElementTheParentTreeReferencesStays() throws Exception {
    // An annotation's /StructParent may name an element without an OBJR in its /K: removing it would leave that slot dangling.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "Text."))))) {
      PDStructureElement document = document(doc);
      PDStructureElement link = new PDStructureElement("Link", document);
      document.appendKid(link);
      document.appendKid(new PDStructureElement("Span", document));
      COSDictionary parentTree = (COSDictionary) doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.PARENT_TREE);
      COSArray nums = (COSArray) parentTree.getDictionaryObject(COSName.NUMS);
      nums.add(COSInteger.get(1000));
      nums.add(link.getCOSObject());

      assertEquals(1, EmptyElements.remove(doc), "only the unreferenced Span");
      assertEquals("Document(P, Link)", TaggedStructureRepairRuleTest.shape(document));
    }
  }

  private static PDStructureElement document(PDDocument doc) {
    List<Object> kids = doc.getDocumentCatalog().getStructureTreeRoot().getKids();
    return (PDStructureElement) kids.get(0);
  }
}
