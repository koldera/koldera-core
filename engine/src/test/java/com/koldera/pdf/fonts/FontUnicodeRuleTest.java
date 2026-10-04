package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class FontUnicodeRuleTest {

  private static final WorkerRequest FIX_UPLOAD = request(Mode.FIX, Origin.UPLOAD);
  private final FontUnicodeRule rule = new FontUnicodeRule();

  private static byte[] texFixture() {
    return SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(80, "summationtext", 81, "f_f", 82, "grave.ts1"), "APQR".getBytes(US_ASCII), null);
  }

  private WorkerResult.Action apply(PDDocument doc) {
    return rule.apply(doc, rule.inspect(doc, FIX_UPLOAD));
  }

  private static COSDictionary font(PDDocument doc, String name) throws IOException {
    return doc.getPage(0).getResources().getFont(COSName.getPDFName(name)).getCOSObject();
  }

  private static CMap toUnicode(COSDictionary font) throws IOException {
    COSBase base = font.getDictionaryObject(COSName.TO_UNICODE);
    assertTrue(base instanceof COSStream, "a ToUnicode stream");
    try (var in = ((COSStream) base).createInputStream()) {
      return new CMapParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
    }
  }

  private static byte[] save(PDDocument doc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CopyWriter.save(doc, out);
    return out.toByteArray();
  }

  private static String fillOnly(byte[] before, byte[] after) throws IOException {
    try (PDDocument b = Loader.loadPDF(before); PDDocument a = Loader.loadPDF(after)) {
      return GlyphAudit.fillOnlyFailure(b, a);
    }
  }

  @Test
  void aTexNameAndALigatureNameAreFilled() throws IOException {
    byte[] before = texFixture();
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("ToUnicode written for 1 fonts (4 codes: 2 kept from the existing text, 1 from standard glyph names, 1 from TeX glyph names,"
          + " 0 from the font program, 0 from the standard Macintosh glyph order); text changed for 1 codes (1 from standard glyph names, 0 from TeX glyph names,"
          + " 0 from the font program, 0 from the standard Macintosh glyph order)", action.reason(), "summationtext already extracts as 2211: only f_f changes the text");
      CMap cmap = toUnicode(font(doc, "F1"));
      assertEquals("A", cmap.toUnicode(65, 1));
      assertEquals("∑", cmap.toUnicode(80, 1));
      assertEquals("ff", cmap.toUnicode(81, 1));
      assertEquals("`", cmap.toUnicode(82, 1));
      assertEquals(Optional.empty(), FontUnicodeRule.unmapped(doc));
      SortedMap<GlyphNames.Source, Integer> derived = new TreeMap<>(Map.of(GlyphNames.Source.AGL, 1));
      assertEquals(Optional.of(derived), FontUnicodeRule.derived(action));
      after = save(doc);
    }
    assertNull(fillOnly(before, after));
  }

  @Test
  void mappedGlyphsKeepTheirTextWhenAToUnicodeIsWritten() throws IOException {
    byte[] before = SyntheticPdfFactory.untaggedStandard14WithoutEncoding();
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("ToUnicode written for 1 fonts (2 codes: 2 kept from the existing text, 0 from standard glyph names, 0 from TeX glyph names,"
          + " 0 from the font program, 0 from the standard Macintosh glyph order); text changed for 0 codes", action.reason());
      assertEquals(Optional.empty(), FontUnicodeRule.derived(action), "no text changed");
      after = save(doc);
    }
    List<GlyphAudit.Glyph> was;
    List<GlyphAudit.Glyph> now;
    try (PDDocument b = Loader.loadPDF(before); PDDocument a = Loader.loadPDF(after)) {
      was = GlyphAudit.capture(b);
      now = GlyphAudit.capture(a);
      assertEquals(PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(b)), PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(a)));
    }
    assertEquals(2, was.size());
    for (int i = 0; i < was.size(); i++) {
      assertEquals(was.get(i).unicode(), now.get(i).unicode(), "glyph " + i);
    }
  }

  @Test
  void aWellEncodedFontIsLeftAlone() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(), "AB".getBytes(US_ASCII), null))) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("every font used for text maps its codes to Unicode", action.reason());
      assertNull(font(doc, "F1").getDictionaryObject(COSName.TO_UNICODE));
      assertEquals(Optional.empty(), FontUnicodeRule.unmapped(doc));
    }
  }

  @Test
  void aTypeThreeFontWithMeaninglessNamesStaysUnmapped() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedType3WithMeaninglessNames())) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("no Unicode source for the unmapped codes; 2 codes in 1 fonts stay unmapped: Type 3 font", action.reason());
      assertEquals(Optional.of(new FontUnicodeRule.Unmapped(2, 1, List.of("Type 3 font"))), FontUnicodeRule.unmapped(doc));
      assertNull(font(doc, "F1").getDictionaryObject(COSName.TO_UNICODE));
    }
  }

  @Test
  void aWordStyleSymbolicSubsetIsMappedFromTheMacOrder() throws IOException {
    byte[] before = SyntheticPdfFactory.symbolicTrueTypeWithoutUnicodeTables("Hello", false);
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before)) {
      PDFont pdFont = doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
      assertNull(pdFont.toUnicode(72), "precondition: PDFBox has no Unicode value");
      for (CmapSubtable subtable : ((PDTrueTypeFont) pdFont).getTrueTypeFont().getCmap().getCmaps()) {
        assertTrue(subtable.getPlatformId() != 0 && !(subtable.getPlatformId() == 3 && subtable.getPlatformEncodingId() == 1),
            "precondition: no Unicode subtable, found " + subtable.getPlatformId() + "," + subtable.getPlatformEncodingId());
      }
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("ToUnicode written for 1 fonts (4 codes: 0 kept from the existing text, 0 from standard glyph names, 0 from TeX glyph names,"
          + " 0 from the font program, 4 from the standard Macintosh glyph order); text changed for 0 codes", action.reason(),
          "the raw codes already extracted as H, e, l, o");
      CMap cmap = toUnicode(font(doc, "F1"));
      assertEquals("H", cmap.toUnicode(72, 1));
      assertEquals("e", cmap.toUnicode(101, 1));
      assertEquals("l", cmap.toUnicode(108, 1));
      assertEquals("o", cmap.toUnicode(111, 1));
      assertEquals(Optional.empty(), FontUnicodeRule.derived(action));
      after = save(doc);
    }
    assertNull(fillOnly(before, after));
    // Findings after a processor run: PdfProcessorTest#aFilledUnicodeMappingPassesTheTextInvariant (same fixture).
  }

  @Test
  void aSymbolicFontWithoutOrderEvidenceIsLeftUnmapped() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.symbolicTrueTypeWithoutUnicodeTables("Hello", true))) {
      PDFont pdFont = doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
      assertTrue(((PDTrueTypeFont) pdFont).getTrueTypeFont().getNumberOfGlyphs() < 258, "precondition: a renumbered subset");
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("no Unicode source for the unmapped codes; 4 codes in 1 fonts stay unmapped: LiberationSans", action.reason());
      assertNull(font(doc, "F1").getDictionaryObject(COSName.TO_UNICODE));
      assertEquals(Optional.of(new FontUnicodeRule.Unmapped(4, 1, List.of("LiberationSans"))), FontUnicodeRule.unmapped(doc));
    }
  }

  @Test
  void aType0FontWithAPartialMapIsCompleted() throws IOException {
    byte[] before = SyntheticPdfFactory.type0WithPartialToUnicode();
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().startsWith("ToUnicode written for 1 fonts (1 codes: "), action.reason());
      assertTrue(action.reason().endsWith("; 1 codes in 1 fonts have no source and stay unmapped: LiberationSans"), action.reason());
      CMap cmap = toUnicode(font(doc, "F1"));
      assertEquals("A", cmap.toUnicode(36, 2));
      assertEquals("B", cmap.toUnicode(37, 2));
      assertNull(cmap.toUnicode(1, 2), ".null stays unmapped");
      assertEquals(Optional.of(new FontUnicodeRule.Unmapped(1, 1, List.of("LiberationSans"))), FontUnicodeRule.unmapped(doc));
      after = save(doc);
    }
    assertNull(fillOnly(before, after));
  }

  @Test
  void aSharedToUnicodeStreamIsNeverEdited() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedTwoFontsSharingToUnicode())) {
      COSBase sharedBefore = font(doc, "F1").getDictionaryObject(COSName.TO_UNICODE);
      byte[] bytesBefore;
      try (var in = ((COSStream) sharedBefore).createInputStream()) {
        bytesBefore = in.readAllBytes();
      }
      assertSame(sharedBefore, font(doc, "F2").getDictionaryObject(COSName.TO_UNICODE), "precondition: shared");
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertSame(sharedBefore, font(doc, "F1").getDictionaryObject(COSName.TO_UNICODE));
      try (var in = ((COSStream) sharedBefore).createInputStream()) {
        assertArrayEquals(bytesBefore, in.readAllBytes());
      }
      COSBase f2 = font(doc, "F2").getDictionaryObject(COSName.TO_UNICODE);
      assertNotSame(sharedBefore, f2);
      CMap cmap = toUnicode(font(doc, "F2"));
      assertEquals("A", cmap.toUnicode(65, 1));
      assertEquals("ff", cmap.toUnicode(66, 1));
    }
  }

  @Test
  void theRuleDoesNotParticipateOutsideFixUploads() {
    assertTrue(rule.participates(FIX_UPLOAD));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertEquals(WorkerResult.RuleId.PDF_FONT_TOUNICODE_V1, rule.id());
  }

  @Test
  void twoRunsGiveIdenticalBytes() throws IOException {
    byte[][] runs = new byte[2][];
    for (int i = 0; i < 2; i++) {
      try (PDDocument doc = Loader.loadPDF(texFixture())) {
        assertEquals(ActionOutcome.APPLIED, apply(doc).outcome());
        runs[i] = save(doc);
      }
    }
    assertArrayEquals(runs[0], runs[1]);
  }

  @Test
  void renderingIsUnchanged() throws IOException {
    // RenderIdentity also compares the extracted text, which a fill changes by design: the text-neutral fixture goes through it,
    // the filling fixture is compared raster by raster.
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedStandard14WithoutEncoding())) {
      assertNull(RenderIdentity.changes(doc, List.of(1), d -> rule.apply(d, rule.inspect(d, FIX_UPLOAD))));
    }
    byte[] before = texFixture();
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before)) {
      assertEquals(ActionOutcome.APPLIED, apply(doc).outcome());
      after = save(doc);
    }
    try (PDDocument b = Loader.loadPDF(before); PDDocument a = Loader.loadPDF(after)) {
      var rb = new PDFRenderer(b).renderImageWithDPI(0, 72);
      var ra = new PDFRenderer(a).renderImageWithDPI(0, 72);
      assertArrayEquals(rb.getRGB(0, 0, rb.getWidth(), rb.getHeight(), null, 0, rb.getWidth()),
          ra.getRGB(0, 0, ra.getWidth(), ra.getHeight(), null, 0, ra.getWidth()));
    }
  }

  @Test
  void unmappedNamesAreSortedAndCappedAtFive() {
    assertEquals("A, B, C, D, E and 2 more", FontUnicodeRule.names(Arrays.asList("G", "F", "E", "D", "C", "B", "A", "A")));
    assertEquals("Type 3 font", FontUnicodeRule.names(List.of("Type 3 font")));
  }

  @Test
  void theUnmappedSurveyReadsTheDocumentAndCarriesFullNames() throws IOException {
    byte[] plain;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedType3WithMeaninglessNames())) {
      plain = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedType3WithMeaninglessNames())) {
      assertEquals(Optional.of(new FontUnicodeRule.Unmapped(2, 1, List.of("Type 3 font"))), FontUnicodeRule.unmapped(doc));
      assertArrayEquals(plain, save(doc), "the survey writes nothing");
    }
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(), "AB".getBytes(US_ASCII), null))) {
      assertEquals(Optional.empty(), FontUnicodeRule.unmapped(doc));
    }
  }

  @Test
  void anInvalidExistingEntryIsReplacedAndCounted() throws IOException {
    byte[] before = SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(66, "f_f"), "AB".getBytes(US_ASCII),
        GlyphAuditTest.toUnicode("<41> <0041>", "<42> <0000>", "<43> <FFFD>"));
    byte[] after;
    try (PDDocument doc = Loader.loadPDF(before)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("ToUnicode written for 1 fonts (1 codes: 0 kept from the existing text, 1 from standard glyph names, 0 from TeX glyph names,"
          + " 0 from the font program, 0 from the standard Macintosh glyph order); invalid entries replaced 2; text changed for 1 codes"
          + " (1 from standard glyph names, 0 from TeX glyph names, 0 from the font program, 0 from the standard Macintosh glyph order)", action.reason());
      CMap cmap = toUnicode(font(doc, "F1"));
      assertEquals("A", cmap.toUnicode(65, 1), "a valid entry is never changed");
      assertEquals("ff", cmap.toUnicode(66, 1), "U+0000 replaced");
      assertNull(cmap.toUnicode(67, 1), "U+FFFD alone is not carried over");
      after = save(doc);
    }
    assertNull(fillOnly(before, after));
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
