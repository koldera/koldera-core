package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class UntaggedArtifactsTest {

  private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

  @Test
  void paintingOutsideMarkedContentBecomesArtifactTextAndTaggedContentStayAsTheyAre() throws IOException {
    try (PDDocument doc = Loader.loadPDF(page(false))) {
      String textBefore = new PDFTextStripper().getText(doc);

      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc);
      assertEquals(4, outcome.painting(), "rectangle, image, inline image on the page + the path in the untagged form");
      assertEquals(1, outcome.text(), "the untagged text object");
      assertEquals("Untagged text", outcome.textSample());

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        PDPage page = after.getPage(0);
        List<String> painted = painted(new PDFStreamParser(page).parse());
        assertEquals(List.of("f:P", "Tj:P", "f:Artifact", "Do:Artifact", "BI:Artifact", "Tj:Artifact", "Do:-", "Do:Span"), painted,
            "tagged content untouched, untagged painting and text wrapped, forms drawn as before");
        PDFormXObject outsideForm = (PDFormXObject) page.getResources().getXObject(COSName.getPDFName("Fout"));
        assertEquals(List.of("f:Artifact"), painted(new PDFStreamParser(outsideForm).parse()), "a form drawn outside marked content is wrapped inside");
        PDFormXObject insideForm = (PDFormXObject) page.getResources().getXObject(COSName.getPDFName("Fin"));
        assertEquals(List.of("f:-"), painted(new PDFStreamParser(insideForm).parse()), "a form drawn inside a tagged sequence is left alone");
        assertEquals(textBefore, new PDFTextStripper().getText(after), "extracted text unchanged");
        assertBalanced(new PDFStreamParser(page).parse());

        assertEquals(new UntaggedArtifacts.Outcome(0, 0, null), UntaggedArtifacts.apply(after), "idempotent: nothing left outside marked content");
      }
    }
  }

  @Test
  void aStreamWithUnbalancedMarkedContentIsNotRewritten() throws IOException {
    try (PDDocument doc = Loader.loadPDF(page(true))) {
      COSDictionary contentsBefore = doc.getPage(0).getCOSObject();
      Object before = contentsBefore.getDictionaryObject(COSName.CONTENTS);

      assertEquals(new UntaggedArtifacts.Outcome(1, 0, null), UntaggedArtifacts.apply(doc), "only the balanced form is rewritten, the unbalanced page (and its text) is not");

      assertEquals(before, doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS), "page stream untouched");
    }
  }

  @Test
  void aGeneratedFixtureWithoutUntaggedPaintIsNotRewritten() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-tables.pdf")) {
      Object before = doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS);
      assertEquals(new UntaggedArtifacts.Outcome(0, 0, null), UntaggedArtifacts.apply(doc), "Chromium tagged or marked everything in this fixture");
      assertEquals(before, doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS), "no rewrite when nothing changed");
    }
  }

  @Test
  void theDecorativeImagesInsideLinksOfTheLinksFixtureBecomeArtifacts() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-links.pdf")) {
      String textBefore = new PDFTextStripper().getText(doc);
      assertEquals(new UntaggedArtifacts.Outcome(2, 0, null), UntaggedArtifacts.apply(doc), "the settings and mailto icons' alt=\"\" images, no untagged text");
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(textBefore, new PDFTextStripper().getText(after));
        assertBalanced(new PDFStreamParser(after.getPage(0)).parse());
      }
    }
  }

  @Test
  void untaggedTextShownInsideATextObjectThatAlsoHoldsTaggedTextIsWrappedAsOneRunWithoutSplittingTheObject() throws IOException {
    String content = "BT /F1 12 Tf 72 700 Td /P <</MCID 0>> BDC (Tagged) Tj EMC ( one) Tj 0 -14 Td [( two) -250 (three)] TJ /P <</MCID 1>> BDC (Tail) Tj EMC ET";
    try (PDDocument doc = Loader.loadPDF(streamPage(content))) {
      int[] before = render(doc, 0);
      String textBefore = new PDFTextStripper().getText(doc);

      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc);

      assertEquals(1, outcome.text(), "the two untagged operators between the tagged ones are one run");
      assertEquals("one twothree", outcome.textSample(), "the painted glyphs of the run, whitespace collapsed; a TJ adjustment is not a space");
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<Object> tokens = new PDFStreamParser(after.getPage(0)).parse();
        assertEquals(List.of("BT", "Tf", "Td", "BDC", "Tj", "EMC", "BMC", "Tj", "Td", "TJ", "EMC", "BDC", "Tj", "EMC", "ET"), operators(tokens),
            "Artifact BMC/EMC inside the one text object, around the untagged run only");
        assertEquals(List.of("Tj:P", "Tj:Artifact", "TJ:Artifact", "Tj:P"), painted(tokens));
        assertEquals(textBefore, new PDFTextStripper().getText(after), "extracted text unchanged");
        assertArrayEquals(before, render(after, 0), "rendering unchanged");
        assertBalanced(tokens);
      }
    }
  }

  @Test
  void anUntaggedSequenceWithoutAnMcidIsWrappedWholeFromOutsideSoItsActualTextStillCoversTheSameGlyphs() throws IOException {
    // Chromium wraps a no-break space as /Span <</ActualText>> BDC without an MCID outside any tagged
    // sequence, and Hebrew runs in /ReversedChars BMC: neither makes content tagged.
    String content = "BT /F1 12 Tf 72 700 Td /Span <</ActualText (x)>> BDC ( ) Tj EMC ET /ReversedChars BMC BT /F1 12 Tf 72 680 Td (cba) Tj ET 0 0 5 5 re f EMC"
        + " /P <</MCID 0>> BDC /Span <</ActualText (y)>> BDC BT /F1 12 Tf 72 660 Td (tagged) Tj ET EMC EMC";
    try (PDDocument doc = Loader.loadPDF(streamPage(content))) {
      int[] before = render(doc, 0);
      String textBefore = new PDFTextStripper().getText(doc);

      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc);

      assertEquals(textBefore, new PDFTextStripper().getText(doc), "extracted text (which honours /ActualText) unchanged");
      assertEquals(new UntaggedArtifacts.Outcome(0, 2, "cba"), outcome, "each MCID-less sequence is one run; the one inside the MCID sequence is left");
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<Object> tokens = new PDFStreamParser(after.getPage(0)).parse();
        assertEquals(
            List.of("BT", "Tf", "Td", "BMC", "BDC", "Tj", "EMC", "EMC", "ET", "BMC", "BMC", "BT", "Tf", "Td", "Tj", "ET", "re", "f", "EMC", "EMC",
                "BDC", "BDC", "BT", "Tf", "Td", "Tj", "ET", "EMC", "EMC"),
            operators(tokens), "the whole MCID-less sequence is wrapped from outside, so its /ActualText still covers the same glyphs");
        assertBalanced(tokens);
        assertArrayEquals(before, render(after, 0));
      }
    }
  }

  @Test
  void anMcidLessSequenceThatHoldsTaggedContentIsNeverWrapped() throws IOException {
    String content = "/Div BMC BT /F1 12 Tf 72 700 Td ( ) Tj /P <</MCID 0>> BDC (tagged) Tj EMC ET EMC";
    try (PDDocument doc = Loader.loadPDF(streamPage(content))) {
      Object before = doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS);
      assertEquals(new UntaggedArtifacts.Outcome(0, 0, null), UntaggedArtifacts.apply(doc), "an Artifact around it would hide the tagged text");
      assertEquals(before, doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS));
    }
  }

  @Test
  void textInAFormDrawnOutsideMarkedContentIsWrappedInsideTheForm() throws IOException {
    try (PDDocument doc = Loader.loadPDF(streamPage("/Fx Do"))) {
      PDFormXObject form = new PDFormXObject(new PDStream(doc));
      form.setBBox(new PDRectangle(0, 0, 200, 200));
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), FONT);
      form.setResources(resources);
      try (var out = form.getContentStream().createOutputStream()) {
        out.write("BT /F1 10 Tf 5 5 Td (Running footer) Tj ET\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      doc.getPage(0).getResources().put(COSName.getPDFName("Fx"), form);

      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc);

      assertEquals(new UntaggedArtifacts.Outcome(0, 1, "Running footer"), outcome);
      assertEquals(List.of("Tj:Artifact"), painted(new PDFStreamParser(form).parse()));
    }
  }

  @Test
  void theSampleFromFormsFollowsDrawOrderSoEveryRunGivesTheSameReason() throws IOException {
    for (int run = 0; run < 25; run++) {
      try (PDDocument doc = Loader.loadPDF(streamPage("/Fa Do /Fb Do"))) {
        for (String[] named : new String[][] {{"Fa", "Drawn first"}, {"Fb", "Drawn second"}}) {
          PDFormXObject form = new PDFormXObject(new PDStream(doc));
          form.setBBox(new PDRectangle(0, 0, 200, 200));
          PDResources resources = new PDResources();
          resources.put(COSName.getPDFName("F1"), FONT);
          form.setResources(resources);
          try (var out = form.getContentStream().createOutputStream()) {
            out.write(("BT /F1 10 Tf 5 5 Td (" + named[1] + ") Tj ET\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          }
          doc.getPage(0).getResources().put(COSName.getPDFName(named[0]), form);
        }

        assertEquals(new UntaggedArtifacts.Outcome(0, 2, "Drawn first"), UntaggedArtifacts.apply(doc), "run " + run);
      }
    }
  }

  @Test
  void theSampleIsTheFirstNonBlankRunWhitespaceCollapsedAndAtMostEightyCharacters() throws IOException {
    String longText = "x".repeat(120);
    String content = "BT /F1 12 Tf 72 700 Td ( ) Tj ET /P <</MCID 0>> BDC BT /F1 12 Tf 72 680 Td (Tagged) Tj ET EMC BT /F1 12 Tf 72 660 Td (" + longText + ") Tj ET";
    try (PDDocument doc = Loader.loadPDF(streamPage(content))) {
      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc);
      assertEquals(2, outcome.text(), "a whitespace-only run counts too");
      assertEquals("x".repeat(79) + "\u2026", outcome.textSample());
      assertEquals(80, outcome.textSample().length());
    }
  }

  @Test
  void theSampleHelperCollapsesWhitespaceAndGivesNothingForBlankOrUndecodableText() {
    assertEquals(null, UntaggedArtifacts.sample(null));
    assertEquals(null, UntaggedArtifacts.sample("  \n "));
    assertEquals("a b", UntaggedArtifacts.sample(" a \n  b "));
    assertEquals("ab c", UntaggedArtifacts.sample("a\u0000b​\u0007 c­"), "control (Cc) and format (Cf) code points are dropped: PostgreSQL rejects U+0000");
    assertEquals(null, UntaggedArtifacts.sample("\u0000﻿"), "nothing left after dropping them");
    String astral ="x".repeat(78) + "😀" + "tail"; // an emoji straddles the cut
    assertEquals("x".repeat(78) + "…", UntaggedArtifacts.sample(astral), "never half a surrogate pair");
  }

  @Test
  void everyUntaggedTextRunOfTheUntaggedTextFixtureBecomesArtifactAndThePagesRenderIdentically() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-untagged-text.pdf")) {
      String textBefore = new PDFTextStripper().getText(doc);
      List<int[]> before = new ArrayList<>();
      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        before.add(render(doc, i));
        assertTrue(painted(new PDFStreamParser(doc.getPage(i)).parse()).contains("Tj:-"), "fixture page " + (i + 1) + " paints untagged text");
      }

      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc);

      assertTrue(outcome.text() >= 10, "running header, footer counters, CSS counters, <q> quotes, aria-hidden text: " + outcome);
      assertEquals("Example Co.", outcome.textSample(), "the @page top-center box is painted first");
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(textBefore, new PDFTextStripper().getText(after), "extracted text unchanged");
        for (int i = 0; i < after.getNumberOfPages(); i++) {
          List<Object> tokens = new PDFStreamParser(after.getPage(i)).parse();
          assertFalse(painted(tokens).contains("Tj:-") || painted(tokens).contains("TJ:-"), "page " + (i + 1) + ": no text outside marked content");
          assertBalanced(tokens);
          assertArrayEquals(before.get(i), render(after, i), "page " + (i + 1) + " raster unchanged");
        }
      }
    }
  }

  @Test
  void paintingOnlyNeverWrapsText() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-untagged-text.pdf")) {
      String textBefore = new PDFTextStripper().getText(doc);
      int untaggedPaintingBefore = 0;
      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        List<String> painted = painted(new PDFStreamParser(doc.getPage(i)).parse());
        untaggedPaintingBefore += (int) painted.stream().filter(p -> p.equals("f:-") || p.equals("S:-") || p.equals("BI:-")).count();
      }

      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc, UntaggedArtifacts.Scope.PAINTING_ONLY);

      assertEquals(0, outcome.text(), "no text is ever wrapped: " + outcome);
      assertEquals(null, outcome.textSample());
      assertTrue(outcome.textLeft() > 0, "the untagged text runs are counted: " + outcome);
      assertTrue(outcome.painting() > 0 || untaggedPaintingBefore == 0, "painting is wrapped: " + outcome);
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertEquals(textBefore, new PDFTextStripper().getText(after), "extracted text unchanged");
        boolean textStillUntagged = false;
        for (int i = 0; i < after.getNumberOfPages(); i++) {
          List<Object> tokens = new PDFStreamParser(after.getPage(i)).parse();
          List<String> painted = painted(tokens);
          textStillUntagged |= painted.contains("Tj:-") || painted.contains("TJ:-");
          assertFalse(painted.contains("f:-") || painted.contains("S:-") || painted.contains("BI:-"), "page " + (i + 1) + ": no painting outside marked content");
          assertBalanced(tokens);
        }
        assertTrue(textStillUntagged, "the untagged text runs stay unwrapped");
      }
    }
  }

  @Test
  void paintingOnlyLeavesAnUntaggedSequenceWithTextAndPaintingWholeAndCountsItAsText() throws IOException {
    String content = "/Span <</ActualText (x)>> BDC BT /F1 12 Tf 72 700 Td (Mixed) Tj ET 72 600 m 500 600 l S EMC 100 100 50 50 re f";
    try (PDDocument doc = Loader.loadPDF(streamPage(content))) {
      UntaggedArtifacts.Outcome outcome = UntaggedArtifacts.apply(doc, UntaggedArtifacts.Scope.PAINTING_ONLY);

      assertEquals(new UntaggedArtifacts.Outcome(1, 0, null, 1), outcome, "the rectangle is wrapped; the Span (text + line) is left whole, counted as text");
      assertEquals(List.of("Tj:Span", "S:Span", "f:Artifact"), painted(new PDFStreamParser(doc.getPage(0)).parse()));
    }
  }

  @Test
  void theGeneratedScopeIsTheDefaultAndReportsNoTextLeft() throws IOException {
    try (PDDocument a = GeneratedStructure.fixture("generated-untagged-text.pdf"); PDDocument b = GeneratedStructure.fixture("generated-untagged-text.pdf")) {
      UntaggedArtifacts.Outcome byDefault = UntaggedArtifacts.apply(a);
      assertEquals(byDefault, UntaggedArtifacts.apply(b, UntaggedArtifacts.Scope.GENERATED));
      assertEquals(0, byDefault.textLeft());
      for (int i = 0; i < a.getNumberOfPages(); i++) {
        assertArrayEquals(a.getPage(i).getContents().readAllBytes(), b.getPage(i).getContents().readAllBytes(), "page " + (i + 1));
      }
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static List<String> operators(List<Object> tokens) {
    List<String> out = new ArrayList<>();
    for (Object token : tokens) {
      if (token instanceof Operator op) {
        out.add(op.getName());
      }
    }
    return out;
  }

  /** One A4 page whose content stream is {@code content}, with Helvetica as /F1. */
  static byte[] streamPage(String content) throws IOException {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), FONT);
      page.setResources(resources);
      PDStream stream = new PDStream(doc);
      try (var os = stream.createOutputStream()) {
        os.write(content.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
      }
      page.setContents(stream);
      doc.save(out);
      return out.toByteArray();
    }
  }

  static int[] render(PDDocument doc, int pageIndex) throws IOException {
    BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, 72);
    BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    rgb.getGraphics().drawImage(image, 0, 0, null);
    return ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData().clone();
  }

  /** Each painting/text/Do operator with the innermost marked-content tag around it ({@code -} for none). */
  private static List<String> painted(List<Object> tokens) {
    Deque<String> tags = new ArrayDeque<>();
    List<Object> operands = new ArrayList<>();
    List<String> out = new ArrayList<>();
    for (Object token : tokens) {
      if (!(token instanceof Operator op)) {
        operands.add(token);
        continue;
      }
      switch (op.getName()) {
        case "BMC", "BDC" -> tags.push(((COSName) operands.get(0)).getName());
        case "EMC" -> tags.pop();
        case "f", "S", "Tj", "TJ", "Do", "BI" -> out.add(op.getName() + ":" + (tags.isEmpty() ? "-" : tags.peek()));
        default -> { }
      }
      operands.clear();
    }
    return out;
  }

  /** Every BMC/BDC has its EMC and q/Q never cross an Artifact marker. */
  private static void assertBalanced(List<Object> tokens) {
    Deque<Integer> saveDepthAtOpen = new ArrayDeque<>();
    Deque<String> tags = new ArrayDeque<>();
    List<Object> operands = new ArrayList<>();
    int q = 0;
    for (Object token : tokens) {
      if (!(token instanceof Operator op)) {
        operands.add(token);
        continue;
      }
      switch (op.getName()) {
        case "q" -> q++;
        case "Q" -> q--;
        case "BMC", "BDC" -> {
          tags.push(((COSName) operands.get(0)).getName());
          saveDepthAtOpen.push(q);
        }
        case "EMC" -> {
          int open = saveDepthAtOpen.pop();
          if ("Artifact".equals(tags.pop())) {
            assertEquals(open, q, "an Artifact marker never splits a q/Q pair");
          }
        }
        default -> { }
      }
      operands.clear();
    }
    assertEquals(0, tags.size(), "all marked content closed");
  }

  /** One page: tagged P (path + text), then untagged rect, image, inline image, text, a form drawn outside and one drawn inside a Span. */
  private static byte[] page(boolean unbalanced) throws IOException {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      page.setResources(new PDResources());
      BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
      PDImageXObject image = LosslessFactory.createFromImage(doc, pixels);
      PDFormXObject outsideForm = form(doc);
      PDFormXObject insideForm = form(doc);
      page.getResources().put(COSName.getPDFName("Fout"), outsideForm);
      page.getResources().put(COSName.getPDFName("Fin"), insideForm);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        COSDictionary props = new COSDictionary();
        props.setInt(COSName.MCID, 0);
        cs.beginMarkedContent(COSName.P, PDPropertyList.create(props));
        cs.addRect(72, 700, 50, 2);
        cs.fill();
        cs.beginText();
        cs.setFont(FONT, 12);
        cs.newLineAtOffset(72, 720);
        cs.showText("Tagged text");
        cs.endText();
        cs.endMarkedContent();

        cs.saveGraphicsState();
        cs.addRect(72, 600, 50, 50);
        cs.fill();
        cs.restoreGraphicsState();
        cs.drawImage(image, 200, 600, 20, 20);
        cs.drawImage(new PDInlineImage(inlineImageParameters(), new byte[] {(byte) 0xFF}, page.getResources()), 250, 600, 10, 10);
        cs.beginText();
        cs.setFont(FONT, 12);
        cs.newLineAtOffset(72, 500);
        cs.showText("Untagged text");
        cs.endText();
        cs.drawForm(outsideForm);
        COSDictionary spanProps = new COSDictionary();
        spanProps.setInt(COSName.MCID, 1);
        cs.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(spanProps));
        cs.drawForm(insideForm);
        cs.endMarkedContent();
        if (unbalanced) {
          cs.endMarkedContent();
        }
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static COSDictionary inlineImageParameters() {
    COSDictionary params = new COSDictionary();
    params.setInt(COSName.W, 1);
    params.setInt(COSName.H, 1);
    params.setInt(COSName.BPC, 8);
    params.setItem(COSName.CS, COSName.G);
    return params;
  }

  private static PDFormXObject form(PDDocument doc) throws IOException {
    PDFormXObject form = new PDFormXObject(new PDStream(doc));
    form.setBBox(new PDRectangle(0, 0, 100, 100));
    try (var out = form.getContentStream().createOutputStream()) {
      out.write("0 0 10 10 re f\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    return form;
  }
}
