package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** Discarding unusable tags on the copy: every MCID and the tree go, nothing painted and no text changes. */
class TagStripperTest {

  @Test
  void strippingRemovesTheTreeAndEveryMcid() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      String text = PdfPreflight.extractText(doc);
      assertNull(RenderIdentity.changes(doc, List.of(1), scratch -> TagStripper.strip(scratch)), "rendering and text identical");

      TagStripper.Outcome outcome = TagStripper.strip(doc);

      assertEquals(new TagStripper.Outcome(2, 2, 1), outcome);
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
      assertFalse(doc.getPage(0).getCOSObject().containsKey(COSName.STRUCT_PARENTS));
      assertFalse(content(doc.getPage(0)).contains("/MCID"), content(doc.getPage(0)));
      assertEquals(text, PdfPreflight.extractText(doc));
    }
  }

  @Test
  void anMcidSequenceWithActualTextKeepsItsActualText() throws Exception {
    try (PDDocument doc = withContent("/Span <</MCID 0 /ActualText (fi)>> BDC BT /F1 12 Tf 72 700 Td ( ) Tj ET EMC")) {
      TagStripper.Outcome outcome = TagStripper.strip(doc);

      assertEquals(new TagStripper.Outcome(0, 1, 1), outcome);
      List<Object> tokens = tokens(doc.getPage(0));
      assertEquals(COSName.getPDFName("Span"), tokens.get(0));
      COSDictionary properties = (COSDictionary) tokens.get(1);
      assertFalse(properties.containsKey(COSName.MCID));
      assertEquals("fi", properties.getString(COSName.ACTUAL_TEXT));
      assertEquals(1, properties.size());
      assertEquals("BDC", ((Operator) tokens.get(2)).getName());
      assertEquals("EMC", ((Operator) tokens.get(tokens.size() - 1)).getName());
    }
  }

  @Test
  void aNamedPropertyListIsCopiedInlineWithoutItsMcid() throws Exception {
    try (PDDocument doc = withContent("/Span /MC0 BDC BT /F1 12 Tf 72 700 Td (x) Tj ET EMC /P /MC1 BDC BT /F1 12 Tf 72 680 Td (y) Tj ET EMC")) {
      COSDictionary mc0 = new COSDictionary();
      mc0.setInt(COSName.MCID, 0);
      mc0.setString(COSName.LANG, "de");
      COSDictionary mc1 = new COSDictionary();
      mc1.setInt(COSName.MCID, 1);
      COSDictionary properties = new COSDictionary();
      properties.setItem(COSName.getPDFName("MC0"), mc0);
      properties.setItem(COSName.getPDFName("MC1"), mc1);
      doc.getPage(0).getResources().getCOSObject().setItem(COSName.PROPERTIES, properties);

      assertEquals(new TagStripper.Outcome(1, 2, 1), TagStripper.strip(doc));

      String content = content(doc.getPage(0));
      assertFalse(content.contains("/MCID"), content);
      assertTrue(content.contains("/Lang (de)"), content);
      assertFalse(content.contains("/MC1"), content);
    }
  }

  @Test
  void anArtifactThatCarriesAnMcidStaysAnArtifact() throws Exception {
    try (PDDocument doc = withContent(
        "/Artifact <</MCID 0>> BDC 0 0 m 10 10 l S EMC /Artifact <</MCID 1 /Type /Pagination>> BDC BT /F1 12 Tf 72 700 Td (x) Tj ET EMC")) {
      assertEquals(new TagStripper.Outcome(0, 2, 1), TagStripper.strip(doc));

      List<Object> tokens = tokens(doc.getPage(0));
      assertEquals(COSName.ARTIFACT, tokens.get(0));
      assertEquals("BMC", ((Operator) tokens.get(1)).getName(), "no properties left: /Artifact BMC");
      int second = tokens.lastIndexOf(COSName.ARTIFACT);
      COSDictionary properties = (COSDictionary) tokens.get(second + 1);
      assertEquals(COSName.getPDFName("Pagination"), properties.getDictionaryObject(COSName.TYPE));
      assertFalse(properties.containsKey(COSName.MCID));
      assertEquals("BDC", ((Operator) tokens.get(second + 2)).getName());
      assertEquals(2, tokens.stream().filter(t -> t instanceof Operator o && o.getName().equals("EMC")).count(), "both sequences keep their EMC");
    }
  }

  @Test
  void anIndirectValueInANamedPropertyListIsCopiedInlineResolved() throws Exception {
    byte[] pdf;
    try (PDDocument doc = withContent("/Span /MC0 BDC BT /F1 12 Tf 72 700 Td ( ) Tj ET EMC"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      COSDictionary mc0 = new COSDictionary();
      mc0.setInt(COSName.MCID, 0);
      org.apache.pdfbox.cos.COSObject actualText = new org.apache.pdfbox.cos.COSObject(new org.apache.pdfbox.cos.COSString("fi"));
      mc0.setItem(COSName.ACTUAL_TEXT, actualText);
      COSDictionary properties = new COSDictionary();
      properties.setItem(COSName.getPDFName("MC0"), mc0);
      doc.getPage(0).getResources().getCOSObject().setItem(COSName.PROPERTIES, properties);
      doc.save(out);
      pdf = out.toByteArray();
    }
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      COSDictionary mc0 = (COSDictionary) doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.PROPERTIES).getDictionaryObject(COSName.getPDFName("MC0"));
      assertTrue(mc0.getItem(COSName.ACTUAL_TEXT) instanceof org.apache.pdfbox.cos.COSObject, "the fixture's /ActualText is an indirect object");

      assertEquals(new TagStripper.Outcome(0, 1, 1), TagStripper.strip(doc));

      COSDictionary inline = (COSDictionary) tokens(doc.getPage(0)).get(1);
      assertEquals("fi", inline.getString(COSName.ACTUAL_TEXT));
    }
  }

  @Test
  void anMcidInAFormIsStrippedOnceAndAnnotationParentsAreRemoved() throws Exception {
    try (PDDocument doc = withContent("/Fm0 Do")) {
      PDPage second = new PDPage(PDRectangle.A4);
      doc.addPage(second);
      org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
      form.setBBox(new PDRectangle(0, 0, 600, 800));
      PDResources formResources = new PDResources();
      formResources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      form.setResources(formResources);
      try (OutputStream out = form.getContentStream().createOutputStream()) {
        out.write("/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (form) Tj ET EMC".getBytes(StandardCharsets.US_ASCII));
      }
      form.getCOSObject().setInt(COSName.STRUCT_PARENTS, 5);
      doc.getPage(0).getResources().put(COSName.getPDFName("Fm0"), form);
      PDResources secondResources = new PDResources();
      secondResources.put(COSName.getPDFName("Fm0"), form);
      second.setResources(secondResources);
      PDStream secondContent = new PDStream(doc);
      try (OutputStream out = secondContent.createOutputStream()) {
        out.write("/Fm0 Do".getBytes(StandardCharsets.US_ASCII));
      }
      second.setContents(secondContent);
      org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
      link.setRectangle(new PDRectangle(70, 690, 100, 20));
      link.setStructParent(7);
      doc.getPage(0).getAnnotations().add(link);

      TagStripper.Outcome outcome = TagStripper.strip(doc);

      assertEquals(new TagStripper.Outcome(1, 1, 3), outcome, "the shared form is rewritten once; page, form and annotation parents removed");
      try (var in = form.getContentStream().createInputStream()) {
        String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains("MCID"), content);
        assertTrue(content.contains("(form) Tj"), content);
      }
      assertFalse(form.getCOSObject().containsKey(COSName.STRUCT_PARENTS));
      assertFalse(link.getCOSObject().containsKey(COSName.getPDFName("StructParent")));
    }
  }

  @Test
  void anOptionalContentSequenceIsLeftExactlyAsItWas() throws Exception {
    try (PDDocument doc = withContent("/OC /oc1 BDC BT /F1 12 Tf 72 700 Td (x) Tj ET EMC /P <</MCID 0>> BDC BT /F1 12 Tf 72 680 Td (y) Tj ET EMC")) {
      TagStripper.strip(doc);

      List<Object> tokens = tokens(doc.getPage(0));
      assertEquals(COSName.getPDFName("OC"), tokens.get(0));
      assertEquals(COSName.getPDFName("oc1"), tokens.get(1));
      assertEquals("BDC", ((Operator) tokens.get(2)).getName());
      assertTrue(content(doc.getPage(0)).startsWith("/OC /oc1 BDC"), content(doc.getPage(0)));
    }
  }

  @Test
  void afterStrippingTheTaggerKeepsTheActualTextSpanInsideOneParagraph() throws Exception {
    try (PDDocument doc = withContent("/Span <</MCID 0 /ActualText (fi)>> BDC BT /F1 12 Tf 72 700 Td ( ) Tj ET EMC")) {
      TagStripper.strip(doc);
      com.koldera.pdf.structure.ContentTagger.apply(doc, com.koldera.pdf.structure.ContentTagger.analyze(doc, true));

      List<String> shape = new java.util.ArrayList<>();
      for (Object t : tokens(doc.getPage(0))) {
        if (t instanceof Operator o && (o.getName().equals("BDC") || o.getName().equals("EMC") || o.getName().equals("Tj"))) {
          shape.add(o.getName());
        }
      }
      assertEquals(List.of("BDC", "BDC", "Tj", "EMC", "EMC"), shape, "P wraps the Span, both closed");
      List<Object> tokens = tokens(doc.getPage(0));
      assertEquals(COSName.P, tokens.get(0));
      assertEquals(0, ((COSDictionary) tokens.get(1)).getInt(COSName.MCID));
      assertEquals(COSName.getPDFName("Span"), tokens.get(3));
      assertEquals("fi", ((COSDictionary) tokens.get(4)).getString(COSName.ACTUAL_TEXT));
      var document = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)
          doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      assertEquals(1, document.getKids().size(), "the text is in one element");
      assertEquals("P", ((org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) document.getKids().get(0)).getStructureType());
    }
  }

  @Test
  void unbalancedMarkedContentIsRefused() throws Exception {
    try (PDDocument doc = withContent("/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (x) Tj ET")) {
      UnsupportedContentException e = assertThrows(UnsupportedContentException.class, () -> TagStripper.strip(doc));
      assertEquals("existing marked content is not balanced; the tags are kept", e.getMessage());
      assertTrue(doc.getDocumentCatalog().getStructureTreeRoot() != null, "nothing was changed");
    }
    try (PDDocument doc = withContent("BT /F1 12 Tf 72 700 Td (x) Tj ET EMC")) {
      assertThrows(UnsupportedContentException.class, () -> TagStripper.strip(doc));
    }
  }

  /** One page with {@code content}, font F1 (Helvetica), /StructParents 0 and an empty structure tree root. */
  private static PDDocument withContent(String content) throws IOException {
    PDDocument doc = new PDDocument();
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    PDResources resources = new PDResources();
    resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
    page.setResources(resources);
    PDStream stream = new PDStream(doc);
    try (OutputStream out = stream.createOutputStream()) {
      out.write(content.getBytes(StandardCharsets.US_ASCII));
    }
    page.setContents(stream);
    page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
    doc.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    doc.save(bytes);
    doc.close();
    return Loader.loadPDF(bytes.toByteArray());
  }

  private static List<Object> tokens(PDPage page) throws IOException {
    return new PDFStreamParser(page).parse();
  }

  private static String content(PDPage page) throws IOException {
    try (var in = page.getContents()) {
      return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }
}
