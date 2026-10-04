package com.koldera.pdf.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.ArtifactKind;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import com.koldera.pdf.pipeline.PdfProcessor;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.rules.DisplayDocumentTitleRule;
import com.koldera.pdf.rules.ExplicitLanguageRule;
import com.koldera.pdf.validation.ValidationOutcome;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: real PDFBox, real veraPDF ({@code VERAPDF_CMD} or PATH), real CLI envelope, contract
 * schema validation of the written result, independent re-validation of the produced PDF, and one
 * run through the packaged {@code target/koldera-pdf-engine.jar} as a subprocess (P13).
 */
class PdfWorkerCliIT {

  private static final String REQUEST_TEMPLATE =
      """
      {
        "specVersion": "1.0",
        "jobId": "123e4567-e89b-42d3-a456-426614174000",
        "inputPath": "/work/in/document.pdf",
        "outputDirectory": "/work/out",
        "mode": "%s",
        "profile": "ua1",
        "explicitLanguage": %s,
        "limits": { "maxBytes": 10485760, "maxPages": 20, "timeoutSeconds": 120 }
      }
      """;

  @TempDir Path work;
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

  @BeforeEach
  void layout() throws IOException {
    Files.createDirectories(work.resolve("in"));
    Files.createDirectories(work.resolve("out"));
  }

  @Test
  void fixOnMissingLangAndTitleFixtureAppliesTheLanguageRuleAndSurvivesIndependentRevalidation() throws IOException {
    byte[] original = install(SyntheticPdfFactory.build(Options.plain().infoTitle("Fixture Title").displayDocTitle(false)));
    writeRequest("fix", "\"en-US\"");

    int exit = realMain().run(args());

    assertEquals(0, exit, stderr.toString(StandardCharsets.UTF_8));
    String json = Files.readString(work.resolve("result.json"), StandardCharsets.UTF_8);
    assertTrue(ContractSchemas.validateResult(json).isEmpty(), () -> ContractSchemas.validateResult(json).toString());
    WorkerResult result = WorkerResultCodec.fromJson(json);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    for (var rule : java.util.List.of(WorkerResult.RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, WorkerResult.RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1)) {
      assertTrue(result.actions().stream().anyMatch(a -> a.ruleId() == rule && a.outcome() == ActionOutcome.APPLIED), rule + " in " + result.actions());
    }
    assertEquals(ValidationStatus.FAILED, result.baseline().status(), "untagged input fails UA-1");
    assertEquals(ValidationStatus.FAILED, result.finalValidation().status(), "fix copies still fail machine checks on this fixture");
    assertTrue(result.baseline().failedRules().size() > result.finalValidation().failedRules().size());
    assertTrue(result.baseline().failedRules().stream().anyMatch(r -> r.ruleId().equals("ISO 14289-1:2014 7.2-34")));
    assertTrue(result.finalValidation().failedRules().stream().noneMatch(r -> r.ruleId().equals("ISO 14289-1:2014 7.2-34")));
    assertTrue(result.finalValidation().failedRules().stream().noneMatch(r -> r.ruleId().equals("ISO 14289-1:2014 7.1-10")));

    // Artifacts: hashes and sizes match the files on disk; original untouched.
    assertEquals(PdfPreflight.sha256Hex(original), result.originalSha256());
    assertArrayEquals(original, Files.readAllBytes(work.resolve("in/document.pdf")));
    for (WorkerResult.Artifact artifact : result.artifacts()) {
      Path file = artifact.path().startsWith("/work/in/") ? work.resolve("in/document.pdf") : work.resolve("out").resolve(artifact.path().substring("/work/out/".length()));
      byte[] bytes = Files.readAllBytes(file);
      assertEquals(PdfPreflight.sha256Hex(bytes), artifact.sha256(), artifact.path());
      assertEquals(bytes.length, artifact.bytes(), artifact.path());
    }
    assertEquals(
        List.of(ArtifactKind.ORIGINAL, ArtifactKind.BASELINE_REPORT, ArtifactKind.REMEDIATED, ArtifactKind.FINAL_REPORT),
        result.artifacts().stream().map(WorkerResult.Artifact::kind).toList());

    // Independent check: run veraPDF ourselves on the published file and compare with finalValidation.
    Path remediated = work.resolve("out/remediated.pdf");
    ValidationOutcome independent =
        VeraPdfCliValidator.fromEnvironment().validate(remediated, work.resolve("independent.json"), Duration.ofSeconds(120));
    assertEquals(ValidationStatus.FAILED, independent.status());
    assertEquals(
        result.finalValidation().failedRules().stream().map(r -> r.ruleId() + "=" + r.count()).toList(),
        independent.failedRuleCounts().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    try (PDDocument doc = Loader.loadPDF(remediated.toFile())) {
      assertEquals("en-US", doc.getDocumentCatalog().getLanguage());
      assertTrue(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
      assertEquals("Fixture Title", doc.getDocumentInformation().getTitle());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
    }
    assertArrayEquals(renderPage1(original), renderPage1(Files.readAllBytes(remediated)), "rendered appearance must be pixel-identical");

    // Output never advertises certification.
    assertTrue(result.manualReviewRequired());
    String lower = json.toLowerCase(Locale.ROOT);
    assertFalse(lower.contains("certif"), "no certificate wording");
    assertFalse(lower.contains("\"" + com.koldera.pdf.validation.VeraPdfCliValidator.PASS_FLAG_FIELD + "\""), "no pass flag in worker result");
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("MANUAL_REVIEW_REQUIRED")));
    assertLogIsContentFree();
  }

  @Test
  void fixOnUntaggedUntitledFixtureTitlesTagsAndWritesXmpAndVeraPdfAgrees() throws IOException {
    byte[] original = install(SyntheticPdfFactory.simpleUntagged());
    Files.writeString(
        work.resolve("request.json"),
        """
        {
          "specVersion": "1.0",
          "jobId": "123e4567-e89b-42d3-a456-426614174000",
          "inputPath": "/work/in/document.pdf",
          "outputDirectory": "/work/out",
          "mode": "fix",
          "profile": "ua1",
          "explicitLanguage": "en-US",
          "sourceFileName": "annual-report-2025.pdf",
          "limits": { "maxBytes": 10485760, "maxPages": 20, "timeoutSeconds": 120 }
        }
        """);

    int exit = productionMain().run(args());

    assertEquals(0, exit, stderr.toString(StandardCharsets.UTF_8));
    WorkerResult result = readResult();
    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    List<String> baseline = result.baseline().failedRules().stream().map(r -> r.ruleId()).toList();
    List<String> fin = result.finalValidation().failedRules().stream().map(r -> r.ruleId()).toList();
    assertTrue(baseline.contains("ISO 14289-1:2014 7.1-10"), baseline.toString());
    assertFalse(fin.contains("ISO 14289-1:2014 7.1-9"), fin.toString());
    assertFalse(fin.contains("ISO 14289-1:2014 7.1-10"), fin.toString());
    // The non-embedded Helvetica gets a metric-compatible substitute, so nothing but 5-1 is left
    // and the identifier step writes pdfuaid:part=1 (a machine-check declaration; a person still reviews the document).
    assertTrue(fin.isEmpty(), "the identified copy validates clean: " + fin);
    assertTrue(result.actions().stream()
        .anyMatch(a -> a.ruleId() == WorkerResult.RuleId.PDF_UA_IDENTIFIER_V1 && a.outcome() == WorkerResult.ActionOutcome.APPLIED), result.actions().toString());
    assertTrue(result.actions().stream()
        .anyMatch(a -> a.ruleId() == WorkerResult.RuleId.PDF_FONT_EMBEDDING_V1 && a.outcome() == WorkerResult.ActionOutcome.APPLIED), result.actions().toString());
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY") && f.needsHumanReview()));
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("FONT_EMBEDDED_SUBSTITUTE") && f.needsHumanReview()));
    assertTrue(result.manualReviewRequired());
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("TITLE_FROM_FILE_NAME") && f.needsHumanReview()));
    assertArrayEquals(original, Files.readAllBytes(work.resolve("in/document.pdf")));
    try (PDDocument doc = Loader.loadPDF(work.resolve("out/remediated.pdf").toFile())) {
      assertEquals("annual-report-2025", doc.getDocumentInformation().getTitle());
      assertTrue(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
      assertNotNull(doc.getDocumentCatalog().getStructureTreeRoot());
    }
    String log = stderr.toString(StandardCharsets.UTF_8);
    assertFalse(log.contains("annual-report-2025"), log);
    assertLogIsContentFree();
  }

  @Test
  void analyzeOnUntaggedFixtureCapturesExplicitUa1BaselineWithoutOutput() throws IOException {
    install(SyntheticPdfFactory.simpleUntagged());
    writeRequest("analyze", "null");

    assertEquals(0, realMain().run(args()));

    WorkerResult result = readResult();
    assertEquals(Status.COMPLETED, result.status());
    assertEquals(ValidationStatus.FAILED, result.baseline().status());
    assertTrue(result.baseline().failedRules().size() >= 5, result.baseline().failedRules().toString());
    assertNull(result.finalValidation());
    assertEquals(RemediationStatus.NOT_REQUESTED, result.remediationStatus());
    assertFalse(Files.exists(work.resolve("out/remediated.pdf")));
    String raw = Files.readString(work.resolve("out/baseline-report.json"));
    assertTrue(raw.contains("PDF/UA-1 validation profile"), "explicit ua1 profile in raw report");
  }

  @Test
  void signedFixIsRefusedWithoutOutputOrDigestChange() throws IOException {
    byte[] original = install(SyntheticPdfFactory.signed());
    writeRequest("fix", "\"en-US\"");

    assertEquals(0, realMain().run(args()));

    WorkerResult result = readResult();
    assertEquals(Status.UNSUPPORTED, result.status());
    assertEquals(ErrorCode.UNSUPPORTED_SIGNED_MUTATION, result.error().code());
    assertEquals(ValidationStatus.NOT_RUN, result.baseline().status());
    assertEquals(PdfPreflight.sha256Hex(original), result.originalSha256());
    assertArrayEquals(original, Files.readAllBytes(work.resolve("in/document.pdf")));
    assertFalse(Files.exists(work.resolve("out/remediated.pdf")));
    assertFalse(Files.exists(work.resolve("out/baseline-report.json")));
  }

  @Test
  void corruptInputIsTypedRejectionWithExitZero() throws IOException {
    install(SyntheticPdfFactory.corrupt());
    writeRequest("fix", "\"en-US\"");

    assertEquals(0, realMain().run(args()));

    WorkerResult result = readResult();
    assertEquals(Status.UNSUPPORTED, result.status());
    assertEquals(ErrorCode.INVALID_INPUT, result.error().code());
    assertNotNull(result.originalSha256());
    assertFalse(Files.exists(work.resolve("out/remediated.pdf")));
  }

  @Test
  void packagedJarRunsTheSameContractAsASubprocess() throws IOException, InterruptedException {
    Path jar = Path.of("target", "koldera-pdf-engine.jar").toAbsolutePath();
    assertTrue(Files.isRegularFile(jar), "run via `mvn verify` so the shaded jar exists: " + jar);
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Jar Fixture")));
    writeRequest("fix", "\"uk\"");
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    Process process =
        new ProcessBuilder(
                javaBin,
                "-jar",
                jar.toString(),
                "process",
                "--request", work.resolve("request.json").toString(),
                "--result", work.resolve("result.json").toString(),
                "--work-root", work.toString())
            .redirectErrorStream(true)
            .start();
    process.getOutputStream().close();
    byte[] output = process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(180, TimeUnit.SECONDS), "jar run timed out");
    String log = new String(output, StandardCharsets.UTF_8);

    assertEquals(0, process.exitValue(), log);
    WorkerResult result = readResult();
    assertEquals(Status.COMPLETED, result.status(), log + result.error());
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertTrue(Files.exists(work.resolve("out/remediated.pdf")));
    assertTrue(log.contains("\"event\":\"result_written\""), log);
    assertFalse(log.contains(work.toString()), "no host paths in log: " + log);
    assertFalse(log.contains("SLF4J"), "no library banner noise: " + log);
    assertTrue(result.engineVersion().contains("pdfbox 3.0.8"), result.engineVersion());
  }

  /** 72 dpi RGB raster of page 1; the two metadata fixes must never change what a viewer draws. */
  private static int[] renderPage1(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      BufferedImage image = new PDFRenderer(doc).renderImage(0);
      return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
  }

  private Main realMain() {
    return new Main(
        new PdfProcessor(
            new PdfPreflight(),
            VeraPdfCliValidator.fromEnvironment(),
            List.of(new ExplicitLanguageRule(), new DisplayDocumentTitleRule())),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  /** The production wiring's rule list (all modes), as {@code dispatch} builds it. */
  private Main productionMain() {
    return new Main(
        new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  private byte[] install(byte[] bytes) throws IOException {
    Files.write(work.resolve("in/document.pdf"), bytes);
    return bytes;
  }

  private void writeRequest(String mode, String languageJson) throws IOException {
    Files.writeString(work.resolve("request.json"), REQUEST_TEMPLATE.formatted(mode, languageJson));
  }

  private String[] args() {
    return new String[] {
      "process",
      "--request", work.resolve("request.json").toString(),
      "--result", work.resolve("result.json").toString(),
      "--work-root", work.toString()
    };
  }

  private WorkerResult readResult() throws IOException {
    String json = Files.readString(work.resolve("result.json"), StandardCharsets.UTF_8);
    assertTrue(ContractSchemas.validateResult(json).isEmpty(), () -> ContractSchemas.validateResult(json).toString());
    return WorkerResultCodec.fromJson(json);
  }

  private void assertLogIsContentFree() {
    String log = stderr.toString(StandardCharsets.UTF_8);
    assertFalse(log.contains(work.toString()), log);
    assertFalse(log.contains(SyntheticPdfFactory.FILLER_TEXT), log);
    assertFalse(log.contains("Fixture Title"), log);
  }
}
