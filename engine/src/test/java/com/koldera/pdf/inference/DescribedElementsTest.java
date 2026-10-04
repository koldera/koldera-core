package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

/** The refusal branches and language/link rules of {@link DescribedElements}, on small in-memory documents. */
class DescribedElementsTest {

  private static final String LINE_A = "BT /F1 11 Tf 72 700 Td (Alpha) Tj ET";
  private static final String LINE_B = "BT /F1 11 Tf 72 680 Td (Beta) Tj ET";

  /** An MCID whose page cannot be resolved must not leave the description on only part of the content. */
  @Test
  void anElementWithAnUnresolvablePageForOneOfItsMcidsIsRefused() throws Exception {
    try (PDDocument doc = document(List.of("/X <</MCID 0>> BDC " + LINE_A + " EMC /X <</MCID 1>> BDC " + LINE_B + " EMC"), (d, root) -> {
      PDStructureElement formula = element(root, "Formula", COSName.ACTUAL_TEXT, "E = mc2", null);
      COSArray k = new COSArray();
      k.add(mcr(d.getPage(0), 0));
      k.add(COSInteger.get(1)); // no /Pg on the element: this MCID's page is unknown
      formula.getCOSObject().setItem(COSName.K, k);
    })) {
      assertThrows(DescribedElements.Refused.class, () -> DescribedElements.check(doc));
    }
  }

  @Test
  void anMcidReferencedByTwoElementsInADescribedSubtreeIsRefused() throws Exception {
    try (PDDocument doc = document(List.of("/X <</MCID 0>> BDC " + LINE_A + " EMC"), (d, root) -> {
      element(root, "Span", COSName.ACTUAL_TEXT, "A", d.getPage(0)).getCOSObject().setItem(COSName.K, COSInteger.ZERO);
      element(root, "P", null, null, d.getPage(0)).getCOSObject().setItem(COSName.K, COSInteger.ZERO);
    })) {
      assertThrows(DescribedElements.Refused.class, () -> DescribedElements.check(doc));
    }
  }

  @Test
  void contentThatCrossesAGraphicsStateBoundaryIsRefused() throws Exception {
    try (PDDocument doc = document(List.of("q /X <</MCID 0>> BDC " + LINE_A + " EMC Q /X <</MCID 1>> BDC " + LINE_B + " EMC"), (d, root) ->
        element(root, "Formula", COSName.ALT, "two lines", d.getPage(0)).getCOSObject().setItem(COSName.K, mcids(0, 1)))) {
      assertThrows(DescribedElements.Refused.class, () -> DescribedElements.check(doc));
    }
  }

  @Test
  void contentInTwoStreamsIsRefused() throws Exception {
    try (PDDocument doc = document(List.of("/X <</MCID 0>> BDC " + LINE_A + " EMC", "/X <</MCID 0>> BDC " + LINE_B + " EMC"), (d, root) -> {
      COSArray k = new COSArray();
      k.add(mcr(d.getPage(0), 0));
      k.add(mcr(d.getPage(1), 0));
      element(root, "Formula", COSName.ALT, "across pages", null).getCOSObject().setItem(COSName.K, k);
    })) {
      assertThrows(DescribedElements.Refused.class, () -> DescribedElements.check(doc));
    }
  }

  @Test
  void unbalancedMarkedContentIsRefused() throws Exception {
    try (PDDocument doc = document(List.of("/X <</MCID 0>> BDC " + LINE_A + " EMC /Y BMC " + LINE_B), (d, root) ->
        element(root, "Span", COSName.ACTUAL_TEXT, "A", d.getPage(0)).getCOSObject().setItem(COSName.K, COSInteger.ZERO))) {
      assertThrows(DescribedElements.Refused.class, () -> DescribedElements.check(doc));
    }
  }

  @Test
  void anMcidThatCannotBeFoundIsRefused() throws Exception {
    try (PDDocument doc = document(List.of(LINE_A), (d, root) ->
        element(root, "Span", COSName.ACTUAL_TEXT, "A", d.getPage(0)).getCOSObject().setItem(COSName.K, COSInteger.ZERO))) {
      assertThrows(DescribedElements.Refused.class, () -> DescribedElements.check(doc));
    }
  }

  /**
   * Review minor: a descendant's /Lang is compared with the language its marked content is enclosed
   * in (here the described Span's German wrapper), not with the catalog's: the English child keeps
   * "en" although the catalog says "en".
   */
  @Test
  void aDescendantsLanguageIsComparedWithTheEnclosingDescribedLanguage() throws Exception {
    try (PDDocument doc = document(List.of("/X <</MCID 0>> BDC " + LINE_A + " EMC /X <</MCID 1>> BDC " + LINE_B + " EMC"), (d, root) -> {
      PDStructureElement span = element(root, "Span", COSName.ACTUAL_TEXT, "Alpha Beta", d.getPage(0));
      span.getCOSObject().setString(COSName.LANG, "de");
      PDStructureElement child = new PDStructureElement("Span", span);
      child.setPage(d.getPage(0));
      child.getCOSObject().setString(COSName.LANG, "en");
      child.getCOSObject().setItem(COSName.K, COSInteger.ONE);
      COSArray k = new COSArray();
      k.add(COSInteger.ZERO);
      k.add(child.getCOSObject());
      span.getCOSObject().setItem(COSName.K, k);
    })) {
      DescribedElements.Outcome outcome = DescribedElements.carry(doc);

      assertEquals(1, outcome.described());
      List<COSDictionary> props = inlineProperties(doc.getPage(0));
      COSDictionary wrapper = props.stream().filter(p -> p.containsKey(COSName.ACTUAL_TEXT)).findFirst().orElseThrow();
      assertEquals("de", wrapper.getString(COSName.LANG));
      COSDictionary second = props.stream().filter(p -> p.getInt(COSName.MCID) == 1).findFirst().orElseThrow();
      assertEquals("en", second.getString(COSName.LANG));
      COSDictionary first = props.stream().filter(p -> p.getInt(COSName.MCID) == 0).findFirst().orElseThrow();
      assertFalse(first.containsKey(COSName.LANG), "the first sequence is German through the wrapper");
    }
  }

  /** Review minor: a Link /Alt without marked content goes to its annotation's missing /Contents. */
  @Test
  void aLinkAltWithoutMarkedContentIsCopiedToAnAnnotationWithoutContents() throws Exception {
    try (PDDocument doc = document(List.of(LINE_A), (d, root) -> link(d, root, null))) {
      DescribedElements.Outcome outcome = DescribedElements.carry(doc);

      assertEquals(1, outcome.linksToAnnotations());
      assertEquals(0, outcome.linksNotCarried());
      assertEquals("Go to the index", ((COSDictionary) doc.getPage(0).getCOSObject().getCOSArray(COSName.ANNOTS).getObject(0)).getString(COSName.CONTENTS));
    }
  }

  @Test
  void aLinkAltIsNotCopiedOverAnAnnotationsOwnContents() throws Exception {
    try (PDDocument doc = document(List.of(LINE_A), (d, root) -> link(d, root, "Index"))) {
      DescribedElements.Outcome outcome = DescribedElements.carry(doc);

      assertEquals(0, outcome.linksToAnnotations());
      assertEquals(1, outcome.linksNotCarried());
      assertEquals("Index", ((COSDictionary) doc.getPage(0).getCOSObject().getCOSArray(COSName.ANNOTS).getObject(0)).getString(COSName.CONTENTS));
    }
  }

  @Test
  void checkWritesNothing() throws Exception {
    try (PDDocument doc = document(List.of("/X <</MCID 0>> BDC " + LINE_A + " EMC"), (d, root) ->
        element(root, "Span", COSName.ACTUAL_TEXT, "A", d.getPage(0)).getCOSObject().setItem(COSName.K, COSInteger.ZERO))) {
      DescribedElements.check(doc);

      assertNull(inlineProperties(doc.getPage(0)).get(0).getDictionaryObject(COSName.ACTUAL_TEXT));
    }
  }

  // ---- fixtures -----------------------------------------------------------------------------------------

  private static void link(PDDocument d, PDStructureElement root, String contents) {
    PDAnnotationLink annotation = new PDAnnotationLink();
    annotation.setRectangle(new PDRectangle(72, 700, 50, 12));
    if (contents != null) {
      annotation.setContents(contents);
    }
    List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> annotations = new ArrayList<>();
    annotations.add(annotation);
    d.getPage(0).setAnnotations(annotations);
    PDStructureElement link = element(root, "Link", COSName.ALT, "Go to the index", d.getPage(0));
    COSDictionary objr = new COSDictionary();
    objr.setItem(COSName.TYPE, COSName.getPDFName("OBJR"));
    objr.setItem(COSName.OBJ, annotation.getCOSObject());
    link.getCOSObject().setItem(COSName.K, objr);
  }

  private static PDStructureElement element(PDStructureElement parent, String type, COSName key, String value, PDPage page) {
    PDStructureElement element = new PDStructureElement(type, parent);
    if (page != null) {
      element.setPage(page);
    }
    if (key != null) {
      element.getCOSObject().setString(key, value);
    }
    parent.appendKid(element);
    return element;
  }

  private static COSArray mcids(int... values) {
    COSArray k = new COSArray();
    for (int v : values) {
      k.add(COSInteger.get(v));
    }
    return k;
  }

  private static COSDictionary mcr(PDPage page, int mcid) {
    COSDictionary mcr = new COSDictionary();
    mcr.setItem(COSName.TYPE, COSName.MCR);
    mcr.setItem(COSName.PG, page.getCOSObject());
    mcr.setInt(COSName.MCID, mcid);
    return mcr;
  }

  /** One page per content string (Helvetica /F1), a tree root with a Document element that {@code tree} fills; catalog /Lang en. */
  private static PDDocument document(List<String> contents, BiConsumer<PDDocument, PDStructureElement> tree) throws Exception {
    PDDocument doc = new PDDocument();
    for (String content : contents) {
      PDPage page = new PDPage(PDRectangle.A4);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      page.setResources(resources);
      PDStream stream = new PDStream(doc);
      try (OutputStream out = stream.createOutputStream()) {
        out.write(content.getBytes(StandardCharsets.US_ASCII));
      }
      page.setContents(stream);
      doc.addPage(page);
    }
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    PDStructureElement document = new PDStructureElement("Document", root);
    root.appendKid(document);
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    doc.getDocumentCatalog().setLanguage("en");
    tree.accept(doc, document);
    return doc;
  }

  /** The inline property lists of the page's BDC operators, in order. */
  private static List<COSDictionary> inlineProperties(PDPage page) throws Exception {
    List<COSDictionary> out = new ArrayList<>();
    Object previous = null;
    for (Object token : new PDFStreamParser(page).parse()) {
      if (token instanceof Operator op && "BDC".equals(op.getName()) && previous instanceof COSDictionary props) {
        out.add(props);
      }
      previous = token;
    }
    return out;
  }

}
