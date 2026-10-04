package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
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
import java.util.Map;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Placement repairs on generated containers: container decoration, orphan list items, ruby. */
class ContainersTest {

  @Test
  void theOwnBackgroundOfAListARowAndAPrintedButtonBecomesAnArtifact() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-containers.pdf")) {
      List<String> before = contentKeys(doc);
      String text = new PDFTextStripper().getText(doc);
      assertEquals("mcid", kidTypes(elements(doc, "L").get(0)).get(0), "fixture shape: the list's background is its own MCID");
      assertEquals("mcid", kidTypes(elements(doc, "TR").get(1)).get(0), "fixture shape: the shaded row's background");
      assertEquals(new WidgetlessForms.Outcome(1, 0), WidgetlessForms.apply(doc), "the button becomes a Div first");

      assertEquals(2, TableDecoration.rowsAndLists(doc), "the list's and the row's background");
      assertEquals(1, TableDecoration.containers(doc), "the printed button's box");
      assertEquals(0, TableDecoration.rowsAndLists(doc) + TableDecoration.containers(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(List.of("LI", "LI"), kidTypes(elements(after, "L").get(0)));
        assertEquals(List.of("TD", "TD"), kidTypes(elements(after, "TR").get(1)));
        assertEquals(List.of("NonStruct"), kidTypes(elements(after, "Div").get(0)));
        List<String> expected = new ArrayList<>(before);
        expected.removeAll(List.of("sp0:0", "sp0:1", "sp0:11"));
        assertEquals(expected, contentKeys(after), "only the three decoration sequences left the tree");
        assertEquals(text, new PDFTextStripper().getText(after), "no text moved");
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void consecutiveListItemsOutsideAListAreWrappedInOneList() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-containers.pdf")) {
      List<String> before = contentKeys(doc);
      COSDictionary document = (COSDictionary) kids(root(doc)).get(0);
      assertEquals(List.of("H1", "L", "Table", "P", "LI", "LI", "LI", "P"), kidTypes(document), "fixture shape");

      assertEquals(1, ListNormalizer.wrapOrphans(doc));
      assertEquals(0, ListNormalizer.wrapOrphans(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary doc2 = (COSDictionary) kids(root(after)).get(0);
        assertEquals(List.of("H1", "L", "Table", "P", "L", "P"), kidTypes(doc2));
        assertEquals(List.of("LI", "LI", "LI"), kidTypes((COSDictionary) kids(doc2).get(4)));
        assertEquals(before, contentKeys(after));
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void eachRubyBaseGoesIntoAnRbAndARubyWithTwoPairsBecomesTwoRubies() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-containers.pdf")) {
      List<String> before = contentKeys(doc);
      List<COSDictionary> rubies = elements(doc, "Ruby");
      assertEquals(List.of("NonStruct", "RT", "NonStruct", "RT"), kidTypes(rubies.get(0)), "fixture shape");
      assertEquals(List.of("NonStruct", "RT"), kidTypes(rubies.get(1)));

      assertEquals(2, RubyNormalizer.normalize(doc));
      assertEquals(0, RubyNormalizer.normalize(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary paragraph = elements(after, "P").stream().filter(p -> "ja".equals(p.getString(COSName.LANG))).findFirst().orElseThrow();
        assertEquals(List.of("NonStruct", "Span", "NonStruct", "Ruby", "NonStruct"), kidTypes(paragraph));
        COSDictionary span = (COSDictionary) kids(paragraph).get(1);
        assertEquals(List.of("Ruby", "Ruby"), kidTypes(span));
        for (COSDictionary ruby : elements(after, "Ruby")) {
          assertEquals(List.of("RB", "RP", "RT", "RP"), kidTypes(ruby), "empty RPs around the annotation (PAC RTTag-InvalidUse otherwise)");
        }
        for (COSDictionary rp : elements(after, "RP")) {
          assertTrue(kids(rp).isEmpty(), "an added RP holds nothing");
        }
        assertEquals(3, elements(after, "RB").size());
        assertEquals(before, contentKeys(after), "base and annotation text keep their order");
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aPrintedButtonInsideAParagraphBecomesASpanNotADiv() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-containers.pdf")) {
      WidgetlessForms.apply(doc);
      TableDecoration.containers(doc);
      assertEquals(1, InlineBlocks.spans(doc));
      assertEquals(0, InlineBlocks.paragraphs(doc), "the button's text stays inline, no paragraph inside the Span");
      COSDictionary paragraph = elements(doc, "P").get(0);
      assertEquals(List.of("Span"), kidTypes(paragraph));
      assertEquals(List.of("NonStruct"), kidTypes((COSDictionary) kids(paragraph).get(0)));
      assertEquals(0, elements(doc, "Div").size());
    }
  }

  @Test
  void aShadedAsideAndBlockQuoteLoseTheirBackgroundAndEmptyPrintedControlsInAParagraphBecomeSpans() throws IOException {
    // PAC: an <aside>'s background as the Aside's own marked content
    // ("Content is present in admissible locations") and a radio button or checkbox left as an empty Div in a P
    // once its box is an artifact ("Div" structure elements).
    try (PDDocument doc = GeneratedStructure.fixture("generated-controls.pdf")) {
      List<String> before = contentKeys(doc);
      String text = new PDFTextStripper().getText(doc);
      assertEquals("mcid", kidTypes(elements(doc, "Aside").get(0)).get(0), "fixture shape: the aside's background");
      assertEquals("mcid", kidTypes(elements(doc, "BlockQuote").get(0)).get(0), "fixture shape: the quote's background");
      assertEquals(new WidgetlessForms.Outcome(3, 0), WidgetlessForms.apply(doc), "two radio buttons and a checkbox");

      assertEquals(5, TableDecoration.containers(doc), "two backgrounds and three control boxes");
      assertEquals(3, InlineBlocks.spans(doc), "the emptied controls in their paragraphs");
      assertEquals(Map.of(), StructureRules.check(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(List.of("H2", "P"), kidTypes(elements(after, "Aside").get(0)));
        assertEquals(List.of("P"), kidTypes(elements(after, "BlockQuote").get(0)));
        assertEquals(0, elements(after, "Div").size());
        assertEquals(List.of("Span", "NonStruct", "Span", "NonStruct"), kidTypes(elements(after, "P").get(2)));
        assertEquals(text, new PDFTextStripper().getText(after), "no text moved");
        assertTrue(before.containsAll(contentKeys(after)));
        assertEquals(before.size() - 5, contentKeys(after).size(), "only the five decoration sequences left the tree");
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aRubyThatDoesNotReadAsPairsIsLeftAlone() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-containers.pdf")) {
      COSDictionary ruby = elements(doc, "Ruby").get(1);
      // Swap to [RT, NonStruct]: an annotation without a base.
      var k = (org.apache.pdfbox.cos.COSArray) ruby.getDictionaryObject(COSName.K);
      var first = k.get(0);
      k.set(0, k.get(1));
      k.set(1, first);
      assertEquals(1, RubyNormalizer.normalize(doc), "only the well-formed Ruby changes");
      assertEquals(List.of("RT", "NonStruct"), kidTypes(ruby));
      assertTrue(elements(doc, "RB").size() == 2);
    }
  }
}
