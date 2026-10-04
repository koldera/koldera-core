package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfUaIdentifierStepTest {

  private static final String UAID = "ISO 14289-1:2014 5-1";

  @TempDir Path dir;

  @Test
  void participatesInFixForUploadsAndGeneratedOnly() {
    assertTrue(PdfUaIdentifierStep.participates(request(WorkerRequest.Mode.FIX, WorkerRequest.Origin.HTML_GENERATED)));
    assertTrue(PdfUaIdentifierStep.participates(request(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD)), "uploads participate in fix mode");
    for (WorkerRequest.Mode mode : WorkerRequest.Mode.values()) {
      if (mode != WorkerRequest.Mode.FIX) {
        for (WorkerRequest.Origin origin : WorkerRequest.Origin.values()) {
          assertFalse(PdfUaIdentifierStep.participates(request(mode, origin)), mode + " " + origin);
        }
      }
    }
  }

  @Test
  void theIdentifierMayBeWrittenOnlyWhenTheIdentifierCheckIsTheOnlyFailure() {
    assertNull(PdfUaIdentifierStep.blocker(Map.of(UAID, 1)));
    String font = PdfUaIdentifierStep.blocker(Map.of(UAID, 1, "ISO 14289-1:2014 7.21.4.1-1", 3, "ISO 14289-1:2014 7.1-3", 1));
    assertNotNull(font);
    assertTrue(font.contains("ISO 14289-1:2014 7.1-3, ISO 14289-1:2014 7.21.4.1-1"), "names every other failing rule, sorted: " + font);
    assertNotNull(PdfUaIdentifierStep.blocker(Map.of("ISO 14289-1:2014 7.21.4.1-1", 3)), "without 5-1 there is nothing to add");
    assertNotNull(PdfUaIdentifierStep.blocker(Map.of()), "no failure at all: nothing to add");
  }

  @Test
  void theStructureChecksBlockOnlyWhenTheyReportSomethingAndAnUnreadableCopyGetsNoIdentifier() throws Exception {
    Path plain = dir.resolve("plain.pdf");
    Files.write(plain, SyntheticPdfFactory.build(Options.plain()));
    assertNull(PdfUaIdentifierStep.structureBlocker(plain), "no structure tree: nothing to report");
    String unreadable = PdfUaIdentifierStep.structureBlocker(dir.resolve("missing.pdf"));
    assertNotNull(unreadable);
    assertTrue(unreadable.startsWith("not written: the copy could not be read"), unreadable);
  }

  @Test
  void addsPdfUaPartOneToTheExistingPacketAndKeepsItsProperties() throws Exception {
    Path in = dir.resolve("in.pdf");
    Path out = dir.resolve("out.pdf");
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.build(Options.plain().language("en").infoTitle("Quarterly R&D")))) {
      byte[] packet = XmpMetadataRule.packet(doc).getBytes(StandardCharsets.UTF_8);
      doc.getDocumentCatalog().setMetadata(new PDMetadata(doc, new ByteArrayInputStream(packet)));
      doc.save(in.toFile());
    }
    byte[] before = Files.readAllBytes(in);

    PdfUaIdentifierStep.writeIdentifiedCopy(in, out);

    assertEquals(0, java.util.Arrays.compare(before, Files.readAllBytes(in)), "the input copy is never modified");
    try (PDDocument doc = Loader.loadPDF(out.toFile())) {
      byte[] xmp = doc.getDocumentCatalog().getMetadata().toByteArray();
      XMPMetadata metadata = parse(xmp);
      XMPSchema uaid = metadata.getSchema(PdfUaIdentifierStep.NAMESPACE);
      assertNotNull(uaid, new String(xmp, StandardCharsets.UTF_8));
      assertEquals("pdfuaid", uaid.getPrefix());
      assertEquals("1", ((org.apache.xmpbox.type.AbstractSimpleProperty) uaid.getProperty("part")).getStringValue());
      assertEquals("Quarterly R&D", metadata.getDublinCoreSchema().getTitle("x-default"));
      assertEquals(java.util.List.of("en"), metadata.getDublinCoreSchema().getLanguages());
      String text = new String(xmp, StandardCharsets.UTF_8);
      assertFalse(text.contains("pdfaid"), "no PDF/A identifier: " + text);
    }
  }

  /** PAC "Title in XMP metadata": a copy whose dc:title x-default is blank or absent never gets the identifier (uploads and generated). */
  @Test
  void aBlankOrMissingXmpTitleBlocksTheIdentifier() throws Exception {
    assertNull(PdfUaIdentifierStep.titleBlocker(withPacket("titled.pdf", TitleRuleTest.packet("Annual report"))));
    for (String packet : new String[] {TitleRuleTest.EMPTY_DC_TITLE, TitleRuleTest.packet("   "), TitleRuleTest.packet(null)}) {
      assertEquals("not written: the XMP metadata has no title", PdfUaIdentifierStep.titleBlocker(withPacket("blank.pdf", packet)), packet);
    }
    Path none = dir.resolve("none.pdf");
    Files.write(none, SyntheticPdfFactory.build(Options.plain()));
    assertEquals("not written: the XMP metadata has no title", PdfUaIdentifierStep.titleBlocker(none));
  }

  private Path withPacket(String name, String packet) throws IOException {
    Path path = dir.resolve(name);
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.build(Options.plain()))) {
      doc.getDocumentCatalog().setMetadata(new PDMetadata(doc, new ByteArrayInputStream(packet.getBytes(StandardCharsets.UTF_8))));
      doc.save(path.toFile());
    }
    return path;
  }

  @Test
  void aCopyWithoutMetadataIsRefused() throws IOException {
    Path in = dir.resolve("in.pdf");
    Files.write(in, SyntheticPdfFactory.build(Options.plain()));
    assertThrows(IOException.class, () -> PdfUaIdentifierStep.writeIdentifiedCopy(in, dir.resolve("out.pdf")));
    assertFalse(Files.exists(dir.resolve("out.pdf")));
  }

  private static XMPMetadata parse(byte[] xmp) throws IOException {
    try {
      DomXmpParser parser = new DomXmpParser();
      parser.setStrictParsing(false);
      return parser.parse(xmp);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private static WorkerRequest request(WorkerRequest.Mode mode, WorkerRequest.Origin origin) {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, mode, "ua1", null, null, "page.pdf", origin,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }
}
