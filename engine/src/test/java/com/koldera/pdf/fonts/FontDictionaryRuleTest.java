package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.junit.jupiter.api.Test;

class FontDictionaryRuleTest {

  private static final WorkerRequest FIX_UPLOAD = request(Mode.FIX, Origin.UPLOAD);
  private static final COSName CID_SET = COSName.getPDFName("CIDSet");
  private final FontDictionaryRule rule = new FontDictionaryRule();

  private static String applied(int cidSets, int charSets, int cidToGid) {
    return "font dictionaries repaired: CIDSet removed from " + cidSets + " CID fonts, CharSet removed from " + charSets
        + " Type 1 fonts, CIDToGIDMap /Identity written for " + cidToGid + " CIDFontType2 fonts (the value a missing entry means)";
  }

  private WorkerResult.Action apply(PDDocument doc) {
    return rule.apply(doc, rule.inspect(doc, FIX_UPLOAD));
  }

  /** The first font of {@code resources} (a Type 0 font) and its descendant. */
  private static COSDictionary descendant(PDResources resources) throws IOException {
    COSName name = resources.getFontNames().iterator().next();
    COSDictionary type0 = resources.getFont(name).getCOSObject();
    return (COSDictionary) ((COSArray) type0.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
  }

  private static COSDictionary descendant(PDDocument doc) throws IOException {
    return descendant(doc.getPage(0).getResources());
  }

  private static COSDictionary descriptor(COSDictionary cidFont) {
    return (COSDictionary) cidFont.getDictionaryObject(COSName.FONT_DESC);
  }

  private static byte[] save(PDDocument doc) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CopyWriter.save(doc, out);
    return out.toByteArray();
  }

  @Test
  void theFixtureCarriesACidSet() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(false))) {
      assertTrue(descriptor(descendant(doc)).getDictionaryObject(CID_SET) instanceof COSStream, "PDFBox writes a CIDSet for subsets");
      assertTrue(descendant(doc).getDictionaryObject(COSName.CID_TO_GID_MAP) != null);
    }
  }

  @Test
  void aCidSetIsRemoved() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(false))) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(applied(1, 0, 0), action.reason());
      assertFalse(descriptor(descendant(doc)).containsKey(CID_SET));
    }
  }

  private static byte[] withoutCidToGidMapAndCidSet() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(false))) {
      descendant(doc).removeItem(COSName.CID_TO_GID_MAP);
      descriptor(descendant(doc)).removeItem(CID_SET);
      return save(doc);
    }
  }

  @Test
  void aMissingCidToGidMapBecomesIdentity() throws IOException {
    byte[] fixture = withoutCidToGidMapAndCidSet();
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      String textBefore = PdfPreflight.extractText(doc);
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(applied(0, 0, 1), action.reason());
      assertSame(COSName.IDENTITY, descendant(doc).getDictionaryObject(COSName.CID_TO_GID_MAP));
      assertEquals(textBefore, PdfPreflight.extractText(doc));
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      assertNull(RenderIdentity.changes(doc, List.of(1), d -> rule.apply(d, rule.inspect(d, FIX_UPLOAD))));
    }
  }

  @Test
  void aPdfA1DocumentKeepsItsCidSetAndCharSet() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(true))) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome(), action.reason());
      assertEquals("CIDSet and CharSet kept: the document declares PDF/A-1, which requires them", action.reason());
      assertTrue(descriptor(descendant(doc)).containsKey(CID_SET));
    }
  }

  @Test
  void aPdfA1DocumentStillGetsItsCidToGidMapAndSaysWhatWasKept() throws IOException {
    byte[] fixture;
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(true))) {
      descendant(doc).removeItem(COSName.CID_TO_GID_MAP);
      fixture = save(doc);
    }
    try (PDDocument doc = Loader.loadPDF(fixture)) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(applied(0, 0, 1) + "; CIDSet and CharSet kept: the document declares PDF/A-1, which requires them", action.reason());
      assertTrue(descriptor(descendant(doc)).containsKey(CID_SET));
    }
  }

  @Test
  void aFontOnlyInAnAnnotationAppearanceIsRepaired() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0SubsetInWidgetAppearance())) {
      PDAnnotation widget = doc.getPage(0).getAnnotations().get(0);
      COSDictionary cidFont = descendant(widget.getAppearance().getNormalAppearance().getAppearanceStream().getResources());
      assertTrue(descriptor(cidFont).containsKey(CID_SET), "fixture");
      WorkerResult.Action action = apply(doc);
      assertEquals(applied(1, 0, 0), action.reason());
      assertFalse(descriptor(cidFont).containsKey(CID_SET));
    }
  }

  @Test
  void anEmbeddedType1CFontLosesItsCharSetAndANonEmbeddedOneKeepsIt() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      PDResources resources = doc.getPage(0).getResources();
      COSDictionary embedded = type1WithCharSet(true);
      COSDictionary notEmbedded = type1WithCharSet(false);
      resources.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("FE"), embedded);
      resources.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("FN"), notEmbedded);
      WorkerResult.Action action = apply(doc);
      assertEquals(applied(0, 1, 0), action.reason());
      assertFalse(descriptor(embedded).containsKey(COSName.CHAR_SET));
      assertTrue(descriptor(notEmbedded).containsKey(COSName.CHAR_SET));
    }
  }

  private static COSDictionary type1WithCharSet(boolean embedded) {
    COSDictionary descriptor = new COSDictionary();
    descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
    descriptor.setName(COSName.FONT_NAME, "ABCDEF+Minion");
    descriptor.setString(COSName.CHAR_SET, "/A/B");
    if (embedded) {
      COSStream program = new COSStream();
      program.setItem(COSName.SUBTYPE, COSName.getPDFName("Type1C"));
      descriptor.setItem(COSName.FONT_FILE3, program);
    }
    COSDictionary font = new COSDictionary();
    font.setItem(COSName.TYPE, COSName.FONT);
    font.setItem(COSName.SUBTYPE, COSName.TYPE1);
    font.setName(COSName.BASE_FONT, "ABCDEF+Minion");
    font.setItem(COSName.FONT_DESC, descriptor);
    return font;
  }

  @Test
  void nothingToDoIsSkipped() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      WorkerResult.Action action = apply(doc);
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("no font has a CIDSet, a CharSet or a missing CIDToGIDMap", action.reason());
    }
  }

  @Test
  void theRuleDoesNotParticipateOutsideFixUploads() {
    assertTrue(rule.participates(FIX_UPLOAD));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)));
    assertEquals(WorkerResult.RuleId.PDF_FONT_DICTIONARY_V1, rule.id());
  }

  @Test
  void twoRunsGiveIdenticalBytes() throws IOException {
    byte[][] runs = new byte[2][];
    for (int i = 0; i < 2; i++) {
      try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(false))) {
        assertEquals(ActionOutcome.APPLIED, apply(doc).outcome());
        runs[i] = save(doc);
      }
    }
    assertArrayEquals(runs[0], runs[1]);
  }

  @Test
  void renderingIsUnchanged() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.type0Subset(false))) {
      assertNull(RenderIdentity.changes(doc, List.of(1), d -> rule.apply(d, rule.inspect(d, FIX_UPLOAD))));
    }
  }

  private static WorkerRequest request(Mode mode, Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode, "ua1", null, null, null, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
