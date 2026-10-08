package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;

class XmpMetadataRuleTest {

  private final XmpMetadataRule rule = new XmpMetadataRule(new BasicStructureRule());

  @Test
  void writesMinimalPacketFromInfoTitleAndCatalogLanguageWithoutAnyConformanceClaim() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.build(Options.plain().language("en").infoTitle("Quarterly R&D <statement>")))) {
      doc.getDocumentInformation().setCreator("Synthetic Creator");
      doc.getDocumentInformation().setProducer("Synthetic Producer 1.0");
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(RuleId.PDF_XMP_METADATA_FROM_INFO_V1, action.ruleId());
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
      assertNotNull(metadata);
      assertEquals("Metadata", metadata.getCOSObject().getNameAsString(COSName.TYPE));
      assertEquals("XML", metadata.getCOSObject().getNameAsString(COSName.SUBTYPE));
      String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("<dc:title>"), xmp);
      assertTrue(xmp.contains("Quarterly R&amp;D &lt;statement&gt;"), xmp);
      assertTrue(xmp.contains("<dc:language>"), xmp);
      assertTrue(xmp.contains("<rdf:li>en</rdf:li>"), xmp);
      assertTrue(xmp.contains("<xmp:CreatorTool>Synthetic Creator</xmp:CreatorTool>"), xmp);
      assertTrue(xmp.contains("<pdf:Producer>Synthetic Producer 1.0</pdf:Producer>"), xmp);
      assertFalse(xmp.contains("pdfuaid"), "never claims PDF/UA: " + xmp);
      assertFalse(xmp.contains("pdfaid"), xmp);
      assertEquals("Quarterly R&D <statement>", doc.getDocumentInformation().getTitle(), "Info untouched");
      // The reason must not contradict a later PDF_UA_IDENTIFIER_V1 (mode fix, uploads and generated PDFs), nor promise one.
      assertEquals(
          "wrote a minimal XMP packet (dc:title=from Info, dc:language=en); no PDF/UA identifier is written: PDF_UA_IDENTIFIER_V1"
              + " adds one only in mode fix, on request (declarePdfUa) after human review (humanReviewAcknowledged), when the machine checks pass;"
              + " whether the title and language are correct requires human review",
          action.reason());
    }
  }

  @Test
  void omitsTitleAndLanguageElementsWhenTheDocumentHasNone() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.simpleUntagged())) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertFalse(xmp.contains("dc:title"), "no title is invented: " + xmp);
      assertFalse(xmp.contains("dc:language"), "no language is guessed: " + xmp);
      assertTrue(xmp.contains("<x:xmpmeta"), xmp);
    }
  }

  @Test
  void skippedOutsideFixMode() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")))) {
      assertFalse(rule.inspect(doc, request(WorkerRequest.Mode.ANALYZE)).applicable());
      RuleDecision analyze = rule.inspect(doc, request(WorkerRequest.Mode.ANALYZE));
      assertEquals(ActionOutcome.SKIPPED, rule.apply(doc, analyze).outcome());
      assertNull(doc.getDocumentCatalog().getMetadata());
    }
  }

  @Test
  void skippedWhenMetadataAlreadyExists() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")))) {
      doc.getDocumentCatalog().setMetadata(new PDMetadata(doc, new ByteArrayInputStream("<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8))));

      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));

      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("already"), decision.reason());
      rule.apply(doc, decision);
      assertEquals("<x:xmpmeta/>", new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8), "existing metadata never overwritten");
    }
  }

  @Test
  void aTaggedPdfThisPlatformGeneratedGetsThePacketWithTitleAndLanguage() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      assertTrue(rule.participates(generated(WorkerRequest.Mode.FIX)));
      RuleDecision decision = rule.inspect(doc, generated(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("<rdf:li xml:lang=\"x-default\">Tagged synthetic fixture</rdf:li>"), xmp);
      assertTrue(xmp.contains("<dc:language><rdf:Bag><rdf:li>en</rdf:li></rdf:Bag></dc:language>"), xmp);
      assertFalse(xmp.contains("pdfuaid"), "this rule never writes an identifier: " + xmp);
    }
  }

  @Test
  void aGeneratedPdfWithExistingMetadataKeepsIt() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      doc.getDocumentCatalog().setMetadata(new PDMetadata(doc, new ByteArrayInputStream("<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8))));
      RuleDecision decision = rule.inspect(doc, generated(WorkerRequest.Mode.FIX));
      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("already"), decision.reason());
    }
  }

  @Test
  void aTaggedUploadWithoutAPacketGetsAMinimalPacketInFix() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());
      assertEquals("catalog has no /Metadata; a minimal XMP packet can be derived from Info and /Lang without any conformance claim", decision.reason());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("Tagged synthetic fixture"), xmp);
      assertTrue(xmp.contains("<rdf:li>en</rdf:li>"), xmp);
      assertFalse(xmp.contains("pdfuaid"), xmp);
    }
  }

  @Test
  void theLadderRunWithoutTheStructureStepsKeepsThePhaseOneBehaviourForATaggedUpload() throws IOException {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.tagged())) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX), java.util.EnumSet.of(RuleId.PDF_STRUCTURE_INFERRED_V1));
      assertFalse(decision.applicable());
      assertTrue(decision.reason().contains("already tagged"), decision.reason());
    }
  }

  // ---- fix: an existing packet gains dc:title / dc:language ----------------------------

  private static final String PACKET_WITHOUT_TITLE = XmpPacketEditorTest.HEAD + XmpPacketEditorTest.TAIL;

  private static PDDocument withPacket(byte[] pdf, String packet) throws IOException {
    PDDocument doc = Loader.loadPDF(pdf);
    doc.getDocumentCatalog().setMetadata(new PDMetadata(doc, new ByteArrayInputStream(packet.getBytes(StandardCharsets.UTF_8))));
    return doc;
  }

  @Test
  void inFixATaggedUploadsExistingPacketGainsTheInfoTitleAndTheLanguageWithoutLosingAnything() throws IOException {
    try (PDDocument doc = withPacket(SyntheticPdfFactory.tagged(), PACKET_WITHOUT_TITLE)) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertTrue(decision.applicable(), decision.reason());
      assertEquals("Metadata present without dc:title, dc:language", decision.before());

      WorkerResult.Action action = rule.apply(doc, decision);

      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals(
          "added dc:title (from Info) and dc:language (en) to the existing XMP packet; nothing in it was changed or removed;"
              + " whether the title and language are correct requires human review",
          action.reason());
      assertEquals("dc:title=from Info, dc:language=en", action.after());
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("<rdf:li xml:lang=\"x-default\">Tagged synthetic fixture</rdf:li>"), xmp);
      assertTrue(xmp.contains("<rdf:li>kept &amp; untouched</rdf:li>"), xmp);
      assertTrue(xmp.startsWith(XmpPacketEditorTest.HEAD), "the packet is only added to");
      assertFalse(xmp.contains("pdfuaid"), xmp);
    }
  }

  @Test
  void inFixOnlyTheMissingPropertyIsAdded() throws IOException {
    String withTitle = XmpPacketEditorTest.HEAD.replace("xmp:CreatorTool=", "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:title=\"Own\" xmp:CreatorTool=")
        + XmpPacketEditorTest.TAIL;
    try (PDDocument doc = withPacket(SyntheticPdfFactory.build(Options.plain().language("de").infoTitle("Info title")), withTitle)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertEquals("dc:language=de", action.after());
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertFalse(xmp.contains("Info title"), "a present dc:title is never added to or replaced: " + xmp);
    }
  }

  @Test
  void inFixAPacketThatAlreadyHasBothIsLeftAlone() throws IOException {
    String complete = XmpPacketEditorTest.HEAD.replace("xmp:CreatorTool=", "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:title=\"T\" dc:language=\"en\" xmp:CreatorTool=")
        + XmpPacketEditorTest.TAIL;
    try (PDDocument doc = withPacket(SyntheticPdfFactory.tagged(), complete)) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertFalse(decision.applicable());
      assertEquals("the existing XMP packet already has dc:title and dc:language; it is left unchanged", decision.reason());
    }
  }

  /** An empty dc:title is no title: the reason must not claim one (PAC "Title in XMP metadata"). */
  @Test
  void inFixAPacketWithAnEmptyTitleDoesNotClaimATitle() throws IOException {
    String blank = XmpPacketEditorTest.HEAD.replace("xmp:CreatorTool=", "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:title=\"  \" dc:language=\"en\" xmp:CreatorTool=")
        + XmpPacketEditorTest.TAIL;
    try (PDDocument doc = withPacket(SyntheticPdfFactory.tagged(), blank)) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertFalse(decision.applicable());
      assertEquals("the existing XMP packet has dc:language and an empty dc:title, which is not filled in place; it is left unchanged", decision.reason());
      assertEquals("the existing XMP packet has dc:language and an empty dc:title, which is not filled in place; it is left unchanged",
          rule.apply(doc, decision).reason());
    }
  }

  @Test
  void inFixAnExistingPacketThatCannotBeEditedSafelyIsLeftAloneWithTheReason() throws IOException {
    try (PDDocument doc = withPacket(SyntheticPdfFactory.tagged(), "<x:xmpmeta><broken>")) {
      RuleDecision decision = rule.inspect(doc, request(WorkerRequest.Mode.FIX));
      assertFalse(decision.applicable());
      assertEquals("catalog already has /Metadata that is not added to: the packet is not well-formed XML; it is left unchanged", decision.reason());
      rule.apply(doc, decision);
      assertEquals("<x:xmpmeta><broken>", new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void inFixAPacketIsNotTouchedWhenThereIsNeitherATitleNorALanguageToAdd() throws IOException {
    try (PDDocument doc = withPacket(SyntheticPdfFactory.simpleUntagged(), PACKET_WITHOUT_TITLE)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, request(WorkerRequest.Mode.FIX)));
      assertEquals(ActionOutcome.SKIPPED, action.outcome());
      assertEquals("the existing XMP packet lacks dc:title and dc:language, but the document has no Info title and no /Lang to copy; nothing is invented", action.reason());
      assertEquals(PACKET_WITHOUT_TITLE, new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void aGeneratedPdfsExistingPacketGainsTheTitleToo() throws IOException {
    try (PDDocument doc = withPacket(SyntheticPdfFactory.tagged(), PACKET_WITHOUT_TITLE)) {
      WorkerResult.Action action = rule.apply(doc, rule.inspect(doc, generated(WorkerRequest.Mode.FIX)));
      assertEquals(ActionOutcome.APPLIED, action.outcome(), action.reason());
      assertTrue(new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8).contains("Tagged synthetic fixture"));
    }
  }

  private static WorkerRequest generated(WorkerRequest.Mode mode) {
    return new WorkerRequest(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        null,
        null,
        null,
        WorkerRequest.Origin.HTML_GENERATED,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  private static WorkerRequest request(WorkerRequest.Mode mode) {
    return new WorkerRequest(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        null,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
