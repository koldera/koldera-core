package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.inference.InferenceSettings;
import com.koldera.pdf.inference.StructureInferenceRule;
import com.koldera.pdf.preflight.PdfPreflight;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the structure rule kept /ActualText, /Alt or /E of other elements, the copy's
 * extracted text must equal that of the original with the descriptions carried (the exact oracle)
 * and its glyph text must equal the original's; a run that kept only languages stays strict.
 */
class KeptDescriptionsInvariantTest {

  @TempDir Path dir;

  private static final WorkerRequest REQUEST = new WorkerRequest(
      "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174002"), WorkerRequest.CANONICAL_INPUT_PATH,
      WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", null, "x.pdf",
      WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

  @Test
  void aCopyWhoseTextFollowsTheKeptReplacementTextPasses() throws Exception {
    byte[] original = SyntheticPdfFactory.taggedUnusableWithDescribedText(false);
    Run run = rebuild(original, t -> t);

    assertNull(check(original, run));
  }

  @Test
  void aKeptDescriptionsRunWithARealGlyphChangeFails() throws Exception {
    byte[] original = SyntheticPdfFactory.taggedUnusableWithDescribedText(false);
    Run run = rebuild(original, tokens -> replaceString(tokens, "Ein deutscher Satz.", "Ein deutscher Satz!"));

    assertEquals("extracted text digest changed", check(original, run));
  }

  @Test
  void aKeptDescriptionsRunWhoseReplacementTextDiffersFromTheOriginalsFails() throws Exception {
    byte[] original = SyntheticPdfFactory.taggedUnusableWithDescribedText(false);
    Run run = rebuild(original, tokens -> wrapFirstShownString(tokens, "Injected"));

    assertEquals("extracted text digest changed", check(original, run));
  }

  /** Both rules applied: a real fill of a glyph without Unicode plus the kept replacement text passes both checks. */
  @Test
  void aToUnicodeFillInAKeptDescriptionsRunPasses() throws Exception {
    byte[] original = withUnmappedGlyph(SyntheticPdfFactory.taggedUnusableWithDescribedText(false));
    Run run = rebuild(original, t -> t, KeptDescriptionsInvariantTest::fillCode2);

    assertNull(check(original, run, withToUnicode(run)));
  }

  /** Both rules applied: the glyph audit passes (glyphs untouched) but the reader text departs from the kept replacement text. */
  @Test
  void aToUnicodeRunWhoseReplacementTextDiffersFromTheOriginalsFails() throws Exception {
    byte[] original = withUnmappedGlyph(SyntheticPdfFactory.taggedUnusableWithDescribedText(false));
    Run run = rebuild(original, tokens -> wrapFirstShownString(tokens, "Injected"), KeptDescriptionsInvariantTest::fillCode2);

    assertEquals("extracted text changed where kept replacement text applies", check(original, run, withToUnicode(run)));
  }

  /** Both rules applied: a real glyph change still fails the glyph audit. */
  @Test
  void aToUnicodeRunWithARealGlyphChangeFails() throws Exception {
    byte[] original = withUnmappedGlyph(SyntheticPdfFactory.taggedUnusableWithDescribedText(false));
    Run run = rebuild(original, tokens -> replaceString(tokens, "Ein deutscher Satz.", "Ein deutscher Satz!"), KeptDescriptionsInvariantTest::fillCode2);

    String failure = check(original, run, withToUnicode(run));
    assertTrue(failure != null && failure.startsWith(com.koldera.pdf.fonts.GlyphAudit.FAILURE_PREFIX), String.valueOf(failure));
  }

  /** Only a /Lang was kept: no replacement text may change what is extracted, so the digest stays strict. */
  @Test
  void aLanguageOnlyRunStaysStrict() throws Exception {
    byte[] original = germanFirstLine(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9));
    Run run = rebuild(original, tokens -> wrapFirstShownString(tokens, "Injected"));

    assertEquals("extracted text digest changed", check(original, run));
  }

  // ---- helpers -------------------------------------------------------------------------------------------

  private record Run(WorkerResult.Action action, Path copy) {}

  /** The structure rule applied to {@code original}, then {@code edit} applied to page 1's tokens, saved. */
  private Run rebuild(byte[] original, UnaryOperator<List<Object>> edit) throws Exception {
    return rebuild(original, edit, doc -> { });
  }

  /** A document change after the rule (e.g. a ToUnicode fill). */
  private interface DocEdit {
    void apply(PDDocument doc) throws Exception;
  }

  private Run rebuild(byte[] original, UnaryOperator<List<Object>> edit, DocEdit docEdit) throws Exception {
    StructureInferenceRule rule = new StructureInferenceRule(InferenceSettings.DEFAULTS);
    Path copy = dir.resolve("copy.pdf");
    try (PDDocument doc = Loader.loadPDF(original)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, REQUEST));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      docEdit.apply(doc);
      List<Object> tokens = edit.apply(new ArrayList<>(new PDFStreamParser(doc.getPage(0)).parse()));
      PDStream stream = new PDStream(doc);
      try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(out).writeTokens(tokens);
      }
      doc.getPage(0).setContents(stream);
      doc.save(copy.toFile());
      return new Run(action, copy);
    }
  }

  private String check(byte[] original, Run run) throws Exception {
    return check(original, run, List.of(run.action()));
  }

  private String check(byte[] original, Run run, List<WorkerResult.Action> actions) throws Exception {
    Path input = dir.resolve("original.pdf");
    Files.write(input, original);
    PdfPreflight preflight = new PdfPreflight();
    return PdfProcessor.invariantFailure(preflight.inspect(input, REQUEST), preflight.inspect(run.copy(), REQUEST), actions, original, run.copy());
  }

  /** The structure action and an applied ToUnicode action, as in a run where both rules applied. */
  private static List<WorkerResult.Action> withToUnicode(Run run) {
    return List.of(run.action(), new WorkerResult.Action(
        WorkerResult.RuleId.PDF_FONT_TOUNICODE_V1, ActionOutcome.APPLIED, "test: fills", "1 font without ToUnicode", "1 font mapped"));
  }

  /**
   * The described-text fixture plus one glyph without a Unicode value: code 2 of the ligature font
   * /F2 is named {@code /zzunmapped} (no AGL name, no ToUnicode) and shown on a line of its own.
   */
  private static byte[] withUnmappedGlyph(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      COSDictionary f2 = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F2"));
      org.apache.pdfbox.cos.COSArray differences = f2.getCOSDictionary(COSName.ENCODING).getCOSArray(COSName.DIFFERENCES);
      differences.add(org.apache.pdfbox.cos.COSInteger.get(2));
      differences.add(COSName.getPDFName("zzunmapped"));
      List<Object> tokens = new ArrayList<>(new PDFStreamParser(doc.getPage(0)).parse());
      tokens.addAll(new PDFStreamParser("BT /F2 11 Tf 72 440 Td <02> Tj ET".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).parse());
      PDStream stream = new PDStream(doc);
      try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(os).writeTokens(tokens);
      }
      doc.getPage(0).setContents(stream);
      doc.save(out);
      return out.toByteArray();
    }
  }

  /** A fill as the ToUnicode rule makes it: /F2 gains a ToUnicode map with code 2 → "z" (the other codes keep their text). */
  private static void fillCode2(PDDocument doc) throws Exception {
    COSDictionary f2 = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).getCOSDictionary(COSName.getPDFName("F2"));
    String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Test def /CMapType 2 def\n"
        + "1 begincodespacerange <00> <FF> endcodespacerange\n"
        + "2 beginbfchar <01> <FB01> <02> <007A> endbfchar\n"
        + "1 beginbfrange <20> <7E> <0020> endbfrange\n"
        + "endcmap CMapName currentdict /CMap defineresource pop end end";
    PDStream toUnicode = new PDStream(doc);
    try (OutputStream os = toUnicode.createOutputStream()) {
      os.write(cmap.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    f2.setItem(COSName.TO_UNICODE, toUnicode);
  }

  private static List<Object> replaceString(List<Object> tokens, String from, String to) {
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i) instanceof COSString s && s.getString().equals(from)) {
        tokens.set(i, new COSString(to));
      }
    }
    return tokens;
  }

  /** {@code /Span <</ActualText (text)>> BDC … EMC} around the first {@code (string) Tj} (no marked content nested inside): glyphs unchanged. */
  private static List<Object> wrapFirstShownString(List<Object> tokens, String text) {
    for (int i = 1; i < tokens.size(); i++) {
      if (tokens.get(i) instanceof Operator op && "Tj".equals(op.getName()) && tokens.get(i - 1) instanceof COSString) {
        tokens.add(i + 1, Operator.getOperator("EMC"));
        COSDictionary props = new COSDictionary();
        props.setString(COSName.ACTUAL_TEXT, text);
        tokens.add(i - 1, Operator.getOperator("BDC"));
        tokens.add(i - 1, props);
        tokens.add(i - 1, COSName.getPDFName("Span"));
        return tokens;
      }
    }
    throw new IllegalStateException("no text shown");
  }

  /** The fixture with its first tagged element given /Lang de (the only description in the tree). */
  private static byte[] germanFirstLine(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
      ((PDStructureElement) document.getKids().get(0)).getCOSObject().setString(COSName.LANG, "de");
      doc.save(out);
      return out.toByteArray();
    }
  }
}
