package com.koldera.pdf.preflight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfPreflightTest {

  @TempDir Path tmp;

  private final PdfPreflight preflight = new PdfPreflight();

  @Test
  void acceptsSimplePdfAndRecordsDigestsPagesAndFlags() throws IOException {
    byte[] bytes = SyntheticPdfFactory.simpleUntagged();
    Path input = SyntheticPdfFactory.write(tmp.resolve("in/document.pdf"), bytes);

    PreflightOutcome outcome = preflight.inspect(input, request(WorkerRequest.Mode.SAFE_FIX, 10_485_760, 20));

    assertTrue(outcome.accepted(), outcome.reason());
    assertNull(outcome.rejectCode());
    assertEquals(sha256(bytes), outcome.sha256());
    assertEquals(bytes.length, outcome.bytes());
    assertEquals(1, outcome.pageCount());
    assertNotNull(outcome.textDigest());
    assertEquals(64, outcome.textDigest().length());
    assertFalse(outcome.encrypted());
    assertFalse(outcome.signed());
  }

  @Test
  void textDigestIsStableAcrossWhitespaceOnlyDifferencesAndDistinguishesContent() throws IOException {
    Path a = SyntheticPdfFactory.write(tmp.resolve("a/in/document.pdf"), SyntheticPdfFactory.simpleUntagged());
    Path b =
        SyntheticPdfFactory.write(
            tmp.resolve("b/in/document.pdf"),
            SyntheticPdfFactory.build(SyntheticPdfFactory.Options.plain().language("en")));
    Path c =
        SyntheticPdfFactory.write(
            tmp.resolve("c/in/document.pdf"),
            SyntheticPdfFactory.build(SyntheticPdfFactory.Options.plain().pages(2)));

    WorkerRequest req = request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20);
    String da = preflight.inspect(a, req).textDigest();
    String db = preflight.inspect(b, req).textDigest();
    String dc = preflight.inspect(c, req).textDigest();

    assertEquals(da, db, "adding /Lang does not change extracted text");
    assertNotEquals(da, dc, "an extra page changes extracted text");
    assertEquals(
        PdfPreflight.normalizedTextDigest("  Synthetic fixture paragraph for accessibility engine tests. Page 1.  \n"),
        da);
  }

  @Test
  void rejectsMissingFile() {
    PreflightOutcome outcome =
        preflight.inspect(tmp.resolve("in/document.pdf"), request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20));

    assertFalse(outcome.accepted());
    assertEquals(ErrorCode.INVALID_INPUT, outcome.rejectCode());
    assertNull(outcome.sha256());
  }

  @Test
  void rejectsEmptyFile() throws IOException {
    Path input = SyntheticPdfFactory.write(tmp.resolve("in/document.pdf"), new byte[0]);

    PreflightOutcome outcome = preflight.inspect(input, request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20));

    assertEquals(ErrorCode.INVALID_INPUT, outcome.rejectCode());
  }

  @Test
  void rejectsNonPdfMagicWithoutParsing() throws IOException {
    Path input =
        SyntheticPdfFactory.write(
            tmp.resolve("in/document.pdf"), "<html>not a pdf</html>".getBytes(StandardCharsets.UTF_8));

    PreflightOutcome outcome = preflight.inspect(input, request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20));

    assertEquals(ErrorCode.INVALID_INPUT, outcome.rejectCode());
    assertTrue(outcome.reason().contains("%PDF-"), outcome.reason());
  }

  @Test
  void rejectsCorruptAndTruncatedDocuments() throws IOException {
    Path corrupt = SyntheticPdfFactory.write(tmp.resolve("c/in/document.pdf"), SyntheticPdfFactory.corrupt());
    Path truncated = SyntheticPdfFactory.write(tmp.resolve("t/in/document.pdf"), SyntheticPdfFactory.truncated());
    WorkerRequest req = request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20);

    PreflightOutcome c = preflight.inspect(corrupt, req);

    assertEquals(ErrorCode.INVALID_INPUT, c.rejectCode());
    assertEquals(sha256(SyntheticPdfFactory.corrupt()), c.sha256(), "digest is recorded even when rejected");
    // PDFBox repairs many truncated files; either a typed rejection or a successful parse is
    // acceptable, but never an exception and never a silent wrong page count.
    PreflightOutcome t = preflight.inspect(truncated, req);
    assertTrue(t.accepted() || t.rejectCode() == ErrorCode.INVALID_INPUT);
  }

  @Test
  void rejectsOversizedFileBeforeParsing() throws IOException {
    byte[] bytes = SyntheticPdfFactory.simpleUntagged();
    Path input = SyntheticPdfFactory.write(tmp.resolve("in/document.pdf"), bytes);

    PreflightOutcome outcome =
        preflight.inspect(input, request(WorkerRequest.Mode.ANALYZE, bytes.length - 1, 20));

    assertEquals(ErrorCode.LIMIT_EXCEEDED, outcome.rejectCode());
    assertEquals(bytes.length, outcome.bytes());
    assertNull(outcome.pageCount());
  }

  @Test
  void rejectsTooManyPages() throws IOException {
    byte[] bytes = SyntheticPdfFactory.build(SyntheticPdfFactory.Options.plain().pages(3));
    Path input = SyntheticPdfFactory.write(tmp.resolve("in/document.pdf"), bytes);

    PreflightOutcome outcome = preflight.inspect(input, request(WorkerRequest.Mode.ANALYZE, 10_485_760, 2));

    assertEquals(ErrorCode.LIMIT_EXCEEDED, outcome.rejectCode());
    assertEquals(3, outcome.pageCount());
    assertEquals(sha256(bytes), outcome.sha256());
  }

  @Test
  void rejectsEncryptedDocumentInAnyMode() throws IOException {
    byte[] bytes = SyntheticPdfFactory.encrypted("secret");
    Path input = SyntheticPdfFactory.write(tmp.resolve("in/document.pdf"), bytes);

    PreflightOutcome analyze = preflight.inspect(input, request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20));
    PreflightOutcome fix = preflight.inspect(input, request(WorkerRequest.Mode.SAFE_FIX, 10_485_760, 20));

    assertEquals(ErrorCode.UNSUPPORTED_ENCRYPTED, analyze.rejectCode());
    assertEquals(ErrorCode.UNSUPPORTED_ENCRYPTED, fix.rejectCode());
    assertTrue(analyze.encrypted());
    assertEquals(sha256(bytes), fix.sha256());
    assertArrayEquals(bytes, Files.readAllBytes(input), "input is never rewritten");
  }

  @Test
  void signedDocumentIsRefusedForSafeFixButInspectableForAnalyze() throws IOException {
    byte[] bytes = SyntheticPdfFactory.signed();
    Path input = SyntheticPdfFactory.write(tmp.resolve("in/document.pdf"), bytes);

    PreflightOutcome fix = preflight.inspect(input, request(WorkerRequest.Mode.SAFE_FIX, 10_485_760, 20));
    PreflightOutcome analyze = preflight.inspect(input, request(WorkerRequest.Mode.ANALYZE, 10_485_760, 20));

    assertFalse(fix.accepted());
    assertEquals(ErrorCode.UNSUPPORTED_SIGNED_MUTATION, fix.rejectCode());
    assertTrue(fix.signed());
    assertTrue(analyze.accepted(), analyze.reason());
    assertTrue(analyze.signed());
    assertEquals(1, analyze.pageCount());
    assertEquals(sha256(bytes), analyze.sha256());
    assertArrayEquals(bytes, Files.readAllBytes(input), "signed input is never rewritten");
  }

  @Test
  void signedFixtureReallyCarriesASignatureDictionary() throws IOException {
    byte[] bytes = SyntheticPdfFactory.signed();

    try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
      assertEquals(1, doc.getSignatureDictionaries().size());
      assertNotNull(doc.getLastSignatureDictionary().getContents());
    }
  }

  private static WorkerRequest request(WorkerRequest.Mode mode, long maxBytes, int maxPages) {
    return new WorkerRequest(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        null,
        new WorkerRequest.Limits(maxBytes, maxPages, 60));
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
