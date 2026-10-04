package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.OcrExtra;
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

class InvisibleTextFontRuleTest {

  private static final WorkerRequest FIX_UPLOAD = request(Mode.FIX, Origin.UPLOAD);
  private static final String LEFT = "left: HiddenHorzOCR (also used for visible text or in an annotation appearance)";
  private final InvisibleTextFontRule rule = new InvisibleTextFontRule();

  private WorkerResult.Action apply(PDDocument doc) {
    return rule.apply(doc, rule.inspect(doc, FIX_UPLOAD));
  }

  private static byte[] save(PDDocument doc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CopyWriter.save(doc, out);
    return out.toByteArray();
  }

  private static COSDictionary type0(PDDocument doc) {
    return doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F1"));
  }

  private static COSDictionary descendant(PDDocument doc) {
    return (COSDictionary) ((COSArray) type0(doc).getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
  }

  private static List<String> unicode(PDDocument doc) throws IOException {
    return GlyphAudit.capture(doc).stream().map(GlyphAudit.Glyph::unicode).toList();
  }

  @Test
  void anInvisibleNotdefFontGetsAProgramWithoutNotdef() throws IOException {
    byte[] fixture = SyntheticPdfFactory.untaggedInvisibleOcrText(false, false);
    byte[] repaired;
    List<String> unicodeBefore;
    String textBefore;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      unicodeBefore = unicode(doc);
      textBefore = PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(doc));
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("invisible text fonts given a font program without .notdef references: 1 fonts (4 glyphs on 1 pages); rendering and text unchanged",
          action.reason());
      repaired = save(doc);
    }
    assertEquals(List.of("S", "c", "a", "n"), unicodeBefore, "precondition");
    try (PDDocument doc = Loader.loadPDF(repaired)) {
      COSDictionary cidFont = descendant(doc);
      assertEquals(COSName.CID_FONT_TYPE2, cidFont.getCOSName(COSName.SUBTYPE));
      COSDictionary descriptor = cidFont.getCOSDictionary(COSName.FONT_DESC);
      assertFalse(descriptor.containsKey(COSName.FONT_FILE));
      assertFalse(descriptor.containsKey(COSName.FONT_FILE3));
      assertFalse(descriptor.containsKey(COSName.getPDFName("CIDSet")));
      COSStream program = (COSStream) descriptor.getDictionaryObject(COSName.FONT_FILE2);
      try (InputStream in = program.createInputStream()) {
        assertArrayEquals(InvisibleGlyphProgram.bytes(), in.readAllBytes());
      }
      assertEquals(InvisibleGlyphProgram.bytes().length, program.getInt(COSName.LENGTH1));
      COSStream map = (COSStream) cidFont.getDictionaryObject(COSName.CID_TO_GID_MAP);
      try (InputStream in = map.createInputStream()) {
        byte[] entries = in.readAllBytes();
        assertEquals(2 * (0x6E + 1), entries.length);
        for (int i = 0; i < entries.length; i += 2) {
          assertEquals(0, entries[i]);
          assertEquals(1, entries[i + 1]);
        }
      }
      assertEquals(500, cidFont.getInt(COSName.DW));
      assertTrue(type0(doc).getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream, "ToUnicode unchanged");
      PDType0Font font = (PDType0Font) doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
      for (int code : new int[] {0x53, 0x63, 0x61, 0x6E}) {
        assertEquals(1, font.getDescendantFont().codeToGID(code), "code " + code);
      }
      assertEquals(unicodeBefore, unicode(doc), "the glyphs' text is unchanged");
      assertEquals(textBefore, PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(doc)));
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      assertNull(RenderIdentity.changes(doc, List.of(1), d -> rule.apply(d, rule.inspect(d, FIX_UPLOAD))));
    }
  }

  @Test
  void aTrueTypeInvisibleNotdefFontGetsTheSameRepair() throws IOException {
    byte[] fixture = SyntheticPdfFactory.untaggedInvisibleOcrTextTrueType();
    byte[] repaired;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      PDType0Font before = (PDType0Font) doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
      assertEquals(0, before.getDescendantFont().codeToGID(0x53), "precondition: the CIDToGIDMap sends the code to .notdef");
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      repaired = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(repaired)) {
      PDType0Font font = (PDType0Font) doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
      for (int code : new int[] {0x53, 0x63, 0x61, 0x6E}) {
        assertEquals(1, font.getDescendantFont().codeToGID(code), "code " + code);
      }
      assertEquals(List.of("S", "c", "a", "n"), unicode(doc));
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      assertNull(RenderIdentity.changes(doc, List.of(1), d -> rule.apply(d, rule.inspect(d, FIX_UPLOAD))));
    }
  }

  @Test
  void aFontAlsoUsedForVisibleTextIsLeft() throws IOException {
    assertLeft(SyntheticPdfFactory.untaggedInvisibleOcrText(true, false));
  }

  @Test
  void aFontUsedInAnAnnotationAppearanceIsLeft() throws IOException {
    assertLeft(SyntheticPdfFactory.untaggedInvisibleOcrText(false, true));
  }

  private void assertLeft(byte[] fixture) throws IOException {
    byte[] before;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      before = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals(LEFT, action.reason());
      assertEquals(COSName.CID_FONT_TYPE0, descendant(doc).getCOSName(COSName.SUBTYPE));
      assertArrayEquals(before, save(doc), "the font is unchanged");
    }
  }

  @Test
  void aDescendantSharedWithAVisibleFontIsLeft() throws IOException {
    byte[] fixture;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedInvisibleOcrText(false, false))) {
      // A second Type 0 font /F2 over the same descendant, shown visibly.
      COSDictionary f2 = new COSDictionary(type0(doc));
      doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), f2);
      COSStream content = (COSStream) doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS);
      String text;
      try (InputStream in = content.createInputStream()) {
        text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
      }
      try (java.io.OutputStream out = content.createOutputStream()) {
        out.write((text + "BT 0 Tr /F2 12 Tf 72 650 Td <0053> Tj ET\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      fixture = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals(LEFT, action.reason());
      assertEquals(COSName.CID_FONT_TYPE0, descendant(doc).getCOSName(COSName.SUBTYPE));
    }
  }

  private static final String REACHED =
      "left: HiddenHorzOCR (also used in a pattern, a Type 3 glyph or a rollover or down appearance)";

  private void assertLeftWith(OcrExtra extra, String reason) throws IOException {
    byte[] fixture = SyntheticPdfFactory.untaggedInvisibleOcrText(extra);
    byte[] before;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      before = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals(reason, action.reason());
      assertArrayEquals(before, save(doc), "the font is unchanged");
    }
  }

  @Test
  void aFontShownInsideATilingPatternIsLeft() throws IOException {
    assertLeftWith(OcrExtra.PATTERN_PAGE_2, REACHED);
  }

  @Test
  void aFontShownInsideAType3GlyphIsLeft() throws IOException {
    assertLeftWith(OcrExtra.TYPE3_PAGE_2, REACHED);
  }

  @Test
  void aFontShownInARolloverAppearanceIsLeft() throws IOException {
    assertLeftWith(OcrExtra.ROLLOVER_APPEARANCE, REACHED);
  }

  @Test
  void aFontAlsoShownAsClipTextIsLeft() throws IOException {
    assertLeftWith(OcrExtra.CLIP, LEFT);
  }

  @Test
  void everyPageWhoseResourcesReachTheFontIsCompared() throws IOException {
    List<List<Integer>> compared = new java.util.ArrayList<>();
    InvisibleTextFontRule recording = new InvisibleTextFontRule((d, pages, m) -> {
      compared.add(pages);
      return RenderIdentity.changes(d, pages, m);
    });
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedInvisibleOcrText(OcrExtra.UNUSED_ON_PAGE_2))) {
      WorkerResult.Action action = recording.apply(doc, recording.inspect(doc, FIX_UPLOAD));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains("(4 glyphs on 1 pages)"), action.reason());
    }
    assertEquals(List.of(List.of(1, 2)), compared, "page 2 lists the font in its resources, so it is rendered too");
  }

  @Test
  void aRenderingDifferenceLeavesTheDocumentAsItIs() throws IOException {
    InvisibleTextFontRule failing = new InvisibleTextFontRule((d, pages, m) -> "the rendering of page 1");
    byte[] fixture = SyntheticPdfFactory.untaggedInvisibleOcrText(false, false);
    byte[] before;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      before = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = failing.apply(doc, failing.inspect(doc, FIX_UPLOAD));
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals("rendering would change on page 1; left as it is", action.reason());
      assertArrayEquals(before, save(doc));
    }
  }

  @Test
  void aDescriptorSharedThroughAnotherDescendantIsLeft() throws IOException {
    byte[] fixture;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedInvisibleOcrText(false, false))) {
      // /F2: another Type 0 font whose own descendant points to the same descriptor; listed, never shown.
      COSDictionary otherDescendant = new COSDictionary(descendant(doc));
      COSDictionary f2 = new COSDictionary(type0(doc));
      f2.setName(COSName.BASE_FONT, "OtherOCR");
      f2.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(otherDescendant)));
      doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), f2);
      fixture = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals("left: HiddenHorzOCR (shares its font data with another font)", action.reason(), "the unused font is not called visible");
      assertEquals(COSName.CID_FONT_TYPE0, descendant(doc).getCOSName(COSName.SUBTYPE));
    }
  }

  @Test
  void twoTargetsShareOneProgramStream() throws IOException {
    byte[] fixture;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedInvisibleOcrText(false, false))) {
      // /F2: a second invisible OCR font with its own descendant and descriptor, shown in mode 3 too.
      COSDictionary otherDescendant = new COSDictionary(descendant(doc));
      otherDescendant.setItem(COSName.FONT_DESC, new COSDictionary(descendant(doc).getCOSDictionary(COSName.FONT_DESC)));
      COSDictionary f2 = new COSDictionary(type0(doc));
      f2.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(otherDescendant)));
      doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), f2);
      COSStream content = (COSStream) doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS);
      String text;
      try (InputStream in = content.createInputStream()) {
        text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
      }
      try (java.io.OutputStream out = content.createOutputStream()) {
        out.write((text + "BT 3 Tr /F2 12 Tf 72 650 Td <0053> Tj ET\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      fixture = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().contains(": 2 fonts (5 glyphs on 1 pages)"), action.reason());
      COSDictionary f2 = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F2"));
      COSDictionary d2 = (COSDictionary) ((COSArray) f2.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
      assertTrue(descendant(doc).getCOSDictionary(COSName.FONT_DESC).getDictionaryObject(COSName.FONT_FILE2)
          == d2.getCOSDictionary(COSName.FONT_DESC).getDictionaryObject(COSName.FONT_FILE2), "one program stream for every repaired font");
    }
  }

  @Test
  void aVisibleNotdefFontIsNeverTouched() throws IOException {
    byte[] before;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedVisibleNotdefText())) {
      before = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedVisibleNotdefText())) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals("no invisible-text font references .notdef", action.reason());
      assertArrayEquals(before, save(doc));
    }
  }

  @Test
  void theRuleDoesNotParticipateOutsideFixUploads() {
    assertTrue(rule.participates(FIX_UPLOAD));
    assertFalse(rule.participates(request(Mode.SAFE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertEquals(WorkerResult.RuleId.PDF_FONT_INVISIBLE_TEXT_V1, rule.id());
  }

  @Test
  void twoRunsGiveIdenticalBytes() throws IOException {
    byte[][] runs = new byte[2][];
    for (int i = 0; i < 2; i++) {
      try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedInvisibleOcrText(false, false))) {
        assertEquals(ActionOutcome.APPLIED, apply(doc).outcome());
        runs[i] = save(doc);
      }
    }
    assertArrayEquals(runs[0], runs[1]);
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
