package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.inference.StructureStrategy.Decision;
import com.koldera.pdf.inference.StructureStrategy.Kind;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** Per upload, keep the producer's tags, or discard them on the copy and build structure from the content. */
class StructureStrategyTest {

  private static Decision decide(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return StructureStrategy.decide(doc, InferenceSettings.DEFAULTS);
    }
  }

  @Test
  void anUntaggedDocumentIsUntagged() throws Exception {
    Decision d = decide(SyntheticPdfFactory.simpleUntagged());
    assertEquals(Kind.UNTAGGED, d.kind());
    assertTrue(d.reason().startsWith("strategy untagged: no StructTreeRoot"), d.reason());
    assertEquals("strategy untagged: no StructTreeRoot; structure is built from the page content", d.reason());
  }

  @Test
  void aFullyTaggedDocumentIsUsable() throws Exception {
    Decision d = decide(SyntheticPdfFactory.tagged());
    assertEquals(Kind.TAGGED_USABLE, d.kind(), d.reason());
    assertEquals(1.0, d.metrics().coverage());
    assertTrue(d.reason().contains("tagged text coverage 100.0 % (≥ 90 %)"), d.reason());
    assertEquals(
        "strategy tagged_usable: tagged text coverage 100.0 % (≥ 90 %), largest element holds 50.0 % of 2 MCIDs (≤ 50 %), no unmappable"
            + " structure types; the existing tags are kept",
        d.reason());
  }

  @Test
  void aWellTaggedMultiElementDocumentIsUsable() throws Exception {
    Decision d = decide(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("H1", "Title"), Block.of("P", "Body one"), Block.of("P", "Body two"), Block.of("LI", "• Item")).nested(true)));
    assertEquals(Kind.TAGGED_USABLE, d.kind(), d.reason());
    assertEquals(4, d.metrics().mcids());
    assertEquals(1, d.metrics().largestElementMcids());
  }

  @Test
  void mostTextOutsideTheTagsIsUnusable() throws Exception {
    Decision d = decide(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9));
    assertEquals(Kind.TAGGED_UNUSABLE, d.kind(), d.reason());
    assertTrue(d.reason().contains("tagged text coverage 10.0 % (< 90 %)"), d.reason());
    assertEquals(
        "strategy tagged_unusable: tagged text coverage 10.0 % (< 90 %); the existing tags are discarded on the copy and structure is built from"
            + " the page content",
        d.reason());
  }

  @Test
  void exactlyNinetyPercentIsUsable() throws Exception {
    Decision d = decide(SyntheticPdfFactory.taggedWithUntaggedLines(9, 1));
    assertEquals(Kind.TAGGED_USABLE, d.kind(), d.reason());
    assertTrue(d.reason().contains("tagged text coverage 90.0 % (≥ 90 %)"), d.reason());
  }

  @Test
  void oneElementHoldingMostMcidsIsUnusable() throws Exception {
    Decision large = decide(SyntheticPdfFactory.taggedOneLargeElement(30, 16));
    assertEquals(Kind.TAGGED_UNUSABLE, large.kind(), large.reason());
    assertTrue(large.reason().contains("one element holds 53.3 % of 30 MCIDs (> 50 %)"), large.reason());

    Decision half = decide(SyntheticPdfFactory.taggedOneLargeElement(30, 15));
    assertEquals(Kind.TAGGED_USABLE, half.kind(), half.reason());
    assertTrue(half.reason().contains("largest element holds 50.0 % of 30 MCIDs (≤ 50 %)"), half.reason());

    Decision few = decide(SyntheticPdfFactory.taggedOneLargeElement(10, 6));
    assertEquals(Kind.TAGGED_USABLE, few.kind(), "10 MCIDs are fewer than the 20 the share criterion needs: " + few.reason());
  }

  @Test
  void anUnmappableTypeIsUnusable() throws Exception {
    Decision own = decide(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("MyPara", "Body one"), Block.of("P", "Body two"))));
    assertEquals(Kind.TAGGED_UNUSABLE, own.kind(), own.reason());
    assertTrue(own.reason().contains("structure types that cannot be mapped to standard types: MyPara"), own.reason());

    Decision mapped = decide(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("MyPara", "Body one"), Block.of("P", "Body two")).roleMap(Map.of("MyPara", "P"))));
    assertEquals(Kind.TAGGED_USABLE, mapped.kind(), mapped.reason());

    Decision pdf20 = decide(SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("Strong", "Body one"), Block.of("P", "Body two"))));
    assertEquals(Kind.TAGGED_USABLE, pdf20.kind(), "a PDF 2.0 type the role-map rule maps: " + pdf20.reason());
  }

  @Test
  void aRoleMapChainCountsOnlyWhenItEndsAtAStandardType() throws Exception {
    Decision viaPdf20 = decide(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("MyPara", "Body one"), Block.of("P", "Body two")).roleMap(Map.of("MyPara", "Strong"))));
    assertEquals(Kind.TAGGED_UNUSABLE, viaPdf20.kind(), "the role-map rule never completes MyPara→Strong: " + viaPdf20.reason());
    assertTrue(viaPdf20.reason().contains("cannot be mapped to standard types: MyPara"), viaPdf20.reason());

    Decision twoSteps = decide(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("MyPara", "Body one"), Block.of("P", "Body two")).roleMap(Map.of("MyPara", "Other", "Other", "P"))));
    assertEquals(Kind.TAGGED_USABLE, twoSteps.kind(), twoSteps.reason());
  }

  @Test
  void aCyclicTreeCannotBeWalked() throws Exception {
    byte[] pdf;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged()); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      var root = doc.getDocumentCatalog().getStructureTreeRoot();
      var document = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) root.getKids().get(0);
      var heading = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) document.getKids().get(0);
      org.apache.pdfbox.cos.COSArray kids = new org.apache.pdfbox.cos.COSArray();
      kids.add(org.apache.pdfbox.cos.COSInteger.ZERO);
      kids.add(document.getCOSObject());
      heading.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.K, kids);
      doc.save(out);
      pdf = out.toByteArray();
    }
    Decision d = decide(pdf);
    assertEquals(Kind.TAGGED_UNUSABLE, d.kind(), d.reason());
    assertTrue(d.metrics().treeUnwalkable());
    assertTrue(d.reason().contains("the structure tree cannot be walked"), d.reason());
  }

  @Test
  void aTreeDeeperThan512LevelsCannotBeWalked() throws Exception {
    byte[] pdf;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged()); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      var root = doc.getDocumentCatalog().getStructureTreeRoot();
      var document = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) root.getKids().get(0);
      var parent = document;
      for (int i = 0; i < 520; i++) {
        var div = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement("Div", parent);
        parent.appendKid(div);
        parent = div;
      }
      doc.save(out);
      pdf = out.toByteArray();
    }
    Decision d = decide(pdf);
    assertEquals(Kind.TAGGED_UNUSABLE, d.kind(), d.reason());
    assertTrue(d.reason().contains("the structure tree cannot be walked"), d.reason());
  }

  @Test
  void aRoleMapCycleIsNotAMapping() throws Exception {
    Decision cyclic = decide(SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("MyPara", "Body one"), Block.of("P", "Body two")).roleMap(Map.of("MyPara", "Other", "Other", "MyPara"))));
    assertEquals(Kind.TAGGED_UNUSABLE, cyclic.kind(), cyclic.reason());
    assertTrue(cyclic.reason().contains("cannot be mapped to standard types: MyPara"), cyclic.reason());
  }

  /** A form that draws itself is counted once per path, not up to PDFBox's recursion limit. */
  @Test
  void aSelfDrawingFormIsCountedOnce() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      long taggedBefore = StructureStrategy.decide(doc, InferenceSettings.DEFAULTS).metrics().taggedGlyphs();
      var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
      form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 200, 40));
      var resources = new org.apache.pdfbox.pdmodel.PDResources();
      resources.put(org.apache.pdfbox.cos.COSName.getPDFName("F1"),
          new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA));
      resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Fm1"), form);
      form.setResources(resources);
      try (java.io.OutputStream os = form.getContentStream().createOutputStream()) {
        os.write("BT /F1 10 Tf 0 10 Td (AB) Tj ET /Fm1 Do\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      doc.getPage(0).getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("Fm1"), form);
      try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, doc.getPage(0), org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, false)) {
        cs.drawForm(form);
      }

      StructureStrategy.Metrics m = StructureStrategy.decide(doc, InferenceSettings.DEFAULTS).metrics();

      assertEquals(2, m.untaggedGlyphs(), "the form's two glyphs, once");
      assertEquals(taggedBefore, m.taggedGlyphs());
    }
  }

  @Test
  void taggedTextIsNonWhitespaceTextInsideAnMcidSequence() throws Exception {
    try (PDDocument tagged = Loader.loadPDF(SyntheticPdfFactory.tagged()); PDDocument untagged = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      assertTrue(StructureStrategy.hasTaggedText(tagged));
      assertEquals(false, StructureStrategy.hasTaggedText(untagged), "no StructTreeRoot");
    }
    for (String content : new String[] {
        "/P <</MCID 0>> BDC BT /%s 12 Tf 72 720 Td (   ) Tj ET EMC\n",
        "/P <</MCID 0>> BDC /Artifact BMC BT /%s 12 Tf 72 720 Td (Page 1) Tj ET EMC EMC\n",
        "/Span BMC BT /%s 12 Tf 72 720 Td (Not in an MCID sequence) Tj ET EMC\n"}) {
      try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
        var page = doc.getPage(0);
        String font = page.getResources().getFontNames().iterator().next().getName();
        var stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc);
        try (java.io.OutputStream os = stream.createOutputStream()) {
          os.write(content.formatted(font).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        page.setContents(stream);
        assertEquals(false, StructureStrategy.hasTaggedText(doc), content);
      }
    }
  }

  /** Re-review: /K arrays shared in a DAG (A0=[A1 A1], A1=[A2 A2], …) are walked once each, not 2^40 times. */
  @Test
  void sharedKArraysAreWalkedOnce() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      var root = doc.getDocumentCatalog().getStructureTreeRoot();
      var document = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) root.getKids().get(0);
      org.apache.pdfbox.cos.COSBase inner = document.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.K);
      for (int level = 0; level < 40; level++) {
        org.apache.pdfbox.cos.COSArray pair = new org.apache.pdfbox.cos.COSArray();
        pair.add(inner);
        pair.add(inner);
        inner = pair;
      }
      document.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.K, inner);

      Decision d = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
          () -> StructureStrategy.decide(doc, InferenceSettings.DEFAULTS));

      assertEquals(Kind.TAGGED_USABLE, d.kind(), d.reason());
      assertEquals(2, d.metrics().mcids());
    }
  }

  @Test
  void aKArrayThatContainsItselfCannotBeWalked() throws Exception {
    Decision d = decide(SyntheticPdfFactory.taggedWithSelfReferencingKArray(false));
    assertEquals(Kind.TAGGED_UNUSABLE, d.kind(), d.reason());
    assertTrue(d.metrics().treeUnwalkable());
    assertTrue(d.reason().contains("the structure tree cannot be walked"), d.reason());
  }

  @Test
  void kArraysNestedDeeperThan512LevelsCannotBeWalked() throws Exception {
    // Kept in memory: the PDF parser itself refuses to read arrays nested this deeply back in.
    Decision d;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      var root = doc.getDocumentCatalog().getStructureTreeRoot();
      var document = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) root.getKids().get(0);
      org.apache.pdfbox.cos.COSArray outer = new org.apache.pdfbox.cos.COSArray();
      org.apache.pdfbox.cos.COSArray current = outer;
      for (int i = 0; i < 520; i++) {
        org.apache.pdfbox.cos.COSArray inner = new org.apache.pdfbox.cos.COSArray();
        current.add(inner);
        current = inner;
      }
      document.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.K, outer);
      d = StructureStrategy.decide(doc, InferenceSettings.DEFAULTS);
    }
    assertEquals(Kind.TAGGED_UNUSABLE, d.kind(), d.reason());
    assertTrue(d.reason().contains("the structure tree cannot be walked"), d.reason());
  }
}
