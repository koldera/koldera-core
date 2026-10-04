package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.assertSameContent;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kidTypes;
import static com.koldera.pdf.generated.GeneratedStructure.kids;
import static com.koldera.pdf.generated.GeneratedStructure.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class InlineBlocksTest {

  @Test
  void aNonStructOfInlineContentInAGroupingElementBecomesAParagraphAndNothingElseMoves() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-inline-blocks.pdf")) {
      assertParentTreeMatches(doc);
      List<String> before = contentKeys(doc);
      assertEquals(6, linksOutsideAnInlineContainer(doc), "fixture shape: six links whose nearest non-NonStruct ancestor is a grouping element");
      assertEquals(1, codeOutsideAnInlineContainer(doc), "fixture shape: <pre><code> gives NonStruct > Code in Document");

      // div alone, div with text, section > div (outer one), blockquote > div, nav, pre, text-only div
      assertEquals(7, InlineBlocks.paragraphs(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(0, linksOutsideAnInlineContainer(after));
        assertEquals(0, codeOutsideAnInlineContainer(after));
        COSDictionary document = (COSDictionary) kids(root(after)).get(0);
        assertEquals(
            List.of("H1", "P", "P", "P", "P", "BlockQuote", "P", "L", "P", "P", "P", "Figure", "P", "Aside"), kidTypes(document),
            "only /S changed; every block keeps its place");
        COSDictionary section = (COSDictionary) kids(document).get(4);
        assertEquals(List.of("NonStruct"), kidTypes(section), "the inner div of section > div stays: its paragraph is the section");
        COSDictionary quote = (COSDictionary) kids(document).get(5);
        assertEquals(List.of("P"), kidTypes(quote));
        COSDictionary item = (COSDictionary) kids((COSDictionary) kids(document).get(7)).get(0);
        assertEquals(List.of("Lbl", "NonStruct"), kidTypes(item), "a NonStruct in a list item is not in a grouping element: unchanged");
        assertEquals(List.of("mcid", "Code"), kidTypes((COSDictionary) kids(document).get(8)), "the pre block keeps its own painting and its Code");
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aBlockFigureGetsPlacementBlockAndAFigureInAParagraphStaysInline() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-inline-blocks.pdf")) {
      FigureNormalizer.normalize(doc);
      FigureBBox.apply(doc);
      InlineBlocks.paragraphs(doc);
      List<String> before = contentKeys(doc);

      assertEquals(1, InlineBlocks.figurePlacement(doc));
      assertEquals(0, InlineBlocks.figurePlacement(doc), "a second run finds the placement already set");

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<COSDictionary> figures = elements(after, "Figure");
        assertEquals(2, figures.size());
        COSDictionary layout = layout(figures.get(0));
        assertEquals("Block", layout.getNameAsString(COSName.getPDFName("Placement")));
        assertTrue(layout.containsKey(COSName.getPDFName("BBox")), "added to the existing Layout attribute, next to its BBox");
        assertTrue(layout(figures.get(1)) == null || !layout(figures.get(1)).containsKey(COSName.getPDFName("Placement")), "the signature in a paragraph stays inline");
        assertSameContent(before, after);
        assertParentTreeMatches(after);
      }
    }
  }

  @Test
  void aNonStructHoldingABlockElementIsLeftAlone() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-lists.pdf")) {
      int before = elements(doc, "NonStruct").size();
      int changed = InlineBlocks.paragraphs(doc);
      // Every NonStruct in the lists fixture is text inside a heading, label or list body: none is in a grouping element.
      assertEquals(0, changed);
      assertEquals(before, elements(doc, "NonStruct").size());
    }
  }

  private static COSDictionary layout(COSDictionary element) {
    COSBase a = GeneratedStructure.resolve(element.getItem(COSName.A));
    List<COSDictionary> all = new ArrayList<>();
    if (a instanceof COSDictionary d) {
      all.add(d);
    } else if (a instanceof COSArray array) {
      array.forEach(x -> {
        if (GeneratedStructure.resolve(x) instanceof COSDictionary d) {
          all.add(d);
        }
      });
    }
    return all.stream().filter(d -> "Layout".equals(d.getNameAsString(COSName.O))).findFirst().orElse(null);
  }

  private static int linksOutsideAnInlineContainer(PDDocument doc) {
    return outside(doc, "Link");
  }

  private static int codeOutsideAnInlineContainer(PDDocument doc) {
    return outside(doc, "Code");
  }

  /** Elements of {@code type} whose nearest ancestor other than NonStruct is a grouping element. */
  private static int outside(PDDocument doc, String type) {
    int[] count = {0};
    walk(root(doc), "Document", type, count);
    return count[0];
  }

  private static void walk(COSDictionary node, String context, String type, int[] count) {
    for (COSBase kid : kids(node)) {
      if (GeneratedStructure.isElement(kid)) {
        COSDictionary el = (COSDictionary) kid;
        String s = el.getNameAsString(COSName.S);
        if (type.equals(s) && InlineBlocks.GROUPING.contains(context)) {
          count[0]++;
        }
        walk(el, "NonStruct".equals(s) ? context : s, type, count);
      }
    }
  }
}
