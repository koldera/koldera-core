package com.koldera.pdf.rules;

import static org.junit.jupiter.api.Assertions.*;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

public class TitleResolverTest {

  public static WorkerRequest fix(String explicitTitle, String fileName) {
    return new WorkerRequest("1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1",
        null, explicitTitle, fileName, new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  private static PDDocument load(Options o) throws Exception { return Loader.loadPDF(SyntheticPdfFactory.build(o)); }

  @Test
  void userTitleWinsOverAnExistingTitle() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Old"))) {
      var r = TitleResolver.resolve(d, fix("  Annual report  ", "a.pdf")).orElseThrow();
      assertEquals("Annual report", r.title());
      assertEquals(TitleResolver.TitleSource.USER, r.source());
    }
  }

  @Test
  void existingTitleIsKeptWithoutUserInput() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("Quarterly Report"))) {
      var r = TitleResolver.resolve(d, fix(null, "q.pdf")).orElseThrow();
      assertEquals("Quarterly Report", r.title());
      assertEquals(TitleResolver.TitleSource.DOCUMENT, r.source());
    }
  }

  @Test
  void blankExistingTitleFallsBackToTheFileName() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("   "))) {
      var r = TitleResolver.resolve(d, fix(null, "annual-report-2025.pdf")).orElseThrow();
      assertEquals("annual-report-2025", r.title());
      assertEquals(TitleResolver.TitleSource.FILE_NAME, r.source());
    }
  }

  @Test
  void placeholderFileNamesAreNotTitles() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      assertTrue(TitleResolver.resolve(d, fix(null, "document.pdf")).isEmpty());
      assertTrue(TitleResolver.resolve(d, fix(null, "DOCUMENT.PDF")).isEmpty());
      assertTrue(TitleResolver.resolve(d, fix(null, "page.html")).isEmpty());
      assertEquals("documents", TitleResolver.resolve(d, fix(null, "documents.pdf")).orElseThrow().title());
    }
  }

  @Test
  void xmpTitleIsTheDocumentTitleWhenInfoIsBlank() throws Exception {
    try (PDDocument d = load(Options.plain().infoTitle("  "))) {
      TitleRuleTest.attachPacket(d, "Real title");
      var r = TitleResolver.resolve(d, fix(null, "annual-report.pdf")).orElseThrow();
      assertEquals("Real title", r.title());
      assertEquals(TitleResolver.TitleSource.XMP, r.source());
    }
  }

  @Test
  void unparseableXmpFallsThroughToTheFileName() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      TitleRuleTest.attachRaw(d, "not xml");
      assertEquals(TitleResolver.TitleSource.FILE_NAME,
          TitleResolver.resolve(d, fix(null, "annual-report.pdf")).orElseThrow().source());
    }
  }

  @Test
  void nothingUsableMeansNoTitle() throws Exception {
    try (PDDocument d = load(Options.plain())) {
      assertTrue(TitleResolver.resolve(d, fix(null, ".pdf")).isEmpty());
      assertTrue(TitleResolver.resolve(d, fix(null, null)).isEmpty());
      assertTrue(TitleResolver.resolve(d, fix("   ", null)).isEmpty());
    }
  }
}
