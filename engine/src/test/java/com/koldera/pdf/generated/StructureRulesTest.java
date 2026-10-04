package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** One test group per PAC-only rule; each builds the smallest tree that breaks it and its valid twin. */
class StructureRulesTest {

  // ---- tree builder ---------------------------------------------------------------------------

  /** A structure element: kids are other elements (COSDictionary), Integer MCIDs, or MCR dictionaries. */
  private static COSDictionary el(String type, Object... kids) {
    COSDictionary dict = new COSDictionary();
    dict.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
    dict.setItem(COSName.S, COSName.getPDFName(type));
    COSArray k = new COSArray();
    for (Object kid : kids) {
      if (kid instanceof Integer mcid) {
        k.add(COSInteger.get(mcid));
      } else {
        COSDictionary child = (COSDictionary) kid;
        k.add(child);
        if (child.getDictionaryObject(COSName.S) != null) {
          child.setItem(COSName.P, dict);
        }
      }
    }
    dict.setItem(COSName.K, k);
    return dict;
  }

  private static COSDictionary mcr(int mcid) {
    COSDictionary dict = new COSDictionary();
    dict.setItem(COSName.TYPE, COSName.getPDFName("MCR"));
    dict.setInt(COSName.MCID, mcid);
    return dict;
  }

  private static COSDictionary placement(COSDictionary element, String value) {
    COSDictionary layout = new COSDictionary();
    layout.setItem(COSName.O, COSName.getPDFName("Layout"));
    layout.setItem(COSName.getPDFName("Placement"), COSName.getPDFName(value));
    element.setItem(COSName.A, layout);
    return element;
  }

  private static Map<String, Integer> check(COSDictionary document) throws IOException {
    return check(document, null);
  }

  private static Map<String, Integer> check(COSDictionary document, COSDictionary roleMap) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      COSDictionary rootDict = root.getCOSObject();
      rootDict.setItem(COSName.K, document);
      document.setItem(COSName.P, rootDict);
      if (roleMap != null) {
        rootDict.setItem(COSName.ROLE_MAP, roleMap);
      }
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      return StructureRules.check(doc);
    }
  }

  private static COSDictionary doc(Object... kids) {
    return el("Document", kids);
  }

  private static COSDictionary p(Object... kids) {
    return el("P", kids);
  }

  // ---- no tree / valid document ------------------------------------------------------------------

  @Test
  void aDocumentWithoutAStructureTreeHasNoFindings() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      assertEquals(Map.of(), StructureRules.check(doc));
    }
  }

  @Test
  void aWellFormedDocumentHasNoFindings() throws IOException {
    COSDictionary tree = doc(
        el("H1", 0),
        p(1, el("Link", 2), el("Ruby", el("RB", 3), el("RP"), el("RT", 4), el("RP")), el("Figure", 5)),
        el("H2", 6),
        el("L", el("LI", el("Lbl", 7), el("LBody", 8, el("L", el("LI", el("LBody", 9)))))),
        el("Table", el("THead", el("TR", el("TH", 10))), el("TBody", el("TR", el("TD", 11))), el("TFoot", el("TR", el("TD", 12)))),
        el("Table", el("Caption", 13), el("TR", el("TH", 14)), el("TR", el("TD", 15))),
        placement(el("Figure", 16), "Block"),
        el("H3", 17),
        el("H2", 18));
    assertEquals(Map.of(), check(tree));
  }

  // ---- Ruby -------------------------------------------------------------------------------------

  @Test
  void aRubyMustReadRbRpRtRp() throws IOException {
    assertEquals(Map.of(StructureRules.RUBY, 1), check(doc(p(el("Ruby", el("RB", 0), el("RT", 1))))), "RB, RT without RPs: PAC RTTag-InvalidUse");
    assertEquals(Map.of(StructureRules.RUBY, 1), check(doc(p(el("Ruby", el("NonStruct", 0), el("RT", 1))))), "base not in an RB");
    assertEquals(Map.of(StructureRules.RUBY, 1), check(doc(p(el("Ruby", el("RB", 0), el("RP"), el("RT", 1), el("RP"), el("RB", 2), el("RP"), el("RT", 3), el("RP"))))), "two pairs in one Ruby");
    assertEquals(Map.of(StructureRules.RUBY, 1), check(doc(p(el("Ruby", 0, el("RP"), el("RT", 1), el("RP"))))), "marked content directly in Ruby");
  }

  @Test
  void rubyPartsMustSitInARuby() throws IOException {
    assertEquals(Map.of(StructureRules.RUBY, 2), check(doc(p(el("RB", 0), el("RT", 1)))));
  }

  // ---- headings ---------------------------------------------------------------------------------

  @Test
  void theFirstHeadingMustBeH1() throws IOException {
    assertEquals(Map.of(StructureRules.HEADING_ORDER, 1), check(doc(el("H2", 0), el("H3", 1))));
  }

  @Test
  void noHeadingLevelMayBeSkipped() throws IOException {
    assertEquals(Map.of(StructureRules.HEADING_ORDER, 2), check(doc(el("H1", 0), el("H3", 1), el("H2", 2), el("H4", 3))));
    assertEquals(Map.of(), check(doc(el("H1", 0), el("H2", 1), el("H3", 2), el("H1", 3), el("H2", 4))), "going back up any number of levels is fine");
  }

  @Test
  void aTreeUsingStronglyStructuredHeadingsIsNotCheckedForLevels() throws IOException {
    assertEquals(Map.of(), check(doc(el("H", 0), el("H3", 1))));
  }

  @Test
  void aDocumentWithoutHeadingsPassesTheHeadingRule() throws IOException {
    assertEquals(Map.of(), check(doc(p(0))));
  }

  // ---- lists ------------------------------------------------------------------------------------

  @Test
  void aListHoldsListItemsOnly() throws IOException {
    assertEquals(Map.of(StructureRules.LIST, 1), check(doc(el("L", el("LI", el("LBody", 0)), p(1)))), "P directly in L");
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(doc(el("L", el("LI", el("LBody", 0)), 1))), "marked content directly in L");
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(doc(el("L", el("LI", el("LBody", 0)), mcr(1)))), "an MCR directly in L");
  }

  @Test
  void listItemsAndTheirPartsMustSitWhereTheyBelong() throws IOException {
    assertEquals(Map.of(StructureRules.LIST, 1), check(doc(el("LI", el("LBody", 0)))), "LI outside L");
    assertEquals(Map.of(StructureRules.LIST, 1), check(doc(el("L", el("LI", p(0))))), "P directly in LI");
    assertEquals(Map.of(StructureRules.LIST, 1), check(doc(el("LBody", 0))), "LBody outside LI");
  }

  // ---- tables -----------------------------------------------------------------------------------

  @Test
  void tableChildRules() throws IOException {
    assertEquals(Map.of(StructureRules.TABLE, 1), check(doc(el("Table", el("TD", 0)))), "TD directly in Table");
    assertEquals(Map.of(StructureRules.TABLE, 1), check(doc(el("Table", el("THead", el("TH", 0)), el("TR", el("TD", 1))))), "TH directly in THead");
    assertEquals(Map.of(StructureRules.TABLE, 1), check(doc(el("Table", el("TR", p(0))))), "P directly in TR");
    assertEquals(Map.of(StructureRules.TABLE, 1), check(doc(el("TR", el("TD", 0)))), "TR outside a table");
    assertEquals(Map.of(StructureRules.TABLE, 1), check(doc(p(el("TD", 0)))), "TD outside a row");
  }

  @Test
  void aTBodyWithoutATHeadIsReported() throws IOException {
    assertEquals(Map.of(StructureRules.TBODY_WITHOUT_THEAD, 1), check(doc(el("Table", el("TBody", el("TR", el("TD", 0)))))));
  }

  @Test
  void contentDirectlyInTableStructureIsReported() throws IOException {
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(doc(el("Table", 0, el("TR", el("TD", 1))))));
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(doc(el("Table", el("TR", 0, el("TD", 1))))));
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(doc(el("Table", el("THead", el("TR", el("TH", 1)), 2), el("TBody", el("TR", el("TD", 3)))))));
  }

  // ---- figures ----------------------------------------------------------------------------------

  @Test
  void aFigureInAGroupingElementNeedsPlacement() throws IOException {
    assertEquals(Map.of(StructureRules.FIGURE_PLACEMENT, 1), check(doc(el("Figure", 0))));
    assertEquals(Map.of(StructureRules.FIGURE_PLACEMENT, 1), check(doc(el("Div", el("NonStruct", el("Figure", 0))))), "NonStruct is transparent");
    assertEquals(Map.of(), check(doc(placement(el("Figure", 0), "Block"))));
    assertEquals(Map.of(), check(doc(p(el("Figure", 0)))), "inside a paragraph a Figure is inline");
  }

  private static COSDictionary alt(COSDictionary element, String text) {
    element.setItem(COSName.ALT, new org.apache.pdfbox.cos.COSString(text));
    return element;
  }

  @Test
  void aFigureWhoseAltIsBlankIsReported() throws IOException {
    // PAC: <img alt="   "> gave Figure /Alt "   ": "Alternative text for Figure" error;
    // veraPDF 7.3-1 only asks for the key. A missing /Alt is veraPDF's (7.3-1), not this rule's.
    assertEquals(Map.of(StructureRules.FIGURE_ALT_BLANK, 1), check(doc(p(alt(el("Figure", 0), "   ")))));
    assertEquals(Map.of(StructureRules.FIGURE_ALT_BLANK, 1), check(doc(p(alt(el("Figure", 0), "")))));
    assertEquals(Map.of(StructureRules.FIGURE_ALT_BLANK, 1), check(doc(p(alt(el("Figure", 0), " 	")))));
    assertEquals(Map.of(), check(doc(p(alt(el("Figure", 0), "Chart")))));
    assertEquals(Map.of(), check(doc(p(el("Figure", 0)))));
  }

  // ---- grouping elements -------------------------------------------------------------------------

  @Test
  void aDivInsideAParagraphOrOtherInlineContainerIsReported() throws IOException {
    // PAC: an empty Div (a printed control whose box became an artifact) inside a P gave one
    // "Div" structure elements warning each; the same holds for a Div with inline content in a P.
    assertEquals(Map.of(StructureRules.DIV_PLACEMENT, 2), check(doc(p(0, el("Div"), el("NonStruct", el("Div", el("Span", 1)))))));
    assertEquals(Map.of(StructureRules.DIV_PLACEMENT, 1), check(doc(el("H1", el("Span", el("Div", el("Span", 0)))))));
    assertEquals(Map.of(), check(doc(el("Div", p(0)), el("Sect", el("Div", p(1))), el("L", el("LI", el("LBody", el("Div", p(2))))))));
  }

  @Test
  void markedContentDirectlyInAGroupingElementIsReported() throws IOException {
    // PAC: the background of an <aside> as the Aside's own marked content gave two
    // "Content is present in admissible locations" warnings (one per page).
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 2), check(doc(el("Aside", 0, p(1), mcr(2)))));
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(doc(el("Div", 0, p(1)))));
    assertEquals(Map.of(StructureRules.CONTENT_POSITION, 1), check(el("Document", 0, p(1))));
    assertEquals(Map.of(), check(doc(el("Aside", p(1)), el("Div", el("NonStruct", p(2))))));
  }

  // ---- links ------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"Document", "Div", "Sect", "BlockQuote"})
  void aLinkMustNotSitDirectlyInAGroupingElement(String grouping) throws IOException {
    COSDictionary tree = "Document".equals(grouping) ? doc(el("Link", 0)) : doc(el(grouping, el("Link", 0)));
    assertEquals(Map.of(StructureRules.LINK_PLACEMENT, 1), check(tree));
    COSDictionary nonStruct = doc(el(grouping, el("NonStruct", el("H1", 1), el("Link", 0))));
    assertEquals(Map.of(StructureRules.LINK_PLACEMENT, 1), check(nonStruct), "a NonStruct mixing blocks and inline content is transparent");
  }

  @Test
  void aLinkMustNotSitDirectlyInATableCaption() throws IOException {
    // PAC: <caption>… <a>…</a></caption> gives one LinkTag-PossibleInappropriateUseParagraph warning.
    assertEquals(Map.of(StructureRules.LINK_PLACEMENT, 1), check(doc(el("Table", el("Caption", 0, el("Link", 1)), el("TR", el("TH", 2))))));
    assertEquals(Map.of(), check(doc(el("Table", el("Caption", p(0, el("Link", 1))), el("TR", el("TH", 2))))));
  }

  @Test
  void linksInParagraphsListsAndCellsAreFine() throws IOException {
    assertEquals(Map.of(), check(doc(p(el("Link", 0)), el("L", el("LI", el("LBody", el("Link", 1)))),
        el("Table", el("TR", el("TD", el("Link", 2)))), el("H1", el("Link", 3)))));
  }

  // ---- role map ---------------------------------------------------------------------------------

  @Test
  void typesAreReadThroughTheRoleMap() throws IOException {
    COSDictionary roleMap = new COSDictionary();
    roleMap.setItem(COSName.getPDFName("Strong"), COSName.getPDFName("Span"));
    roleMap.setItem(COSName.getPDFName("Title"), COSName.getPDFName("H1"));
    roleMap.setItem(COSName.getPDFName("Loop"), COSName.getPDFName("Loop"));
    assertEquals(Map.of(), check(doc(el("Title", 0), p(el("Strong", el("Link", 1))), p(el("Loop", 2))), roleMap));
    assertEquals(Map.of(StructureRules.HEADING_ORDER, 1), check(doc(el("Title", 0), el("H3", 1)), roleMap));
  }

  // ---- summary ----------------------------------------------------------------------------------

  @Test
  void summaryNamesEachRuleWithItsCountInOrder() {
    assertNull(StructureRules.summary(Map.of()));
    assertEquals("FIGURE_PLACEMENT ×2, RUBY ×1", StructureRules.summary(Map.of(StructureRules.RUBY, 1, StructureRules.FIGURE_PLACEMENT, 2)));
  }

  @Test
  void aCyclicTreeIsReadWithoutLooping() throws IOException {
    COSDictionary div = el("Div");
    COSDictionary inner = el("Div", div);
    ((COSArray) div.getDictionaryObject(COSName.K)).add((COSBase) inner);
    assertEquals(Map.of(), check(doc(div)));
  }
}
