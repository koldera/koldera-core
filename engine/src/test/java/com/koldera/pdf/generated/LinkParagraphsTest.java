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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Test;

/**
 * generated-link-runs.html: a skip link first in the body, a link between a heading and an aside, a
 * loose text run with a link between paragraphs, and two table captions with links. Chromium puts all
 * of these links directly in Document or Caption (PAC "LinkTag-PossibleInappropriateUseParagraph").
 */
class LinkParagraphsTest {

  @Test
  void eachRunOfInlineContentAroundALooseLinkBecomesOneParagraphAndNothingElseMoves() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-link-runs.pdf")) {
      assertParentTreeMatches(doc);
      List<String> before = contentKeys(doc);
      assertEquals(6, StructureRules.check(doc).get(StructureRules.LINK_PLACEMENT), "fixture shape");
      COSDictionary document = (COSDictionary) kids(root(doc)).get(0);
      assertEquals(
          List.of("Link", "H1", "Link", "Aside", "H2", "L", "P", "NonStruct", "Link", "NonStruct", "Em", "NonStruct", "P", "Table", "Table", "P", "L"),
          kidTypes(document), "fixture shape: Chromium flattens the divs");

      assertEquals(5, LinkParagraphs.apply(doc), "skip link, link after the heading, the loose run, two captions");
      assertEquals(0, LinkParagraphs.apply(doc), "idempotent");

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary doc2 = (COSDictionary) kids(root(after)).get(0);
        assertEquals(List.of("P", "H1", "P", "Aside", "H2", "L", "P", "P", "P", "Table", "Table", "P", "L"), kidTypes(doc2));
        assertEquals(List.of("Link"), kidTypes((COSDictionary) kids(doc2).get(0)), "the skip link alone");
        assertEquals(List.of("NonStruct", "Link", "NonStruct", "Em", "NonStruct"), kidTypes((COSDictionary) kids(doc2).get(7)),
            "the whole loose run, in order, in one P");
        List<COSDictionary> captions = elements(after, "Caption");
        assertEquals(List.of("P"), kidTypes(captions.get(0)));
        assertEquals(List.of("NonStruct", "Link", "NonStruct", "Link"), kidTypes((COSDictionary) kids(captions.get(0)).get(0)));
        assertEquals(List.of("P"), kidTypes(captions.get(1)));
        assertEquals(null, StructureRules.check(after).get(StructureRules.LINK_PLACEMENT), "no loose link left (the raw list item is another step's)");
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void theFullGeneratedNormalizationLeavesNoLooseLink() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-link-runs.pdf")) {
      List<String> before = contentKeys(doc);
      GeneratedStructureRule rule = new GeneratedStructureRule();
      var action = rule.apply(doc, rule.inspect(doc, GeneratedStructureRuleTest.request(
          com.koldera.pdf.contracts.WorkerRequest.Mode.FIX, com.koldera.pdf.contracts.WorkerRequest.Origin.HTML_GENERATED)));
      org.junit.jupiter.api.Assertions.assertTrue(action.reason().contains(", link paragraphs 5"), action.reason());
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(Map.of(), StructureRules.check(after));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
      }
    }
  }

  @Test
  void linksAlreadyInParagraphsListItemsAndInlineContainersAreLeftAlone() throws IOException {
    // Not the production order (LinkParagraphs runs before InlineBlocks.paragraphs): paragraphs runs first here
    // only to produce a tree whose every link already sits in a P, an LI or an inline container.
    try (PDDocument doc = GeneratedStructure.fixture("generated-inline-blocks.pdf")) {
      InlineBlocks.paragraphs(doc);
      assertEquals(0, LinkParagraphs.apply(doc), "links already in paragraphs, list items and inline containers stay");
    }
  }

  @Test
  void aNonStructOfInlineContentInsideACaptionRunEndsUpInsideTheOneNewParagraphNotInASecondOne() throws IOException {
    // Caption > [NonStruct, Link, NonStruct[NonStruct, Link]]: the whole caption is one run; the nested
    // NonStruct is part of it and must not become a P inside the new P.
    try (PDDocument doc = GeneratedStructure.fixture("generated-link-runs.pdf")) {
      PDStructureElement caption = captions(doc).get(0);
      List<Object> kids = caption.getKids();
      assertEquals(4, kids.size(), "fixture shape: Caption > NonStruct Link NonStruct Link");
      StructTree.wrap(caption, kids.subList(2, 4), "NonStruct");
      List<String> before = contentKeys(doc);

      LinkParagraphs.apply(doc);

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary cap = elements(after, "Caption").get(0);
        assertEquals(List.of("P"), kidTypes(cap));
        assertEquals(List.of("NonStruct", "Link", "NonStruct"), kidTypes((COSDictionary) kids(cap).get(0)));
        assertNoParagraphInAParagraph(after);
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  @Test
  void aNonStructCaptionHolderThatIsOneRunIsRetaggedOnceAndItsNestedRunIsLeftInside() throws IOException {
    // Example: Caption > NonStruct[NonStruct, Link, NonStruct[NonStruct, Link]].
    try (PDDocument doc = GeneratedStructure.fixture("generated-link-runs.pdf")) {
      PDStructureElement caption = captions(doc).get(0);
      PDStructureElement holder = StructTree.wrap(caption, caption.getKids(), "NonStruct");
      List<Object> kids = holder.getKids();
      StructTree.wrap(holder, kids.subList(2, 4), "NonStruct");
      List<String> before = contentKeys(doc);

      LinkParagraphs.apply(doc);

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        COSDictionary cap = elements(after, "Caption").get(0);
        assertEquals(List.of("P"), kidTypes(cap), "the holder itself is the paragraph");
        assertEquals(List.of("NonStruct", "Link", "NonStruct"), kidTypes((COSDictionary) kids(cap).get(0)));
        assertNoParagraphInAParagraph(after);
        assertEquals(null, StructureRules.check(after).get(StructureRules.LINK_PLACEMENT));
        assertSameContent(before, after);
        assertParentTreeMatches(after);
        assertParentLinks(after);
      }
    }
  }

  private static List<PDStructureElement> captions(PDDocument doc) {
    return StructTree.elementsOfType(doc, "Caption");
  }

  private static void assertNoParagraphInAParagraph(PDDocument doc) {
    for (COSDictionary p : elements(doc, "P")) {
      assertEquals(0, countBelow(p, "P", 0), "a P below a P");
    }
  }

  private static int countBelow(COSDictionary node, String type, int depth) {
    int n = 0;
    for (org.apache.pdfbox.cos.COSBase kid : kids(node)) {
      if (GeneratedStructure.isElement(kid) && depth < 64) {
        n += (type.equals(GeneratedStructure.type(kid)) ? 1 : 0) + countBelow((COSDictionary) kid, type, depth + 1);
      }
    }
    return n;
  }

  @Test
  void aNonStructOfInlineContentInACaptionIsRetaggedAParagraphAndOneInAGroupingElementIsLeftToInlineBlocks() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      PDStructureElement table = child(document, "Table");
      PDStructureElement caption = child(table, "Caption");
      PDStructureElement holder = child(caption, "NonStruct"); // Example: Caption > NonStruct > [NonStruct, Link, NonStruct]
      child(holder, "NonStruct").appendKid(new org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent(COSName.P, mcid(0)));
      child(child(holder, "Link"), "NonStruct").appendKid(new org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent(COSName.P, mcid(1)));
      PDStructureElement div = child(document, "NonStruct"); // <div><a>…</a></div> in Document
      child(div, "Link").appendKid(new org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent(COSName.P, mcid(2)));

      assertEquals(1, LinkParagraphs.apply(doc));
      assertEquals("P", holder.getStructureType(), "only /S changes");
      assertEquals("NonStruct", div.getStructureType(), "InlineBlocks.paragraphs retags this one, as before");
      assertEquals(1, InlineBlocks.paragraphs(doc));
      assertEquals(Map.of(), StructureRules.check(doc));
    }
  }

  private static PDStructureElement child(PDStructureElement parent, String type) {
    PDStructureElement kid = new PDStructureElement(type, parent);
    parent.appendKid(kid);
    return kid;
  }

  private static org.apache.pdfbox.cos.COSDictionary mcid(int mcid) {
    org.apache.pdfbox.cos.COSDictionary properties = new org.apache.pdfbox.cos.COSDictionary();
    properties.setInt(COSName.MCID, mcid);
    return properties;
  }

  @Test
  void aRunThatCannotBeWrappedIsLeftAsItIs() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDStructureElement link = new PDStructureElement("Link", root);
      root.appendKid(link);
      assertEquals(0, LinkParagraphs.apply(doc), "a Link directly under the tree root has no element to hold the new P: left alone");
    }
  }
}
