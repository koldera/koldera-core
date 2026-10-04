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
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.preflight.PdfPreflight;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.junit.jupiter.api.Test;

class FontEmbeddingRuleTest {

  private static final WorkerRequest FIX_UPLOAD = request(Mode.FIX, Origin.UPLOAD);
  private final FontEmbeddingRule rule = new FontEmbeddingRule();

  private WorkerResult.Action apply(PDDocument doc) {
    return rule.apply(doc, rule.inspect(doc, FIX_UPLOAD));
  }

  private static byte[] save(PDDocument doc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CopyWriter.save(doc, out);
    return out.toByteArray();
  }

  private static COSDictionary font(PDDocument doc, String name) {
    return doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName(name));
  }

  private static List<String> unicode(PDDocument doc) throws IOException {
    return GlyphAudit.capture(doc).stream().map(GlyphAudit.Glyph::unicode).toList();
  }

  private static float[] widths(COSDictionary font) {
    COSArray array = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
    float[] w = new float[array.size()];
    for (int i = 0; i < w.length; i++) {
      w[i] = ((COSNumber) array.getObject(i)).floatValue();
    }
    return w;
  }

  /** Applies the rule; returns the saved copy after asserting unicode, positions and text are unchanged. */
  private byte[] applyAndCheckText(byte[] fixture, ActionOutcome expected, String reason) throws IOException {
    List<GlyphAudit.Glyph> before;
    String textBefore;
    byte[] saved;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      before = GlyphAudit.capture(doc);
      textBefore = PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(doc));
      WorkerResult.Action action = apply(doc);
      assertEquals(expected, action.outcome(), action.reason());
      assertEquals(reason, action.reason());
      saved = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(saved)) {
      List<GlyphAudit.Glyph> after = GlyphAudit.capture(doc);
      assertEquals(before.stream().map(GlyphAudit.Glyph::unicode).toList(), after.stream().map(GlyphAudit.Glyph::unicode).toList());
      assertNull(GlyphAudit.positionFailure(before, after));
      assertEquals(textBefore, PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(doc)));
    }
    return saved;
  }

  @Test
  void aNonEmbeddedHelveticaIsEmbeddedAsLiberationSans() throws IOException {
    byte[] saved = applyAndCheckText(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0), ActionOutcome.APPLIED,
        "embedded metric-compatible substitutes for 1 fonts: Helvetica → Liberation Sans Regular");
    try (PDDocument doc = Loader.loadPDF(saved)) {
      COSDictionary f = font(doc, "F1");
      assertEquals(COSName.TRUE_TYPE, f.getCOSName(COSName.SUBTYPE));
      assertTrue(f.getNameAsString(COSName.BASE_FONT).matches("^[A-Z]{6}\\+LiberationSans$"), f.getNameAsString(COSName.BASE_FONT));
      COSDictionary descriptor = f.getCOSDictionary(COSName.FONT_DESC);
      assertEquals(f.getNameAsString(COSName.BASE_FONT), descriptor.getNameAsString(COSName.FONT_NAME));
      COSStream program = (COSStream) descriptor.getDictionaryObject(COSName.FONT_FILE2);
      assertTrue(program.getInt(COSName.LENGTH1) > 0);
      assertEquals(32, descriptor.getInt(COSName.FLAGS));
      assertFalse(descriptor.containsKey(COSName.getPDFName("CIDSet")));
      assertFalse(descriptor.containsKey(COSName.getPDFName("CharSet")));
      assertEquals(224, ((COSArray) f.getDictionaryObject(COSName.WIDTHS)).size());
      assertEquals(32, f.getInt(COSName.FIRST_CHAR));
      assertEquals(255, f.getInt(COSName.LAST_CHAR));
      assertEquals(COSName.WIN_ANSI_ENCODING, f.getDictionaryObject(COSName.ENCODING));
      PDFont loaded = doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
      assertTrue(loaded instanceof PDTrueTypeFont && loaded.isEmbedded());
      PDTrueTypeFont tt = (PDTrueTypeFont) loaded;
      for (int code : "Hello world, a test.".chars().toArray()) {
        assertTrue(tt.codeToGID(code) > 0, "glyph for code " + code);
        assertEquals(tt.getWidth(code), tt.getWidthFromFont(code), 1.0f, "width of code " + code);
      }
    }
  }

  @Test
  void aWordTrueTypeFontKeepsItsWidthsAndEncoding() throws IOException {
    byte[] fixture = SyntheticPdfFactory.untaggedNonEmbedded("TimesNewRomanPS-BoldMT", true, 0);
    float[] before;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      before = widths(font(doc, "F1"));
    }
    byte[] saved = applyAndCheckText(fixture, ActionOutcome.APPLIED,
        "embedded metric-compatible substitutes for 1 fonts: TimesNewRomanPS-BoldMT → Liberation Serif Bold");
    try (PDDocument doc = Loader.loadPDF(saved)) {
      COSDictionary f = font(doc, "F1");
      assertArrayEquals(before, widths(f));
      assertEquals(COSName.WIN_ANSI_ENCODING, f.getDictionaryObject(COSName.ENCODING));
      assertTrue(f.getNameAsString(COSName.BASE_FONT).matches("^[A-Z]{6}\\+LiberationSerif-Bold$"), f.getNameAsString(COSName.BASE_FONT));
      assertEquals(32 | 2, f.getCOSDictionary(COSName.FONT_DESC).getInt(COSName.FLAGS));
    }
  }

  @Test
  void aFontWhoseWidthsDifferByMoreThanOneUnitIsNotEmbedded() throws IOException {
    assertLeft(SyntheticPdfFactory.untaggedNonEmbedded("ArialMT", true, 5),
        "left not embedded: 1 (ArialMT: width of code 97 differs by 5.0 units from Liberation Sans Regular)");
  }

  @Test
  void fontsWithoutASubstituteAreNamed() throws IOException {
    assertLeft(SyntheticPdfFactory.untaggedNonEmbedded("Courier", false, 0), "left not embedded: 1 (Courier: no bundled metric-compatible font)");
    assertLeft(SyntheticPdfFactory.untaggedNonEmbedded("Symbol", false, 0), "left not embedded: 1 (Symbol: no bundled metric-compatible font)");
  }

  @Test
  void anInvisibleOnlyFontIsNotACandidate() throws IOException {
    assertLeft(SyntheticPdfFactory.untaggedNonEmbeddedInvisible("Helvetica"), "every font used for visible text is embedded");
  }

  @Test
  void aSymbolicTrueTypeIsLeft() throws IOException {
    assertLeft(SyntheticPdfFactory.untaggedNonEmbeddedSymbolicTrueType("ArialMT"), "left not embedded: 1 (ArialMT: symbolic font)");
  }

  @Test
  void standardEncodingBecomesWinAnsiWithDifferences() throws IOException {
    byte[] fixture = SyntheticPdfFactory.untaggedNonEmbeddedStandardEncoding("Times-Roman", "It's a test.");
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      assertEquals("’", unicode(doc).get(2), "precondition: code 39 is quoteright");
    }
    byte[] saved = applyAndCheckText(fixture, ActionOutcome.APPLIED,
        "embedded metric-compatible substitutes for 1 fonts: Times-Roman → Liberation Serif Regular");
    try (PDDocument doc = Loader.loadPDF(saved)) {
      COSDictionary encoding = (COSDictionary) font(doc, "F1").getDictionaryObject(COSName.ENCODING);
      assertEquals(COSName.WIN_ANSI_ENCODING, encoding.getDictionaryObject(COSName.BASE_ENCODING));
      COSArray differences = (COSArray) encoding.getDictionaryObject(COSName.DIFFERENCES);
      assertEquals(2, differences.size());
      assertEquals(39, ((COSNumber) differences.getObject(0)).intValue());
      assertEquals(COSName.getPDFName("quoteright"), differences.getObject(1));
      assertEquals("’", unicode(doc).get(2));
    }
  }

  /** The fixture with {@code edit} applied to its font {@code /F1} (and to the document), saved. */
  private static byte[] edited(byte[] fixture, java.util.function.BiConsumer<PDDocument, COSDictionary> edit) throws IOException {
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      edit.accept(doc, font(doc, "F1"));
      return save(doc);
    }
  }

  @Test
  void anArialDictionaryWithTheItalicFlagIsLeft() throws IOException {
    byte[] fixture = edited(SyntheticPdfFactory.untaggedNonEmbedded("Arial", true, 0),
        (doc, f) -> f.getCOSDictionary(COSName.FONT_DESC).setInt(COSName.FLAGS, 32 | 64));
    assertLeft(fixture, "left not embedded: 1 (Arial: its style flags differ from Liberation Sans Regular)");
  }

  @Test
  void aHelveticaWithAnItalicAngleIsLeft() throws IOException {
    byte[] fixture = edited(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0), (doc, f) -> {
      COSDictionary descriptor = new COSDictionary();
      descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
      descriptor.setName(COSName.FONT_NAME, "Helvetica");
      descriptor.setInt(COSName.FLAGS, 32);
      descriptor.setInt(COSName.ITALIC_ANGLE, -12);
      f.setItem(COSName.FONT_DESC, descriptor);
    });
    assertLeft(fixture, "left not embedded: 1 (Helvetica: its style flags differ from Liberation Sans Regular)");
  }

  @Test
  void aBoldWeightOnARegularSubstituteIsLeftAndARegularWeightOnABoldOne() throws IOException {
    assertLeft(edited(SyntheticPdfFactory.untaggedNonEmbedded("ArialMT", true, 0),
        (doc, f) -> f.getCOSDictionary(COSName.FONT_DESC).setInt(COSName.getPDFName("FontWeight"), 700)),
        "left not embedded: 1 (ArialMT: its style flags differ from Liberation Sans Regular)");
    assertLeft(edited(SyntheticPdfFactory.untaggedNonEmbedded("Arial-BoldMT", true, 0),
        (doc, f) -> f.getCOSDictionary(COSName.FONT_DESC).setInt(COSName.getPDFName("FontWeight"), 400)),
        "left not embedded: 1 (Arial-BoldMT: its style flags differ from Liberation Sans Bold)");
  }

  @Test
  void aNonStandardFontWithoutWidthsIsLeft() throws IOException {
    byte[] fixture = edited(SyntheticPdfFactory.untaggedNonEmbedded("Arial-Bold", true, 0), (doc, f) -> {
      f.removeItem(COSName.WIDTHS);
      f.removeItem(COSName.FIRST_CHAR);
      f.removeItem(COSName.LAST_CHAR);
    });
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      assertFalse(doc.getPage(0).getResources().getFont(COSName.getPDFName("F1")).isStandard14(), "precondition: not a standard 14 name");
    }
    assertLeft(fixture, "left not embedded: 1 (Arial-Bold: no widths in the font dictionary)");
  }

  @Test
  void aFontInTheFormDefaultResourcesIsLeft() throws IOException {
    byte[] fixture = edited(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0), (doc, f) -> {
      COSDictionary fonts = new COSDictionary();
      fonts.setItem(COSName.getPDFName("Helv"), f);
      COSDictionary dr = new COSDictionary();
      dr.setItem(COSName.FONT, fonts);
      COSDictionary acroForm = new COSDictionary();
      acroForm.setItem(COSName.FIELDS, new COSArray());
      acroForm.setItem(COSName.DR, dr);
      doc.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, acroForm);
    });
    assertLeft(fixture, "left not embedded: 1 (Helvetica: also used by form fields)");
  }

  @Test
  void subsetTaggedNamesAreShownWithoutTheTagAndOnce() throws IOException {
    byte[] fixture = edited(SyntheticPdfFactory.untaggedNonEmbedded("ArialMT", true, 0), (doc, f) -> {
      COSDictionary tagged = new COSDictionary(f);
      tagged.setName(COSName.BASE_FONT, "PGIBHG+ArialMT");
      doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), tagged);
      COSStream content = (COSStream) doc.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS);
      try {
        String text;
        try (java.io.InputStream in = content.createInputStream()) {
          text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
        }
        try (java.io.OutputStream out = content.createOutputStream()) {
          out.write((text + "BT /F2 12 Tf 72 650 Td (More text.) Tj ET\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    });
    byte[] saved = applyAndCheckText(fixture, ActionOutcome.APPLIED,
        "embedded metric-compatible substitutes for 1 fonts: ArialMT → Liberation Sans Regular");
    try (PDDocument doc = Loader.loadPDF(saved)) {
      assertTrue(font(doc, "F2").getNameAsString(COSName.BASE_FONT).matches("^[A-Z]{6}\\+LiberationSans$"));
    }
  }

  @Test
  void aFontReachedFromAPatternIsLeft() throws IOException {
    byte[] fixture;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0))) {
      // A tiling pattern whose resources reach the same font dictionary: FontUses does not see what the pattern shows.
      COSStream pattern = doc.getDocument().createCOSStream();
      pattern.setInt(COSName.PATTERN_TYPE, 1);
      pattern.setInt(COSName.PAINT_TYPE, 1);
      pattern.setInt(COSName.TILING_TYPE, 1);
      COSArray bbox = new COSArray();
      for (int v : new int[] {0, 0, 10, 10}) {
        bbox.add(org.apache.pdfbox.cos.COSInteger.get(v));
      }
      pattern.setItem(COSName.BBOX, bbox);
      pattern.setInt(COSName.X_STEP, 10);
      pattern.setInt(COSName.Y_STEP, 10);
      COSDictionary fonts = new COSDictionary();
      fonts.setItem(COSName.getPDFName("F1"), font(doc, "F1"));
      COSDictionary resources = new COSDictionary();
      resources.setItem(COSName.FONT, fonts);
      pattern.setItem(COSName.RESOURCES, resources);
      COSDictionary patterns = new COSDictionary();
      patterns.setItem(COSName.getPDFName("P1"), pattern);
      doc.getPage(0).getResources().getCOSObject().setItem(COSName.PATTERN, patterns);
      fixture = save(doc);
    }
    assertLeft(fixture, "left not embedded: 1 (Helvetica: also used in a pattern, a Type 3 glyph or a rollover or down appearance)");
  }

  @Test
  void embeddedPairsAreReadBackFromTheReason() {
    WorkerResult.Action applied = new WorkerResult.Action(WorkerResult.RuleId.PDF_FONT_EMBEDDING_V1, ActionOutcome.APPLIED,
        "embedded metric-compatible substitutes for 2 fonts: Helvetica → Liberation Sans Regular, TimesNewRoman,Bold → Liberation Serif Bold;"
            + " left not embedded: 1 (Courier: no bundled metric-compatible font)", null, null);
    List<String[]> pairs = FontEmbeddingRule.embedded(applied);
    assertEquals(2, pairs.size());
    assertArrayEquals(new String[] {"Helvetica", "Liberation Sans Regular"}, pairs.get(0));
    assertArrayEquals(new String[] {"TimesNewRoman,Bold", "Liberation Serif Bold"}, pairs.get(1));
    WorkerResult.Action skipped = new WorkerResult.Action(WorkerResult.RuleId.PDF_FONT_EMBEDDING_V1, ActionOutcome.SKIPPED,
        "every font used for visible text is embedded", null, null);
    assertTrue(FontEmbeddingRule.embedded(skipped).isEmpty());
  }

  private void assertLeft(byte[] fixture, String reason) throws IOException {
    byte[] before;
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      before = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals(reason, action.reason());
      assertArrayEquals(before, save(doc), "the document is unchanged");
    }
  }

  @Test
  void twoRunsGiveIdenticalBytes() throws IOException {
    byte[][] runs = new byte[2][];
    for (int i = 0; i < 2; i++) {
      try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedNonEmbeddedMix())) {
        assertEquals(ActionOutcome.APPLIED, apply(doc).outcome());
        runs[i] = save(doc);
      }
    }
    assertArrayEquals(runs[0], runs[1]);
  }

  @Test
  void theRuleDoesNotParticipateOutsideFixUploads() throws IOException {
    assertTrue(rule.participates(FIX_UPLOAD));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertEquals(WorkerResult.RuleId.PDF_FONT_EMBEDDING_V1, rule.id());
    WorkerRequest analyze = request(Mode.ANALYZE, Origin.UPLOAD);
    byte[] before;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0))) {
      before = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0))) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, analyze));
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertArrayEquals(before, save(doc));
    }
  }

  @Test
  void aMixOfFontsIsEmbeddedInOrderOfFirstUse() throws IOException {
    applyAndCheckText(SyntheticPdfFactory.untaggedNonEmbeddedMix(), ActionOutcome.APPLIED,
        "embedded metric-compatible substitutes for 3 fonts: Helvetica → Liberation Sans Regular, ArialMT → Liberation Sans Regular,"
            + " TimesNewRomanPSMT → Liberation Serif Regular");
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
