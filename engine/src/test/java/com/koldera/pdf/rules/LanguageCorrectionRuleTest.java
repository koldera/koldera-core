package com.koldera.pdf.rules;

import static com.koldera.pdf.rules.RoleMapRuleTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequest.Mode;
import com.koldera.pdf.contracts.WorkerRequest.Origin;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.language.ScriptDetector;
import com.koldera.pdf.language.ScriptDetectorTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

public class LanguageCorrectionRuleTest {

  private final LanguageCorrectionRule rule = new LanguageCorrectionRule();

  private static LanguageCorrectionRule.Correction decide(String existing, String explicit, String text) {
    return LanguageCorrectionRule.decide(existing, explicit, ScriptDetector.detect(text));
  }

  @Test
  void participatesInFixForUploadsOnly() {
    assertEquals(RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, rule.id());
    assertTrue(rule.participates(request(Mode.FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.FIX, Origin.HTML_GENERATED)), "the renderer sets a generated PDF's language");
    assertFalse(rule.participates(request(Mode.SAFE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.STRUCTURE_FIX, Origin.UPLOAD)));
    assertFalse(rule.participates(request(Mode.ANALYZE, Origin.UPLOAD)));
  }

  @Test
  void aLatinTagOnTextInAnotherScriptIsReplacedByTheLanguageTheTextShows() {
    LanguageCorrectionRule.Correction arabic = decide("en-US", null, ScriptDetectorTest.AR.repeat(3));
    assertEquals("ar", arabic.language(), arabic.reason());
    assertEquals("the declared language 'en-US' is written in Latin script, but 100% of the text's letters are Arabic; the text's letters"
        + " show 'ar'", arabic.reason());
    assertEquals("uk", decide("en-US", null, ScriptDetectorTest.UK.repeat(2)).language());
    assertEquals("he", decide("fr", null, ScriptDetectorTest.HE.repeat(3)).language());
    assertEquals("ja", decide("en", null, ScriptDetectorTest.JA.repeat(3)).language());
  }

  @Test
  void theCallersLanguageWinsWhenItsScriptMatchesTheText() {
    LanguageCorrectionRule.Correction c = decide("en-US", "bg", ScriptDetectorTest.BG.repeat(3));
    assertEquals("bg", c.language(), c.reason());
    assertTrue(c.reason().endsWith("; the caller's language 'bg' is written in that script"), c.reason());
  }

  @Test
  void aCallersLanguageIsNeverOverriddenSilently() {
    LanguageCorrectionRule.Correction wrongScript = decide("en-US", "fr", ScriptDetectorTest.AR.repeat(3));
    assertNull(wrongScript.language(), "the text shows Arabic, but the caller said 'fr': nothing is replaced");
    assertEquals("the declared language 'en-US' is kept: the caller's language 'fr' is not written in the text's script (Arabic),"
        + " and a language the caller supplied is never overridden; a person should check both", wrongScript.reason());
    LanguageCorrectionRule.Correction unknownScript = decide("x-unknown", "tlh", ScriptDetectorTest.UK.repeat(2));
    assertNull(unknownScript.language());
    assertTrue(unknownScript.reason().contains("the caller's language 'tlh'"), unknownScript.reason());
  }

  @Test
  void anExistingXmpLanguageIsNamedAsLeftAsItWas() throws IOException {
    try (PDDocument doc = Loader.loadPDF(cyrillicPdf("en-US"))) {
      String packet = XmpPacketEditorTest.HEAD.replace("xmp:CreatorTool=", "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:language=\"en-US\" xmp:CreatorTool=")
          + XmpPacketEditorTest.TAIL;
      doc.getDocumentCatalog().setMetadata(new org.apache.pdfbox.pdmodel.common.PDMetadata(doc,
          new java.io.ByteArrayInputStream(packet.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, fix(null)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(action.reason().endsWith(LanguageCorrectionRule.XMP_LANGUAGE_KEPT), action.reason());
      assertEquals(packet, new String(doc.getDocumentCatalog().getMetadata().toByteArray(), java.nio.charset.StandardCharsets.UTF_8),
          "the packet is not changed");
    }
    try (PDDocument doc = Loader.loadPDF(cyrillicPdf("en-US"))) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, fix(null)));
      assertFalse(action.reason().contains(LanguageCorrectionRule.XMP_LANGUAGE_KEPT), "no packet, nothing to name");
    }
  }

  @Test
  void anUndecidableLanguageIsNotGuessed() {
    LanguageCorrectionRule.Correction c = decide("en-US", null, ScriptDetectorTest.BG.repeat(3));
    assertNull(c.language());
    assertEquals("the declared language 'en-US' is written in Latin script, but the text is Cyrillic; Cyrillic text without enough letters"
        + " that only one supported language uses, and no explicit language in that script was supplied; the language is left for a person", c.reason());
  }

  @Test
  void xUnknownIsReplacedByTheCallersLanguageOrTheOneTheTextShows() {
    assertEquals("en", decide("x-unknown", "en", ScriptDetectorTest.EN.repeat(2)).language());
    assertEquals("uk", decide("X-Unknown", null, ScriptDetectorTest.UK.repeat(2)).language());
    LanguageCorrectionRule.Correction latin = decide("x-unknown", null, ScriptDetectorTest.EN.repeat(2));
    assertNull(latin.language());
    assertTrue(latin.reason().contains("Latin script is shared by many languages"), latin.reason());
  }

  @Test
  void aTagThatDoesNotClearlyContradictTheTextIsKept() {
    assertEquals("the declared language 'de' is written in Latin script and the text is Latin; it is kept",
        decide("de", "en", ScriptDetectorTest.EN.repeat(2)).reason());
    assertNull(decide("ru", null, ScriptDetectorTest.UK.repeat(2)).language(), "only a Latin tag is corrected: Russian vs Ukrainian stays for a person");
    assertNull(decide("en", null, ScriptDetectorTest.EL.repeat(2)).language(), "Greek text is not in the owner's list");
    assertNull(decide("tlh", null, ScriptDetectorTest.AR.repeat(3)).language(), "a tag whose script is unknown is never judged");
    assertNull(decide("sr-Cyrl", null, ScriptDetectorTest.EN.repeat(2)).language(), "a script subtag decides the tag's script");
    LanguageCorrectionRule.Correction little = decide("en-US", null, ScriptDetectorTest.AR);
    assertNull(little.language());
    assertEquals("the declared language 'en-US' is kept: too little text to tell the script (fewer than 200 letters)", little.reason());
  }

  @Test
  void aTaggedUploadDeclaringEnglishOverUkrainianTextGetsUk() throws IOException {
    try (PDDocument doc = Loader.loadPDF(cyrillicPdf("en-US"))) {
      RuleDecision decision = rule.inspect(doc, fix("uk"));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("uk", doc.getDocumentCatalog().getLanguage());
      assertEquals("en-US", action.before());
      assertEquals("uk", action.after());
      assertTrue(action.reason().startsWith("replaced the catalog /Lang: the declared language 'en-US' is written in Latin script, but"), action.reason());
      assertTrue(action.reason().endsWith("; whether the new language is right requires human review"), action.reason());
    }
  }

  @Test
  void aDocumentWithoutLangOrWithAMatchingLangIsLeftToTheOtherRules() throws IOException {
    try (PDDocument doc = Loader.loadPDF(cyrillicPdf(null))) {
      RuleDecision decision = rule.inspect(doc, fix(null));
      assertFalse(decision.applicable());
      assertEquals("the catalog has no /Lang; a missing language is only set from explicit input", decision.reason());
    }
    try (PDDocument doc = Loader.loadPDF(cyrillicPdf("uk"))) {
      assertFalse(rule.inspect(doc, fix(null)).applicable());
      assertEquals("uk", doc.getDocumentCatalog().getLanguage());
    }
  }

  private static WorkerRequest fix(String explicitLanguage) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        Mode.FIX, "ua1", explicitLanguage, null, null, Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  /** Ukrainian text in the Liberation Sans that ships with PDFBox; an optional catalog /Lang. */
  public static byte[] cyrillicPdf(String lang) throws IOException {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      PDType0Font font = PDType0Font.load(doc, ttf);
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(font, 10);
        cs.newLineAtOffset(40, 780);
        String text = ScriptDetectorTest.UK.repeat(2);
        for (int i = 0; i < text.length(); i += 90) {
          cs.showText(text.substring(i, Math.min(text.length(), i + 90)));
          cs.newLineAtOffset(0, -14);
        }
        cs.endText();
      }
      if (lang != null) {
        doc.getDocumentCatalog().setLanguage(lang);
      }
      doc.setDocumentId(45L);
      doc.save(out);
      return out.toByteArray();
    }
  }
}
