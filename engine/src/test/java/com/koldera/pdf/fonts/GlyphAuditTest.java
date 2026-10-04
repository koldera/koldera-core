package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;

/** The glyph audit proves "only glyphs without a Unicode value gained one" and compares positions. */
class GlyphAuditTest {

  /**
   * Code 66 (B) shows {@code mapsto}: a TeX name that neither the AGL ({@code font.toUnicode}) nor
   * PDFBox's extraction glyph list ({@code additional.txt}) resolves, so the stripper extracts the raw
   * code "B" and a fill really changes the text ({@code summationtext} does not: {@link
   * #theStripperAlreadyResolvesSummationtext}).
   */
  private static final Map<Integer, String> UNMAPPED_AT_B = Map.of(66, "mapsto");

  /** A one-byte-code ToUnicode CMap with the given {@code <code> <unicode>} lines. */
  static String toUnicode(String... bfchars) {
    return cmap("<00> <FF>", bfchars);
  }

  /** A two-byte-code ToUnicode CMap (codespace {@code <0000> <FFFF>}). */
  static String toUnicode2(String... bfchars) {
    return cmap("<0000> <FFFF>", bfchars);
  }

  private static String cmap(String codespace, String... bfchars) {
    StringBuilder b = new StringBuilder();
    b.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        .append("1 begincodespacerange\n").append(codespace).append("\nendcodespacerange\n")
        .append(bfchars.length).append(" beginbfchar\n");
    for (String line : bfchars) {
      b.append(line).append('\n');
    }
    b.append("endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
    return b.toString();
  }

  private static String ch(int codePoint) {
    return new String(Character.toChars(codePoint));
  }

  private static byte[] fixture(String codes, String toUnicodeOrNull) {
    return SyntheticPdfFactory.untaggedType1WithDifferences(UNMAPPED_AT_B, codes.getBytes(US_ASCII), toUnicodeOrNull);
  }

  private static String fillOnly(byte[] before, byte[] after) throws IOException {
    try (PDDocument b = Loader.loadPDF(before); PDDocument a = Loader.loadPDF(after)) {
      return GlyphAudit.fillOnlyFailure(b, a);
    }
  }

  private static List<GlyphAudit.Glyph> capture(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return GlyphAudit.capture(doc);
    }
  }

  private static String textDigest(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(doc));
    }
  }

  @Test
  void captureListsEveryGlyphInContentOrder() throws IOException {
    List<GlyphAudit.Glyph> glyphs = capture(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(), "AB".getBytes(US_ASCII), null));
    assertEquals(2, glyphs.size());
    GlyphAudit.Glyph first = glyphs.get(0);
    GlyphAudit.Glyph second = glyphs.get(1);
    assertEquals(List.of(1, 0, "Helvetica", 65, "A"), List.of(first.page(), first.ordinal(), first.fontName(), first.code(), first.unicode()));
    assertEquals(List.of(1, 1, "Helvetica", 66, "B"), List.of(second.page(), second.ordinal(), second.fontName(), second.code(), second.unicode()));
    assertEquals(72f, first.x());
    assertEquals(700f, first.y());
    assertEquals(8.00f, first.advance(), "Helvetica A is 667/1000 em: 0.667 x 12 = 8.004, rounded to 8.00");
    assertTrue(second.x() > first.x());
  }

  @Test
  void validRejectsNulAndByteOrderMarks() {
    assertFalse(GlyphAudit.valid(null));
    assertFalse(GlyphAudit.valid(""));
    assertFalse(GlyphAudit.valid("\u0000"));
    assertFalse(GlyphAudit.valid(ch(0xFEFF)));
    assertFalse(GlyphAudit.valid("a" + ch(0xFFFE)));
    assertFalse(GlyphAudit.valid("\uD800"));
    assertTrue(GlyphAudit.valid(ch(0x2211)));
    assertTrue(GlyphAudit.valid("ff"));
  }

  @Test
  void aFillOfAnUnmappedGlyphPasses() throws IOException {
    byte[] before = fixture("AB", null);
    byte[] after = fixture("AB", toUnicode("<41> <0041>", "<42> <21A6>"));
    assertNull(capture(before).get(1).unicode(), "precondition: mapsto has no Unicode value before");
    assertNotEquals(textDigest(before), textDigest(after), "precondition: the fill changes the extracted text");
    assertNull(fillOnly(before, after));
  }

  /**
   * Measured fact the audit relies on: {@code font.toUnicode(code)} (AGL only) has no value for
   * {@code summationtext}, but PDFBox's text stripper extracts it through its own glyph list
   * ({@code additional.txt}) as U+2211 already, so a ToUnicode with that value is a fill that does
   * not change the extracted text.
   */
  @Test
  void theStripperAlreadyResolvesSummationtext() throws IOException {
    Map<Integer, String> summation = Map.of(66, "summationtext");
    byte[] before = SyntheticPdfFactory.untaggedType1WithDifferences(summation, "AB".getBytes(US_ASCII), null);
    byte[] after = SyntheticPdfFactory.untaggedType1WithDifferences(summation, "AB".getBytes(US_ASCII), toUnicode("<41> <0041>", "<42> <2211>"));
    assertNull(capture(before).get(1).unicode());
    assertEquals(textDigest(before), textDigest(after));
    assertEquals(PdfPreflight.normalizedTextDigest("A" + ch(0x2211)), textDigest(before));
    assertNull(fillOnly(before, after));
  }

  /** A glyph PDFBox already extracts (summationtext → U+2211) counts as mapped: another value is a change, not a fill. */
  @Test
  void aGlyphTheStripperAlreadyExtractsCannotBeGivenOtherText() throws IOException {
    Map<Integer, String> summation = Map.of(66, "summationtext");
    byte[] before = SyntheticPdfFactory.untaggedType1WithDifferences(summation, "AB".getBytes(US_ASCII), null);
    byte[] after = SyntheticPdfFactory.untaggedType1WithDifferences(summation, "AB".getBytes(US_ASCII), toUnicode("<41> <0041>", "<42> <21A6>"));
    assertEquals("text check: glyph 2 on page 1 (Helvetica, code 66) changed its text from \"\\u2211\" to \"\\u21a6\"", fillOnly(before, after));
  }

  /** Same glyphs, codes and values (all mapped), but a wide TJ gap makes the stripper insert a word space. */
  @Test
  void textChangedOutsideTheUnmappedGlyphsFails() throws IOException {
    byte[] before = SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(), "AB".getBytes(US_ASCII), null);
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDStream stream = new PDStream(doc);
      try (OutputStream os = stream.createOutputStream()) {
        os.write("BT /F1 12 Tf 72 700 Td [<41> -3000 <42>] TJ ET\n".getBytes(US_ASCII));
      }
      doc.getPage(0).setContents(stream);
      doc.save(out);
      after = out.toByteArray();
    }
    assertEquals("text check: the extracted text changed outside the glyphs that had no Unicode value", fillOnly(before, after));
  }

  /**
   * A composite-font glyph without text yields no text position before (PDFBox returns early for a
   * non-simple font) but one after the fill; the masked digests must still agree.
   */
  @Test
  void aType0FillOfATextlessGlyphPasses() throws IOException {
    byte[] codes = {0, 0x24, 0, 0x25, 0, 0x01};
    byte[] before = SyntheticPdfFactory.untaggedType0LiberationSans(codes, toUnicode2("<0024> <0041>", "<0025> <0042>"));
    byte[] after = SyntheticPdfFactory.untaggedType0LiberationSans(codes, toUnicode2("<0024> <0041>", "<0025> <0042>", "<0001> <2022>"));
    List<GlyphAudit.Glyph> glyphs = capture(before);
    assertEquals(3, glyphs.size());
    assertNull(glyphs.get(2).unicode(), "precondition: the .null glyph has no text before");
    assertNull(fillOnly(before, after));
  }

  @Test
  void aType0ChangeOfATextedGlyphFails() throws IOException {
    byte[] codes = {0, 0x24, 0, 0x25, 0, 0x01};
    byte[] before = SyntheticPdfFactory.untaggedType0LiberationSans(codes, toUnicode2("<0024> <0041>", "<0025> <0042>"));
    byte[] after = SyntheticPdfFactory.untaggedType0LiberationSans(codes, toUnicode2("<0024> <0058>", "<0025> <0042>", "<0001> <2022>"));
    assertEquals("text check: glyph 1 on page 1 (LiberationSans, code 36) changed its text from \"A\" to \"X\"", fillOnly(before, after));
  }

  @Test
  void aFillWithTheReplacementCharacterAloneFails() throws IOException {
    byte[] before = fixture("AB", null);
    byte[] after = fixture("AB", toUnicode("<41> <0041>", "<42> <FFFD>"));
    assertEquals("text check: glyph 2 on page 1 (Helvetica, code 66) gained an invalid Unicode value", fillOnly(before, after));
  }

  @Test
  void formGlyphsContinueThePageOrdinalAndCanBeFilled() throws IOException {
    List<GlyphAudit.Glyph> glyphs = capture(SyntheticPdfFactory.untaggedType1WithDifferencesInForm(Map.of(), null));
    assertEquals(List.of(65, 66, 67), glyphs.stream().map(GlyphAudit.Glyph::code).toList());
    assertEquals(List.of(0, 1, 2), glyphs.stream().map(GlyphAudit.Glyph::ordinal).toList());
    assertEquals(List.of(1, 1, 1), glyphs.stream().map(GlyphAudit.Glyph::page).toList());
    assertEquals(120f, glyphs.get(1).x(), "the form's glyph sits between the page's two glyphs");
    byte[] before = SyntheticPdfFactory.untaggedType1WithDifferencesInForm(UNMAPPED_AT_B, null);
    byte[] after = SyntheticPdfFactory.untaggedType1WithDifferencesInForm(UNMAPPED_AT_B, toUnicode("<42> <21A6>"));
    assertNull(fillOnly(before, after));
    byte[] changed = SyntheticPdfFactory.untaggedType1WithDifferencesInForm(UNMAPPED_AT_B, toUnicode("<42> <21A6>", "<43> <0058>"));
    assertEquals("text check: glyph 3 on page 1 (Helvetica, code 67) changed its text from \"C\" to \"X\"", fillOnly(before, changed));
  }

  @Test
  void aType3FillPasses() throws IOException {
    byte[] before = SyntheticPdfFactory.untaggedType3WithNamedGlyphs(null);
    byte[] after = SyntheticPdfFactory.untaggedType3WithNamedGlyphs(toUnicode("<41> <05D0>", "<42> <05D1>"));
    List<GlyphAudit.Glyph> glyphs = capture(before);
    assertEquals(List.of(65, 66), glyphs.stream().map(GlyphAudit.Glyph::code).toList());
    assertNull(glyphs.get(0).unicode(), "precondition: a0 has no Unicode value");
    assertNotEquals(textDigest(before), textDigest(after), "precondition: the fill changes the extracted text");
    assertNull(fillOnly(before, after));
  }

  @Test
  void changingAMappedGlyphFails() throws IOException {
    byte[] before = fixture("AB", null);
    byte[] after = fixture("AB", toUnicode("<41> <0058>", "<42> <21A6>"));
    assertEquals("text check: glyph 1 on page 1 (Helvetica, code 65) changed its text from \"A\" to \"X\"", fillOnly(before, after));
  }

  @Test
  void aNewInvalidValueFails() throws IOException {
    byte[] before = fixture("AB", null);
    byte[] after = fixture("AB", toUnicode("<41> <0041>", "<42> <0000>"));
    assertEquals("text check: glyph 2 on page 1 (Helvetica, code 66) gained an invalid Unicode value", fillOnly(before, after));
  }

  @Test
  void aDifferentGlyphSequenceFails() throws IOException {
    assertEquals("text check: the glyph sequence changed (2 glyphs before, 3 after)", fillOnly(fixture("AB", null), fixture("ABA", null)));
  }

  @Test
  void maskedDigestWithoutMaskEqualsThePreflightDigest() throws IOException {
    for (byte[] pdf : new byte[][] {SyntheticPdfFactory.untaggedArticle(), SyntheticPdfFactory.untaggedReport(3)}) {
      try (PDDocument doc = Loader.loadPDF(pdf)) {
        assertEquals(PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(doc)), GlyphAudit.maskedTextDigest(doc, Set.of()));
      }
    }
  }

  @Test
  void maskingAGlyphDropsItsText() throws IOException {
    try (PDDocument before = Loader.loadPDF(fixture("AB", null)); PDDocument after = Loader.loadPDF(fixture("AB", toUnicode("<41> <0041>", "<42> <21A6>")))) {
      Set<Long> second = Set.of((1L << 32) | 1);
      assertEquals(GlyphAudit.maskedTextDigest(before, second), GlyphAudit.maskedTextDigest(after, second));
      assertEquals(PdfPreflight.normalizedTextDigest("A"), GlyphAudit.maskedTextDigest(before, second));
      Set<Long> first = Set.of(1L << 32);
      assertNotEquals(GlyphAudit.maskedTextDigest(before, first), GlyphAudit.maskedTextDigest(after, first));
    }
  }

  @Test
  void positionsAreComparedPerGlyph() throws IOException {
    byte[] a = fixture("AB", null);
    assertNull(GlyphAudit.positionFailure(capture(a), capture(a)));
    assertEquals("glyph 1 on page 1 (Helvetica, code 65) moved", GlyphAudit.positionFailure(capture(a), capture(withContent(a, "BT /F1 12 Tf 73 700 Td <4142> Tj ET\n"))));
  }

  @Test
  void positionsAllowADifferenceOfOneHundredthPoint() throws IOException {
    byte[] a = fixture("AB", null);
    assertNull(GlyphAudit.positionFailure(capture(a), capture(withContent(a, "BT /F1 12 Tf 72.01 700 Td <4142> Tj ET\n"))));
    assertEquals("glyph 1 on page 1 (Helvetica, code 65) moved",
        GlyphAudit.positionFailure(capture(a), capture(withContent(a, "BT /F1 12 Tf 72.02 700 Td <4142> Tj ET\n"))));
  }

  private static byte[] withContent(byte[] pdf, String content) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDStream stream = new PDStream(doc);
      try (OutputStream os = stream.createOutputStream()) {
        os.write(content.getBytes(US_ASCII));
      }
      doc.getPage(0).setContents(stream);
      doc.save(out);
      return out.toByteArray();
    }
  }
}
