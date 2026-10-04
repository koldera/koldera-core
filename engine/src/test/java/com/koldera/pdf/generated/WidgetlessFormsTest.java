package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentLinks;
import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kidTypes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
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
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class WidgetlessFormsTest {

  private static final COSName PRINT_FIELD = COSName.getPDFName("PrintField");

  @Test
  void chromiumsFormTagsWithoutWidgetsBecomeDivsAndThePlaceholderPaintedAfterOneBecomesASpanWithActualText() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-forms.pdf")) {
      int forms = elements(doc, "Form").size();
      assertEquals(6, forms, "text input, email input, textarea, two buttons, checkbox");
      int divsBefore = elements(doc, "Div").size();
      String textBefore = new PDFTextStripper().getText(doc);
      int[] renderBefore = UntaggedArtifactsTest.render(doc, 0);

      WidgetlessForms.Outcome outcome = WidgetlessForms.apply(doc);

      assertEquals(new WidgetlessForms.Outcome(6, 1), outcome, "six Forms retagged; one painted value (the email placeholder) attributed");
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(0, elements(after, "Form").size());
        List<COSDictionary> divs = elements(after, "Div");
        assertEquals(divsBefore + 6, divs.size());
        for (COSDictionary div : divs) {
          assertFalse(hasPrintField(div), "the PrintField attributes of a field without a widget are dropped");
        }
        List<COSDictionary> spans = elements(after, "Span");
        assertEquals(1, spans.size());
        COSDictionary span = spans.get(0);
        assertEquals("name@example.org", span.getString(COSName.ACTUAL_TEXT), "ActualText is the painted text");
        COSDictionary emailDiv = (COSDictionary) span.getDictionaryObject(COSName.P);
        assertEquals("Div", emailDiv.getNameAsString(COSName.S));
        assertEquals(List.of("mcid", "Span"), kidTypes(emailDiv), "the value follows the field's own marked content");
        int mcid = ((COSInteger) span.getDictionaryObject(COSName.K)).intValue();
        assertEquals(List.of("Span:" + mcid), spanSequences(new PDFStreamParser(after.getPage(0)).parse()));
        assertParentTreeMatches(after);
        assertParentLinks(after);
        assertEquals(textBefore, new PDFTextStripper().getText(after), "extracted text unchanged");
        assertArrayEquals(renderBefore, UntaggedArtifactsTest.render(after, 0), "rendering unchanged");
        assertEquals(new WidgetlessForms.Outcome(0, 0), WidgetlessForms.apply(after), "idempotent");
      }
    }
  }

  @Test
  void aFormWithAWidgetStaysAFormAndTextAfterItStaysUntouched() throws IOException {
    try (PDDocument doc = synthetic("/Form <</MCID 0>> BDC EMC BT /F1 12 Tf 10 10 Td (Value) Tj ET", true)) {
      assertEquals(new WidgetlessForms.Outcome(0, 0), WidgetlessForms.apply(doc));
      assertEquals(1, elements(doc, "Form").size());
    }
  }

  @Test
  void textAfterAnotherMarkedSequenceOrAfterPaintingIsNotAttributedToTheField() throws IOException {
    for (String content : new String[] {
      "/Form <</MCID 0>> BDC EMC /P <</MCID 1>> BDC EMC BT /F1 12 Tf 10 10 Td (Value) Tj ET",
      "/Form <</MCID 0>> BDC EMC 0 0 5 5 re f BT /F1 12 Tf 10 10 Td (Value) Tj ET",
      "/Form <</MCID 0>> BDC EMC BT /F1 12 Tf 10 10 Td /Span <</MCID 1>> BDC (Tagged) Tj EMC (Value) Tj ET",
      "/Form <</MCID 0>> BDC EMC BT /F1 12 Tf 10 10 Td (   ) Tj ET",
    }) {
      try (PDDocument doc = synthetic(content, false)) {
        assertEquals(new WidgetlessForms.Outcome(1, 0), WidgetlessForms.apply(doc), content);
        assertEquals(1, elements(doc, "Div").size(), content);
        assertEquals(0, elements(doc, "Span").size() - (content.contains("/Span") ? 1 : 0), content);
      }
    }
  }

  @Test
  void aFooterPaintedRightAfterATrailingInputIsNotItsValue() throws IOException {
    // Chromium (generated-trailing-field.html): the empty <input> is the last element; the next text
    // object on the page, untagged and with only q/Q in between, is the main::after footer line
    // "Printed from example.org" 3rem below it. (Chromium paints the @page margin box first.)
    try (PDDocument doc = GeneratedStructure.fixture("generated-trailing-field.pdf")) {
      assertEquals(1, elements(doc, "Form").size(), "fixture shape: one printed input");

      WidgetlessForms.Outcome outcome = WidgetlessForms.apply(doc);

      assertEquals(new WidgetlessForms.Outcome(1, 0), outcome, "the footer lies outside the input's box");
      assertEquals(0, elements(doc, "Span").stream().filter(s -> s.containsKey(COSName.ACTUAL_TEXT)).count());
    }
  }

  @Test
  void textOutsideTheFieldsOwnBoundsIsNeverAttributedToIt() throws IOException {
    // A trailing field followed by an @page footer: the footer is the next text object, but it lies
    // far outside the box the field painted, so it is not the field's value.
    try (PDDocument doc = synthetic("/Form <</MCID 0>> BDC 5 150 100 20 re S EMC BT /F1 8 Tf 80 5 Td (Page 1 of 1) Tj ET", false)) {
      assertEquals(new WidgetlessForms.Outcome(1, 0), WidgetlessForms.apply(doc));
      assertEquals(0, elements(doc, "Span").size());
    }
    try (PDDocument doc = synthetic("/Form <</MCID 0>> BDC EMC BT /F1 12 Tf 10 10 Td (Value) Tj ET", false)) {
      assertEquals(new WidgetlessForms.Outcome(1, 0), WidgetlessForms.apply(doc), "a field that paints nothing has no bounds: nothing is attributed");
    }
    try (PDDocument doc = synthetic("/Form <</MCID 0>> BDC 5 150 100 20 re S EMC BT /F1 12 Tf 10 155 Td (Inside) Tj ET BT /F1 12 Tf 10 10 Td (Below) Tj ET", false)) {
      assertEquals(new WidgetlessForms.Outcome(1, 1), WidgetlessForms.apply(doc));
      assertEquals("Inside", elements(doc, "Span").get(0).getString(COSName.ACTUAL_TEXT), "only the text object inside the box is attributed");
      assertParentTreeMatches(doc);
    }
  }

  @Test
  void consecutiveTextObjectsRightAfterTheFieldBecomeOneSpanWithOneSequencePerTextObject() throws IOException {
    try (PDDocument doc = synthetic("/Form <</MCID 0>> BDC 5 5 100 20 re S EMC q BT /F1 12 Tf 10 10 Td (Pol) Tj ET Q q BT /F1 12 Tf 30 10 Td (and) Tj ET Q /P <</MCID 1>> BDC EMC", false)) {
      assertEquals(new WidgetlessForms.Outcome(1, 1), WidgetlessForms.apply(doc));
      COSDictionary span = elements(doc, "Span").get(0);
      assertEquals("Poland", span.getString(COSName.ACTUAL_TEXT));
      assertEquals(List.of("mcid", "mcid"), kidTypes(span));
      List<Object> tokens = new PDFStreamParser(doc.getPage(0)).parse();
      assertEquals(List.of("Span:2", "Span:3"), spanSequences(tokens), "new MCIDs after the page's highest one");
      assertParentTreeMatches(doc);
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static boolean hasPrintField(COSDictionary element) {
    COSBase a = element.getDictionaryObject(COSName.A);
    List<COSBase> owners = new ArrayList<>();
    if (a instanceof COSArray array) {
      array.forEach(e -> owners.add(GeneratedStructure.resolve(e)));
    } else if (a != null) {
      owners.add(a);
    }
    return owners.stream().anyMatch(o -> o instanceof COSDictionary d && PRINT_FIELD.equals(d.getCOSName(COSName.O)));
  }

  /** "Span:&lt;mcid&gt;" for every Span BDC in the stream. */
  private static List<String> spanSequences(List<Object> tokens) {
    List<String> out = new ArrayList<>();
    List<Object> operands = new ArrayList<>();
    for (Object token : tokens) {
      if (!(token instanceof Operator op)) {
        operands.add(token);
        continue;
      }
      if ("BDC".equals(op.getName()) && "Span".equals(((COSName) operands.get(0)).getName())) {
        out.add("Span:" + ((COSDictionary) operands.get(1)).getInt(COSName.MCID));
      }
      operands.clear();
    }
    return out;
  }

  /** Document > [P (MCID 1 if the content uses it), Form /A PrintField (MCID 0, plus an OBJR to a widget when asked)]. */
  private static PDDocument synthetic(String content, boolean withWidget) throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(new PDRectangle(200, 200));
    doc.addPage(page);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    PDResources resources = new PDResources();
    resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
    page.setResources(resources);
    PDStream stream = new PDStream(doc);
    try (OutputStream out = stream.createOutputStream()) {
      out.write(content.getBytes(StandardCharsets.ISO_8859_1));
    }
    page.setContents(stream);
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    PDStructureElement document = new PDStructureElement("Document", root);
    root.appendKid(document);
    PDStructureElement form = new PDStructureElement("Form", document);
    form.setPage(page);
    document.appendKid(form);
    COSDictionary printField = new COSDictionary();
    printField.setItem(COSName.O, PRINT_FIELD);
    printField.setItem(COSName.getPDFName("Role"), COSName.getPDFName("tv"));
    form.getCOSObject().setItem(COSName.A, printField);
    COSArray formKids = new COSArray();
    formKids.add(COSInteger.get(0));
    COSArray nums = new COSArray();
    COSArray pageArray = new COSArray();
    pageArray.add(form);
    if (withWidget) {
      PDAnnotationWidget widget = new PDAnnotationWidget();
      widget.setRectangle(new PDRectangle(10, 10, 50, 20));
      widget.setPage(page);
      widget.setStructParent(1);
      page.getAnnotations().add(widget);
      COSDictionary objr = new COSDictionary();
      objr.setItem(COSName.TYPE, COSName.getPDFName("OBJR"));
      objr.setItem(COSName.OBJ, widget);
      objr.setItem(COSName.PG, page);
      formKids.add(objr);
    }
    form.getCOSObject().setItem(COSName.K, formKids);
    if (content.contains("/P <</MCID 1>>") || content.contains("/Span <</MCID 1>>")) {
      String type = content.contains("/P <</MCID 1>>") ? "P" : "Span";
      PDStructureElement other = new PDStructureElement(type, document);
      other.setPage(page);
      other.getCOSObject().setItem(COSName.K, COSInteger.get(1));
      document.appendKid(other);
      pageArray.add(other);
    }
    nums.add(COSInteger.get(0));
    nums.add(pageArray);
    if (withWidget) {
      nums.add(COSInteger.get(1));
      nums.add(form);
    }
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, nums);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.setParentTreeNextKey(2);
    return doc;
  }
}
