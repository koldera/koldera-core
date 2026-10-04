package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.*;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;

class TitleRuleTest {
  private final TitleRule rule = new TitleRule();

  private static PDDocument load(Options o) throws Exception { return Loader.loadPDF(SyntheticPdfFactory.build(o)); }

  @Test
  void idAndParticipation() {
    assertEquals(WorkerResult.RuleId.PDF_TITLE_V1, rule.id());
    assertTrue(rule.participates(TitleResolverTest.fix(null, null)));
    assertFalse(rule.participates(ExplicitLanguageRuleTest.request(WorkerRequest.Mode.STRUCTURE_FIX, null)));
    assertFalse(rule.participates(ExplicitLanguageRuleTest.request(WorkerRequest.Mode.SAFE_FIX, null)));
  }

  @Test
  void fileNameTitleIsWrittenToInfoAndSaysWhereItCameFrom() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "annual-report-2025.pdf"));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action a = rule.apply(d, decision);
      assertEquals(ActionOutcome.APPLIED, a.outcome());
      assertEquals("annual-report-2025", d.getDocumentInformation().getTitle());
      assertEquals("title taken from the file name; check it", a.reason());
      assertNull(a.before());
      assertEquals("annual-report-2025", a.after());
    }
  }

  @Test
  void existingTitleIsKeptAndTheRuleSkips() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Quarterly Report"))) {
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "x.pdf"));
      assertFalse(decision.applicable());
      assertEquals("the document already has a title; it is kept", decision.reason());
      assertEquals(ActionOutcome.SKIPPED, rule.apply(d, decision).outcome());
      assertEquals("Quarterly Report", d.getDocumentInformation().getTitle());
    }
  }

  @Test
  void userTitleReplacesAnExistingOneAndReportsBeforeAndAfter() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Old"))) {
      WorkerResult.Action a = rule.apply(d, rule.inspect(d, TitleResolverTest.fix("New title", null)));
      assertEquals(ActionOutcome.APPLIED, a.outcome());
      assertEquals("Old", a.before());
      assertEquals("New title", a.after());
      assertEquals("title supplied by the user", a.reason());
    }
  }

  static String packet(String dcTitle) {
    String title = dcTitle == null ? "" : "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + dcTitle + "</rdf:li></rdf:Alt></dc:title>";
    return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:format>application/pdf</dc:format>" + title
        + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
  }

  static void attachRaw(PDDocument d, String raw) throws Exception {
    d.getDocumentCatalog().setMetadata(new PDMetadata(d, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
  }

  static void attachPacket(PDDocument d, String dcTitle) throws Exception { attachRaw(d, packet(dcTitle)); }

  private static String metadata(PDDocument d) throws Exception {
    return new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
  }

  @Test
  void xmpTitleIsCopiedToInfoAndThePacketIsNotReplaced() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      attachPacket(d, "Real title");
      WorkerResult.Action a = rule.apply(d, rule.inspect(d, TitleResolverTest.fix(null, "annual-report.pdf")));
      assertEquals(ActionOutcome.APPLIED, a.outcome());
      assertEquals("the document's XMP title was copied to the Info dictionary", a.reason());
      assertNull(a.before());
      assertEquals("Real title", a.after());
      assertEquals("Real title", d.getDocumentInformation().getTitle());
      assertEquals(packet("Real title"), metadata(d), "the packet is left byte-identical");
    }
  }

  @Test
  void keptInfoTitleIsCopiedIntoAPacketWithoutDcTitle() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Quarterly Report"))) {
      attachPacket(d, null);
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "x.pdf"));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action a = rule.apply(d, decision);
      assertEquals(ActionOutcome.APPLIED, a.outcome());
      assertEquals("the document already has a title; it is kept and copied to the XMP metadata", a.reason());
      assertEquals("Quarterly Report", a.before());
      assertEquals("Quarterly Report", a.after());
      assertEquals("Quarterly Report", d.getDocumentInformation().getTitle());
      assertTrue(metadata(d).contains("Quarterly Report"), metadata(d));
      assertTrue(metadata(d).contains("application/pdf"));
    }
  }

  @Test
  void keptInfoTitleNeverOverwritesAPresentDcTitle() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Quarterly Report"))) {
      attachPacket(d, "Other title");
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "x.pdf"));
      assertFalse(decision.applicable());
      assertEquals("the document already has a title; it is kept", decision.reason());
      rule.apply(d, decision);
      assertEquals(packet("Other title"), metadata(d));
    }
  }

  /** PAC on un-charter-aspose: an empty x-default dc:title is no title; it is filled, and no reason claims a title exists. */
  static final String EMPTY_DC_TITLE = SyntheticPdfFactory.EMPTY_DC_TITLE_PACKET;

  static String dcTitleDefault(PDDocument d) throws Exception {
    var xmp = new org.apache.xmpbox.xml.DomXmpParser().parse(d.getDocumentCatalog().getMetadata().toByteArray());
    return xmp.getDublinCoreSchema().getTitle("x-default");
  }

  @Test
  void anEmptyDcTitleIsFilledWithTheFileNameTitle() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      attachRaw(d, EMPTY_DC_TITLE);
      WorkerResult.Action a = rule.apply(d, rule.inspect(d, TitleResolverTest.fix(null, "un-charter.pdf")));
      assertEquals(ActionOutcome.APPLIED, a.outcome(), a.reason());
      assertEquals("title taken from the file name; check it", a.reason());
      assertEquals("un-charter", d.getDocumentInformation().getTitle());
      assertEquals("un-charter", dcTitleDefault(d));
      assertTrue(metadata(d).contains("application/pdf"), "existing properties are kept");
    }
  }

  @Test
  void aWhitespaceDcTitleIsFilledWithTheKeptInfoTitle() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Quarterly Report"))) {
      attachPacket(d, "   ");
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "x.pdf"));
      assertTrue(decision.applicable(), decision.reason());
      WorkerResult.Action a = rule.apply(d, decision);
      assertEquals("the document already has a title; it is kept and copied to the XMP metadata", a.reason());
      assertEquals("Quarterly Report", dcTitleDefault(d));
    }
  }

  @Test
  void aBlankInfoTitleAndAnEmptyDcTitleFallBackToTheFileName() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("  "))) {
      attachRaw(d, EMPTY_DC_TITLE);
      WorkerResult.Action a = rule.apply(d, rule.inspect(d, TitleResolverTest.fix(null, "un-charter.pdf")));
      assertEquals("title taken from the file name; check it", a.reason());
      assertEquals("un-charter", d.getDocumentInformation().getTitle());
      assertEquals("un-charter", dcTitleDefault(d));
    }
  }

  @Test
  void keptInfoTitleLeavesAnUnreadablePacketAlone() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Quarterly Report"))) {
      attachRaw(d, "not xml");
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "x.pdf"));
      assertFalse(decision.applicable());
      assertEquals("the document already has a title; it is kept", decision.reason());
    }
  }

  @Test
  void userTitleReplacesBothInfoAndXmpTitles() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Old"))) {
      attachPacket(d, "Old xmp title");
      WorkerResult.Action a = rule.apply(d, rule.inspect(d, TitleResolverTest.fix("New title", "x.pdf")));
      assertEquals(ActionOutcome.APPLIED, a.outcome());
      assertEquals("title supplied by the user", a.reason());
      assertEquals("New title", d.getDocumentInformation().getTitle());
      assertTrue(metadata(d).contains("New title"), metadata(d));
      assertFalse(metadata(d).contains("Old xmp title"), metadata(d));
    }
  }

  @Test
  void userTitleReplacesAnXmpOnlyTitle() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      attachPacket(d, "Old xmp title");
      rule.apply(d, rule.inspect(d, TitleResolverTest.fix("New title", null)));
      assertEquals("New title", d.getDocumentInformation().getTitle());
      assertFalse(metadata(d).contains("Old xmp title"), metadata(d));
    }
  }

  @Test
  void placeholderFileNameSkipsWithTheNoTitleReason() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      RuleDecision decision = rule.inspect(d, TitleResolverTest.fix(null, "document.pdf"));
      assertFalse(decision.applicable());
      assertEquals("no title was supplied, the document has none and the file name gives none", decision.reason());
    }
  }

  @Test
  void existingXmpPacketGetsDcTitle() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      String packet = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
          + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
          + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:format>application/pdf</dc:format></rdf:Description>"
          + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
      PDMetadata md = new PDMetadata(d, new ByteArrayInputStream(packet.getBytes(StandardCharsets.UTF_8)));
      d.getDocumentCatalog().setMetadata(md);
      rule.apply(d, rule.inspect(d, TitleResolverTest.fix("Annual report", null)));
      String after = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(after.contains("Annual report"), after);
      assertTrue(after.contains("application/pdf"), "existing properties are kept");
      assertFalse(after.contains("pdfuaid"), "no conformance identifier");
    }
  }

  @Test
  void unparseableXmpIsLeftAloneAndTheInfoTitleIsStillWritten() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      d.getDocumentCatalog().setMetadata(new PDMetadata(d, new ByteArrayInputStream("not xml".getBytes(StandardCharsets.UTF_8))));
      WorkerResult.Action a = rule.apply(d, rule.inspect(d, TitleResolverTest.fix("Annual report", null)));
      assertEquals(ActionOutcome.APPLIED, a.outcome());
      assertEquals("Annual report", d.getDocumentInformation().getTitle());
      assertEquals("not xml", new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8));
      assertTrue(a.reason().endsWith("; the existing XMP packet could not be read and was left unchanged"), a.reason());
    }
  }
}
