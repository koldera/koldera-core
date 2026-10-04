package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

class StructTreeTest {

  @Test
  void aParentTreeValueInANestedKidIsReplaced() {
    COSDictionary element = new COSDictionary();
    COSDictionary leaf = new COSDictionary();
    leaf.setItem(COSName.NUMS, new COSArray(List.of(COSInteger.get(7), new COSDictionary())));
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.KIDS, new COSArray(List.of(leaf)));

    assertTrue(StructTree.setParentTreeValue(root, 7, element));
    assertSame(element, ((COSArray) leaf.getDictionaryObject(COSName.NUMS)).getObject(1));
  }

  @Test
  void aCyclicKidsArrayFailsClosedInsteadOfOverflowingTheStack() {
    // A malformed number tree whose /Kids points back at itself: the key is never found, and the
    // walk must stop (same depth cap as NumberTreeView) rather than raise StackOverflowError.
    COSDictionary root = new COSDictionary();
    COSDictionary middle = new COSDictionary();
    root.setItem(COSName.KIDS, new COSArray(List.of(middle)));
    middle.setItem(COSName.KIDS, new COSArray(List.of(root)));

    assertFalse(StructTree.setParentTreeValue(root, 7, new COSDictionary()));
  }

  /** Root > Document > [P (MCID 0), Figure (MCID 1)] on one page with a ParentTree; returns {root, document, p, figure, slots}. */
  private static COSDictionary[] smallTree(COSArray slots) {
    COSDictionary page = new COSDictionary();
    page.setInt(COSName.STRUCT_PARENTS, 0);
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
    COSDictionary document = new COSDictionary();
    document.setName(COSName.S, "Document");
    document.setItem(COSName.P, root);
    COSDictionary p = new COSDictionary();
    p.setName(COSName.S, "P");
    p.setItem(COSName.P, document);
    p.setItem(COSName.PG, page);
    p.setItem(COSName.K, COSInteger.get(0));
    COSDictionary figure = new COSDictionary();
    figure.setName(COSName.S, "Figure");
    figure.setItem(COSName.P, document);
    figure.setItem(COSName.PG, page);
    figure.setItem(COSName.K, COSInteger.get(1));
    document.setItem(COSName.K, new COSArray(List.of(p, figure)));
    root.setItem(COSName.K, document);
    slots.add(p);
    slots.add(figure);
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, new COSArray(List.of(COSInteger.ZERO, slots)));
    root.setItem(COSName.PARENT_TREE, parentTree);
    return new COSDictionary[] {root, document, p, figure};
  }

  @Test
  void removeDropsTheChildAndNullsItsParentTreeSlots() {
    COSArray slots = new COSArray();
    COSDictionary[] tree = smallTree(slots);
    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement document =
        new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[1]);

    assertTrue(StructTree.remove(document, new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[3])));

    COSArray kids = (COSArray) tree[1].getDictionaryObject(COSName.K);
    assertSame(tree[2], kids.getObject(0));
    assertTrue(kids.size() == 1, "only the P is left");
    assertSame(tree[2], slots.getObject(0), "the P's slot is untouched");
    assertSame(org.apache.pdfbox.cos.COSNull.NULL, slots.get(1));
  }

  @Test
  void removeRefusesAChildHoldingAnObjectReference() {
    COSArray slots = new COSArray();
    COSDictionary[] tree = smallTree(slots);
    COSDictionary objr = new COSDictionary();
    objr.setItem(COSName.TYPE, COSName.getPDFName("OBJR"));
    tree[3].setItem(COSName.K, new COSArray(List.of(COSInteger.get(1), objr)));
    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement document =
        new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[1]);

    assertFalse(StructTree.remove(document, new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[3])));
    assertTrue(((COSArray) tree[1].getDictionaryObject(COSName.K)).size() == 2, "nothing changed");
    assertSame(tree[3], slots.getObject(1));
  }

  @Test
  void anEmptiedDirectKidOfTheRootStays() {
    COSArray slots = new COSArray();
    COSDictionary[] tree = smallTree(slots);
    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement document =
        new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[1]);
    assertTrue(StructTree.remove(document, new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[2])));
    assertTrue(StructTree.remove(document, new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[3])));

    assertTrue(StructTree.removeEmptyAncestors(document) == 0, "the Document hangs directly under the root");
    assertSame(tree[1], tree[0].getDictionaryObject(COSName.K));
  }

  @Test
  void anEmptiedContainerThatAParentTreeValueNamesStays() {
    // An annotation's /StructParent may name a container directly (no OBJR in its /K): removing the
    // container when its only kid, the Figure, left would leave that ParentTree entry dangling.
    COSArray slots = new COSArray();
    COSDictionary[] tree = smallTree(slots);
    COSDictionary sect = new COSDictionary();
    sect.setName(COSName.S, "Sect");
    sect.setItem(COSName.P, tree[1]);
    tree[3].setItem(COSName.P, sect);
    sect.setItem(COSName.K, new COSArray(List.of(tree[3])));
    tree[1].setItem(COSName.K, new COSArray(List.of(tree[2], sect)));
    COSDictionary parentTree = (COSDictionary) tree[0].getDictionaryObject(COSName.PARENT_TREE);
    parentTree.setItem(COSName.NUMS, new COSArray(List.of(COSInteger.ZERO, slots, COSInteger.ONE, sect)));
    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement container =
        new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(sect);
    assertTrue(StructTree.remove(container, new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[3])));

    assertTrue(StructTree.removeEmptyAncestors(container) == 0, "the ParentTree names the container");
    assertSame(sect, ((COSArray) tree[1].getDictionaryObject(COSName.K)).getObject(1));
  }

  @Test
  void anEmptiedContainerThatAPageMcidSlotNamesStays() {
    // A slot of a page's MCID array (a stale one: the MCID is not in the container's /K) names the container.
    COSArray slots = new COSArray();
    COSDictionary[] tree = smallTree(slots);
    COSDictionary sect = new COSDictionary();
    sect.setName(COSName.S, "Sect");
    sect.setItem(COSName.P, tree[1]);
    tree[3].setItem(COSName.P, sect);
    sect.setItem(COSName.K, new COSArray(List.of(tree[3])));
    tree[1].setItem(COSName.K, new COSArray(List.of(tree[2], sect)));
    slots.add(sect);
    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement container =
        new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(sect);
    assertTrue(StructTree.remove(container, new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement(tree[3])));

    assertTrue(StructTree.removeEmptyAncestors(container) == 0, "an MCID slot names the container");
    assertSame(sect, ((COSArray) tree[1].getDictionaryObject(COSName.K)).getObject(1));
  }
}
