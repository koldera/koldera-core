package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.ArtifactKind;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.Resolution;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fonts.FontUnicodeRule;
import com.koldera.pdf.fonts.InvisibleTextFontRule;
import com.koldera.pdf.fonts.ToUnicodeCMapWriter;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import com.koldera.pdf.generated.GeneratedStructureRule;
import com.koldera.pdf.inference.InferenceSettings;
import com.koldera.pdf.inference.StructureInferenceRule;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.rules.BasicStructureRule;
import com.koldera.pdf.rules.DisplayDocumentTitleRule;
import com.koldera.pdf.rules.ExplicitLanguageRule;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.rules.TitleRule;
import com.koldera.pdf.rules.XmpMetadataRule;
import com.koldera.pdf.validation.PdfValidator;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfProcessorTest {

  private static final UUID JOB = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final String LANG_RULE = "ISO 14289-1:2014 7.2-34";
  private static final String TITLE_RULE = "ISO 14289-1:2014 7.1-10";
  private static final String STRUCT_RULE = "ISO 14289-1:2014 7.1-11";

  @TempDir Path root;
  private JobWorkspace workspace;
  private ScriptedValidator validator;
  private final List<RemediationRule> rules = List.of(new ExplicitLanguageRule(), new DisplayDocumentTitleRule());

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("in"));
    workspace = JobWorkspace.open(root);
    validator = new ScriptedValidator();
  }

  // ---- analyze ----------------------------------------------------------------------------

  @Test
  void analyzeRunsBaselineOnlyAndNeverWritesAnOutputPdf() throws IOException {
    byte[] original = install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.ANALYZE, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status());
    assertEquals(RemediationStatus.NOT_REQUESTED, result.remediationStatus());
    assertNull(result.finalValidation());
    assertEquals(ValidationStatus.FAILED, result.baseline().status());
    assertEquals(3, result.baseline().failedRules().size());
    assertEquals("/work/out/baseline-report.json", result.baseline().rawReportPath());
    assertEquals(1, validator.calls.size());
    assertEquals(workspace.inputFile(), validator.calls.get(0).pdf());
    assertEquals(List.of(ArtifactKind.ORIGINAL, ArtifactKind.BASELINE_REPORT), kinds(result));
    assertEquals(sha(original), result.originalSha256());
    assertEquals(sha(original), artifact(result, ArtifactKind.ORIGINAL).sha256());
    assertTrue(result.actions().isEmpty());
    assertTrue(result.manualReviewRequired());
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals(LANG_RULE)));
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("MANUAL_REVIEW_REQUIRED") && f.needsHumanReview()));
    assertNull(result.error());
    assertNoOutputPdf();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertSchemaValid(result);
  }

  @Test
  void analyzeFindingOrderIsMachineChecksThenImagesThenSigned() throws IOException {
    install(SyntheticPdfFactory.signed(SyntheticPdfFactory.build(Options.plain().image())));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.ANALYZE, null), workspace);

    assertEquals(Status.UNSUPPORTED, result.status());
    assertEquals(
        List.of(STRUCT_RULE, LANG_RULE, "IMAGES_REQUIRE_HUMAN_REVIEW", "DOCUMENT_SIGNED", "MANUAL_REVIEW_REQUIRED"),
        result.findings().stream().map(WorkerResult.Finding::ruleId).toList(),
        "analyze output order is part of the contract surface and stays as before");
  }

  @Test
  void analyzeOnSignedDocumentIsUnsupportedButStillReportsBaseline() throws IOException {
    install(SyntheticPdfFactory.signed());
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.ANALYZE, null), workspace);

    assertEquals(Status.UNSUPPORTED, result.status());
    assertEquals(ValidationStatus.FAILED, result.baseline().status());
    assertNull(result.error());
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("DOCUMENT_SIGNED")));
    assertNoOutputPdf();
    assertSchemaValid(result);
  }

  // ---- safe_fix ---------------------------------------------------------------------------

  @Test
  void safeFixWithTheProductionRuleListReportsExactlyTheTwoMetadataActions() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report").displayDocTitle(false)));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));
    BasicStructureRule structure = new BasicStructureRule();
    PdfProcessor production =
        new PdfProcessor(
            new PdfPreflight(),
            validator,
            List.of(new ExplicitLanguageRule(), new DisplayDocumentTitleRule(), structure, new XmpMetadataRule(structure)));

    WorkerResult result = production.process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(
        List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList(),
        "structure rules never appear in a safe_fix result");
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.APPLIED));
    try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(workspace.outputFile("remediated.pdf")))) {
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot());
      assertNull(doc.getDocumentCatalog().getMetadata());
    }
    assertSchemaValid(result);
  }

  @Test
  void safeFixWithNoApplicableRuleReturnsNoApplicableFixAndDoesNotRevalidate() throws IOException {
    byte[] original = install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, null), workspace);

    assertEquals(Status.COMPLETED, result.status());
    assertEquals(RemediationStatus.NO_APPLICABLE_FIX, result.remediationStatus());
    assertNull(result.finalValidation());
    assertEquals(2, result.actions().size());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.SKIPPED));
    assertEquals(1, validator.calls.size());
    assertEquals(List.of(ArtifactKind.ORIGINAL, ArtifactKind.BASELINE_REPORT), kinds(result));
    assertNoOutputPdf();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertSchemaValid(result);
  }

  @Test
  void safeFixAppliesBothRulesRevalidatesAndPublishesRemediatedCopy() throws IOException {
    byte[] original = install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report").displayDocTitle(false)));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(
        List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.APPLIED));
    assertNotNull(result.finalValidation());
    assertEquals(ValidationStatus.FAILED, result.finalValidation().status(), "still untagged: fewer failures, still failing");
    assertEquals("/work/out/final-report.json", result.finalValidation().rawReportPath());
    assertEquals(2, validator.calls.size());
    assertTrue(validator.calls.get(1).pdf().startsWith(workspace.scratchDirectory()), "final validation runs on the scratch candidate");
    assertEquals(
        List.of(ArtifactKind.ORIGINAL, ArtifactKind.BASELINE_REPORT, ArtifactKind.REMEDIATED, ArtifactKind.FINAL_REPORT),
        kinds(result));

    WorkerResult.Artifact remediated = artifact(result, ArtifactKind.REMEDIATED);
    assertEquals("/work/out/remediated.pdf", remediated.path());
    byte[] outBytes = Files.readAllBytes(workspace.outputFile("remediated.pdf"));
    assertEquals(sha(outBytes), remediated.sha256());
    assertEquals(outBytes.length, remediated.bytes());
    assertFalse(sha(outBytes).equals(sha(original)));
    try (PDDocument doc = Loader.loadPDF(outBytes)) {
      assertEquals("en-US", doc.getDocumentCatalog().getLanguage());
      assertTrue(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
      assertEquals("Report", doc.getDocumentInformation().getTitle());
      assertEquals(1, doc.getNumberOfPages());
      assertNull(doc.getDocumentCatalog().getStructureTreeRoot(), "no fake structure tree");
      assertNull(doc.getDocumentCatalog().getMarkInfo(), "no fake MarkInfo");
      assertNull(doc.getDocumentCatalog().getMetadata(), "no injected XMP claim");
    }
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()), "original immutable");
    assertTrue(result.manualReviewRequired());
    assertNull(result.error());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void appliedSafeFixResolvesEachMachineCheckAgainstTheCopy() throws IOException {
    // The language rule is fixed by the copy, the title rule too; the missing structure tree is not a safe_fix concern.
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report").displayDocTitle(false)));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    assertEquals(Resolution.FIXED_IN_COPY, finding(result, LANG_RULE).resolution());
    assertEquals(Resolution.FIXED_IN_COPY, finding(result, TITLE_RULE).resolution());
    assertEquals(Resolution.STILL_FAILING, finding(result, STRUCT_RULE).resolution());
    assertNull(finding(result, "MANUAL_REVIEW_REQUIRED").resolution(), "review items carry no machine resolution");
    assertEquals(List.of(TITLE_RULE, STRUCT_RULE, LANG_RULE, "MANUAL_REVIEW_REQUIRED"), result.findings().stream().map(WorkerResult.Finding::ruleId).toList(), "sorted rule ids, unchanged by the resolution");
    String json = WorkerResultCodec.toJson(result);
    assertTrue(json.contains("\"resolution\" : \"fixed_in_copy\""), json);
    assertTrue(json.contains("\"resolution\" : \"still_failing\""), json);
    assertSchemaValid(result);
  }

  @Test
  void analyzeAndNoApplicableFixRunsCarryNoResolution() throws IOException {
    install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    WorkerResult analyze = processor().process(request(WorkerRequest.Mode.ANALYZE, "en-US"), workspace);

    install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    WorkerResult skipped = processor().process(request(WorkerRequest.Mode.SAFE_FIX, null), workspace);

    assertEquals(RemediationStatus.NOT_REQUESTED, analyze.remediationStatus());
    assertEquals(RemediationStatus.NO_APPLICABLE_FIX, skipped.remediationStatus());
    assertTrue(analyze.findings().stream().allMatch(f -> f.resolution() == null), analyze.findings().toString());
    assertTrue(skipped.findings().stream().allMatch(f -> f.resolution() == null), skipped.findings().toString());
    assertTrue(WorkerResultCodec.toJson(analyze).contains("\"resolution\" : null"));
  }

  @Test
  void rolledBackSafeFixCarriesNoResolution() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1, "ISO 14289-1:2014 9.9-9", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertTrue(result.findings().stream().allMatch(f -> f.resolution() == null), result.findings().toString());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().contains("9.9-9")), "nothing was published, so the copy's new failure is not a finding");
  }

  @Test
  void aSafeFixWhosePublishFailsRollsBackWithoutAResolution() throws IOException {
    // A non-empty directory where remediated.pdf must land makes the final move fail after the gate passed.
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report").displayDocTitle(false)));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));
    Path blocker = workspace.outputFile("remediated.pdf");
    Files.createDirectories(blocker);
    Files.writeString(blocker.resolve("occupied"), "x");

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus(), String.valueOf(result.error()));
    assertEquals(WorkerResult.ErrorCode.WORKER_ERROR, result.error().code());
    assertTrue(result.findings().stream().allMatch(f -> f.resolution() == null), result.findings().toString());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == WorkerResult.ActionOutcome.ROLLED_BACK), result.actions().toString());
  }

  private static WorkerResult.Finding finding(WorkerResult result, String ruleId) {
    return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).findFirst().orElseThrow(() -> new AssertionError("no finding " + ruleId + " in " + result.findings()));
  }

  @Test
  void retryOnAlreadyFixedInputIsNoApplicableFixWithNoNewArtifact() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().language("en-US").infoTitle("Report").displayDocTitle(true)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status());
    assertEquals(RemediationStatus.NO_APPLICABLE_FIX, result.remediationStatus());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.SKIPPED));
    assertTrue(result.actions().stream().allMatch(a -> a.reason().contains("already")), result.actions().toString());
    assertEquals(1, validator.calls.size());
    assertNoOutputPdf();
    assertSchemaValid(result);
  }

  @Test
  void alreadyCompliantInputIsNotRewritten() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().language("en-US").infoTitle("Report").displayDocTitle(true)));
    validator.enqueue(passed());

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status());
    assertEquals(ValidationStatus.PASSED, result.baseline().status());
    assertEquals(RemediationStatus.NO_APPLICABLE_FIX, result.remediationStatus());
    assertTrue(result.manualReviewRequired(), "passing machine checks is not accessibility");
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("MANUAL_REVIEW_REQUIRED")));
    assertNoOutputPdf();
  }

  // ---- validator failures -----------------------------------------------------------------

  @Test
  void baselineValidatorUnavailableFailsJobWithoutPublishing() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(ValidationOutcome.error(ErrorCode.VALIDATOR_UNAVAILABLE, "no binary", null));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.VALIDATOR_UNAVAILABLE, result.error().code());
    assertEquals(ValidationStatus.ERROR, result.baseline().status());
    assertNull(result.finalValidation());
    assertEquals(RemediationStatus.SKIPPED, result.remediationStatus());
    assertEquals(List.of(ArtifactKind.ORIGINAL), kinds(result));
    assertNoOutputPdf();
    assertSchemaValid(result);
  }

  @Test
  void finalValidatorErrorRollsBack() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(ValidationOutcome.error(ErrorCode.VALIDATOR_ERROR, "timeout", null));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, result.error().code());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertEquals(ValidationStatus.ERROR, result.finalValidation().status());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.ROLLED_BACK), result.actions().toString());
    assertFalse(kinds(result).contains(ArtifactKind.REMEDIATED));
    assertNoOutputPdf();
    assertScratchClean();
    assertSchemaValid(result);
  }

  // ---- regression gates -------------------------------------------------------------------

  @Test
  void newlyFailingRuleAfterFixIsIntegrityRegression() throws IOException {
    byte[] original = install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1, "ISO 14289-1:2014 9.9-9", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().contains("9.9-9"), result.error().message());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.ROLLED_BACK));
    assertNotNull(result.finalValidation());
    assertEquals(List.of(ArtifactKind.ORIGINAL, ArtifactKind.BASELINE_REPORT, ArtifactKind.FINAL_REPORT), kinds(result));
    assertNoOutputPdf();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertTrue(result.manualReviewRequired());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void increasedFailureCountForExistingRuleIsIntegrityRegression() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(STRUCT_RULE, 2)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertNoOutputPdf();
  }

  @Test
  void unchangedFailureSetIsNotARegressionButStillPublishes() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status());
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
  }

  @Test
  void ruleThatChangesPageCountIsRolledBackBeforeFinalValidation() throws IOException {
    byte[] original = install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));
    RemediationRule pageAdder =
        new RemediationRule() {
          @Override
          public RuleId id() {
            return RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1;
          }

          @Override
          public RuleDecision inspect(PDDocument document, WorkerRequest request) {
            return RuleDecision.apply("test double", null, "x");
          }

          @Override
          public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
            document.addPage(new PDPage());
            return new WorkerResult.Action(id(), ActionOutcome.APPLIED, "added a page", null, "x");
          }
        };

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, List.of(pageAdder)).process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().contains("page"), result.error().message());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertEquals(1, validator.calls.size(), "final validation is not even attempted");
    assertNull(result.finalValidation());
    assertNoOutputPdf();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void ruleThatLeavesAnInconsistentParentTreeIsRolledBackBeforeFinalValidation() throws IOException {
    byte[] original = install(SyntheticPdfFactory.untaggedArticle());
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));
    RemediationRule tagsThenBreaksParentTree =
        new RemediationRule() {
          @Override
          public RuleId id() {
            return RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1;
          }

          @Override
          public RuleDecision inspect(PDDocument document, WorkerRequest request) {
            return RuleDecision.apply("test double", null, "x");
          }

          @Override
          public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
            try {
              com.koldera.pdf.structure.ContentTagger.apply(document, com.koldera.pdf.structure.ContentTagger.analyze(document));
            } catch (IOException | com.koldera.pdf.structure.ContentTagger.UnsupportedContentException e) {
              throw new AssertionError(e);
            }
            COSDictionary root = document.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
            org.apache.pdfbox.cos.COSArray nums = ((COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE)).getCOSArray(COSName.NUMS);
            ((org.apache.pdfbox.cos.COSArray) nums.getObject(1)).set(0, org.apache.pdfbox.cos.COSNull.NULL);
            return new WorkerResult.Action(id(), ActionOutcome.APPLIED, "tagged with a hole in the ParentTree", null, "x");
          }
        };

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, List.of(tagsThenBreaksParentTree)).process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().startsWith("copy integrity: "), result.error().message());
    assertTrue(result.error().message().contains("ParentTree"), result.error().message());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertEquals(1, validator.calls.size(), "final validation is not even attempted");
    assertNoOutputPdf();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void ruleReportingFailedApplyIsRolledBackAsWorkerError() throws IOException {
    install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));
    RemediationRule broken =
        new RemediationRule() {
          @Override
          public RuleId id() {
            return RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1;
          }

          @Override
          public RuleDecision inspect(PDDocument document, WorkerRequest request) {
            return RuleDecision.apply("test double", null, "x");
          }

          @Override
          public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
            return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not write", null, "x");
          }
        };

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, List.of(broken)).process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.WORKER_ERROR, result.error().code());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertNoOutputPdf();
    assertSchemaValid(result);
  }

  // ---- preflight rejections ---------------------------------------------------------------

  @Test
  void signedSafeFixIsRefusedBeforeAnyValidation() throws IOException {
    byte[] original = install(SyntheticPdfFactory.signed());

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.UNSUPPORTED, result.status());
    assertEquals(ErrorCode.UNSUPPORTED_SIGNED_MUTATION, result.error().code());
    assertEquals(ValidationStatus.NOT_RUN, result.baseline().status());
    assertEquals(RemediationStatus.SKIPPED, result.remediationStatus());
    assertTrue(validator.calls.isEmpty());
    assertEquals(sha(original), result.originalSha256());
    assertEquals(List.of(ArtifactKind.ORIGINAL), kinds(result));
    assertNoOutputPdf();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertSchemaValid(result);
  }

  @Test
  void encryptedInputIsUnsupportedInBothModes() throws IOException {
    install(SyntheticPdfFactory.encrypted("pw"));

    WorkerResult analyze = processor().process(request(WorkerRequest.Mode.ANALYZE, null), workspace);
    WorkerResult fix = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.UNSUPPORTED, analyze.status());
    assertEquals(ErrorCode.UNSUPPORTED_ENCRYPTED, analyze.error().code());
    assertEquals(ErrorCode.UNSUPPORTED_ENCRYPTED, fix.error().code());
    assertEquals(ValidationStatus.NOT_RUN, analyze.baseline().status());
    assertTrue(validator.calls.isEmpty());
    assertNoOutputPdf();
    assertSchemaValid(analyze);
  }

  @Test
  void corruptOversizedAndTooManyPagesAreTypedRejections() throws IOException {
    install(SyntheticPdfFactory.corrupt());
    WorkerResult corrupt = processor().process(request(WorkerRequest.Mode.ANALYZE, null), workspace);
    assertEquals(Status.UNSUPPORTED, corrupt.status());
    assertEquals(ErrorCode.INVALID_INPUT, corrupt.error().code());

    byte[] big = install(SyntheticPdfFactory.build(Options.plain().pages(3)));
    WorkerResult oversized =
        processor().process(request(WorkerRequest.Mode.ANALYZE, null, big.length - 1, 20), workspace);
    assertEquals(Status.UNSUPPORTED, oversized.status());
    assertEquals(ErrorCode.LIMIT_EXCEEDED, oversized.error().code());
    assertNull(oversized.originalSha256(), "oversized input is not even read");

    WorkerResult tooMany = processor().process(request(WorkerRequest.Mode.ANALYZE, null, 10_485_760, 2), workspace);
    assertEquals(Status.UNSUPPORTED, tooMany.status());
    assertEquals(ErrorCode.LIMIT_EXCEEDED, tooMany.error().code());
    assertEquals(sha(big), tooMany.originalSha256());

    assertTrue(validator.calls.isEmpty());
    assertNoOutputPdf();
    assertSchemaValid(corrupt);
    assertSchemaValid(oversized);
    assertSchemaValid(tooMany);
  }

  // ---- manual review ----------------------------------------------------------------------

  @Test
  void imagesTriggerAnExplicitHumanReviewFindingAndNoAltTextIsInvented() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().language("en").infoTitle("T").displayDocTitle(true).image().table()));
    validator.enqueue(passed());

    WorkerResult result = processor().process(request(WorkerRequest.Mode.SAFE_FIX, null), workspace);

    assertEquals(Status.COMPLETED, result.status());
    assertTrue(result.manualReviewRequired());
    Optional<WorkerResult.Finding> images =
        result.findings().stream().filter(f -> f.ruleId().equals("IMAGES_REQUIRE_HUMAN_REVIEW")).findFirst();
    assertTrue(images.isPresent(), result.findings().toString());
    assertTrue(images.get().needsHumanReview());
    assertEquals(1, images.get().pageNumber());
    assertNoOutputPdf();
  }

  // ---- generated PDFs (origin html_generated) ---------------------------------------------

  private static final String METADATA_RULE = "ISO 14289-1:2014 7.1-8";
  private static final String UAID_RULE = "ISO 14289-1:2014 5-1";

  @Test
  void anUploadedTaggedPdfInFixModeListsNoGeneratedRuleKeepsItsTagsAndGetsAnXmpPacket() throws IOException {
    byte[] original = install(SyntheticPdfFactory.tagged());
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.UPLOAD), workspace);

    assertEquals(
        List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_TITLE_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
            RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1, RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1, RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1, RuleId.PDF_XMP_METADATA_FROM_INFO_V1, RuleId.PDF_UA_IDENTIFIER_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList(),
        "the generated-structure rule never participates for an upload");
    WorkerResult.Action xmp = action(result, RuleId.PDF_XMP_METADATA_FROM_INFO_V1);
    assertEquals(ActionOutcome.APPLIED, xmp.outcome(), "a tagged upload without a packet gets the minimal one (2026-09-28): " + xmp.reason());
    WorkerResult.Action structure = action(result, RuleId.PDF_STRUCTURE_INFERRED_V1);
    assertEquals(ActionOutcome.SKIPPED, structure.outcome());
    assertTrue(structure.reason().startsWith("strategy tagged_usable:"), structure.reason());
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertSchemaValid(result);
  }

  @Test
  void aGeneratedPdfIsNormalizedGetsXmpAndPublishesWhenNoRuleFailsMoreOften() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_GENERATED_STRUCTURE_V1).outcome());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_XMP_METADATA_FROM_INFO_V1).outcome());
    assertTrue(Files.exists(workspace.outputFile("remediated.pdf")));
    assertSchemaValid(result);
  }

  // ---- PDF/UA identifier for machine-clean generated PDFs ------------------------------------

  private static final String FONT_RULE = "ISO 14289-1:2014 7.21.4.1-1";

  @Test
  void aGeneratedCopyWhoseOnlyFailureIsTheIdentifierCheckDeclaresPdfUaOneAndIsStillFlaggedForReview() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.APPLIED, ua.outcome(), ua.reason());
    assertEquals(RuleId.PDF_UA_IDENTIFIER_V1, result.actions().get(result.actions().size() - 1).ruleId(), "listed last: it runs after the final validation");
    assertEquals(3, validator.calls.size(), "the identified copy is validated again");
    assertEquals(ValidationStatus.PASSED, result.finalValidation().status(), "the published copy's own validation is reported");
    assertTrue(result.finalValidation().failedRules().isEmpty());
    assertTrue(remediatedXmp().contains("pdfuaid"), "identifier written to the published copy");
    WorkerResult.Finding finding = result.findings().stream()
        .filter(f -> f.ruleId().equals(FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY)).findFirst().orElseThrow();
    assertTrue(finding.needsHumanReview());
    assertTrue(result.manualReviewRequired());
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals(FindingsBuilder.MANUAL_REVIEW_REQUIRED)));
    assertNoConformanceWording(result);
    assertEquals(sha(Files.readAllBytes(workspace.outputFile("remediated.pdf"))), artifact(result, ArtifactKind.REMEDIATED).sha256());
    assertEquals("{\"fake\":true,\"call\":3}", Files.readString(workspace.outputFile("final-report.json")), "the published report is the identified copy's");
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aGeneratedCopyThatStillFailsAFontCheckGetsNoIdentifierAndTheSkipNamesTheRule() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, FONT_RULE, 2)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, FONT_RULE, 2)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome());
    assertTrue(ua.reason().contains(FONT_RULE), ua.reason());
    assertEquals(2, validator.calls.size(), "no identified copy is made or validated");
    assertFalse(remediatedXmp().contains("pdfuaid"), "no identifier in the published copy");
    assertEquals(ValidationStatus.FAILED, result.finalValidation().status());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY)));
    assertTrue(result.manualReviewRequired());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aGeneratedCopyThatBreaksAPacOnlyStructureRuleGetsNoIdentifierAndTheSkipNamesTheRule() throws IOException {
    install(withAnnotationOutsideRuby(generatedFixture("generated-lists.pdf")));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome());
    assertTrue(ua.reason().contains("structure checks") && ua.reason().contains(com.koldera.pdf.generated.StructureRules.RUBY + " ×1"), ua.reason());
    assertEquals(2, validator.calls.size(), "no identified copy is made or validated");
    assertFalse(remediatedXmp().contains("pdfuaid"), "no identifier in the published copy");
    assertEquals(List.of(UAID_RULE), result.finalValidation().failedRules().stream().map(WorkerResult.FailedRule::ruleId).toList());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY)));
    assertScratchClean();
    assertSchemaValid(result);
  }

  /** The fixture with its H1 retagged RT: a ruby annotation outside a Ruby, which no generated step touches. */
  private static byte[] withAnnotationOutsideRuby(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      COSDictionary heading = firstOfType(doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject(), "H1", 0);
      assertNotNull(heading, "fixture has an H1");
      heading.setItem(COSName.S, COSName.getPDFName("RT"));
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static COSDictionary firstOfType(COSDictionary node, String type, int depth) {
    if (depth > 64) {
      return null;
    }
    for (org.apache.pdfbox.cos.COSBase entry : com.koldera.pdf.generated.StructTree.entries(node)) {
      if (com.koldera.pdf.generated.StructTree.resolve(entry) instanceof COSDictionary kid && kid.getDictionaryObject(COSName.S) instanceof COSName s) {
        if (type.equals(s.getName())) {
          return kid;
        }
        COSDictionary found = firstOfType(kid, type, depth + 1);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  @Test
  void anIdentifiedCopyThatFailsItsOwnValidationIsDiscardedAndThePreIdentifierCopyIsPublished() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(failed(Map.of("ISO 14289-1:2014 5-2", 1)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome());
    assertTrue(ua.reason().contains("ISO 14289-1:2014 5-2"), ua.reason());
    assertFalse(remediatedXmp().contains("pdfuaid"), "the pre-identifier copy is published");
    assertEquals(List.of(UAID_RULE), result.finalValidation().failedRules().stream().map(WorkerResult.FailedRule::ruleId).toList(),
        "the reported final validation is the published copy's");
    assertEquals("{\"fake\":true,\"call\":2}", Files.readString(workspace.outputFile("final-report.json")), "the pre-identifier report stays published");
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY)));
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void anIdentifiedCopyWhoseValidationErrorsIsDiscardedAndThePreIdentifierCopyIsPublished() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(new ValidationOutcome(ValidationStatus.ERROR, Map.of(), Map.of(), null, ErrorCode.VALIDATOR_ERROR, "boom", "fake"));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(ActionOutcome.SKIPPED, action(result, RuleId.PDF_UA_IDENTIFIER_V1).outcome());
    assertFalse(remediatedXmp().contains("pdfuaid"));
    assertEquals(ValidationStatus.FAILED, result.finalValidation().status());
    assertEquals("{\"fake\":true,\"call\":2}", Files.readString(workspace.outputFile("final-report.json")), "the pre-identifier report stays published");
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void twoGeneratedFixRunsOverTheSameInputPublishByteIdenticalCopies() throws Exception {
    List<String> shas = publishTwice(true);
    assertEquals(shas.get(0), shas.get(1), "identified copies must be byte-identical");
  }

  @Test
  void twoGeneratedFixRunsWithoutTheIdentifierAlsoPublishByteIdenticalCopies() throws Exception {
    List<String> shas = publishTwice(false);
    assertEquals(shas.get(0), shas.get(1), "pre-identifier copies must be byte-identical");
  }

  /** Two runs over generated-lists.pdf, one second apart (a wall-clock /ID would differ); returns the remediated sha256s. */
  private List<String> publishTwice(boolean clean) throws Exception {
    List<String> shas = new ArrayList<>();
    for (int run = 0; run < 2; run++) {
      if (run == 1) {
        Thread.sleep(1100);
      }
      install(generatedFixture("generated-lists.pdf"));
      Files.deleteIfExists(workspace.outputFile("remediated.pdf"));
      validator.enqueue(failed(clean ? Map.of(METADATA_RULE, 1) : Map.of(METADATA_RULE, 1, FONT_RULE, 2)));
      validator.enqueue(failed(clean ? Map.of(UAID_RULE, 1) : Map.of(UAID_RULE, 1, FONT_RULE, 2)));
      if (clean) {
        validator.enqueue(passed());
      }
      WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);
      assertEquals(clean ? ActionOutcome.APPLIED : ActionOutcome.SKIPPED, action(result, RuleId.PDF_UA_IDENTIFIER_V1).outcome());
      shas.add(sha(Files.readAllBytes(workspace.outputFile("remediated.pdf"))));
    }
    return shas;
  }

  @Test
  void anUploadInFixModeWhoseOnlyFailureIsTheIdentifierCheckGetsTheIdentifierSinceTheOwnerDecisionOf20260928() throws IOException {
    // The upload behaviour is pinned in UploadIdentifierTest.
    install(SyntheticPdfFactory.simpleUntagged());
    validator.enqueue(failed(Map.of(STRUCT_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    // With a title: a copy whose XMP has no title never gets the identifier (see UploadIdentifierTest).
    WorkerResult result = production().process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "Quarterly report"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_UA_IDENTIFIER_V1).outcome());
    assertEquals(3, validator.calls.size());
    assertTrue(remediatedXmp().contains("pdfuaid"));
    assertNoConformanceWording(result);
    assertSchemaValid(result);
  }

  private String remediatedXmp() throws IOException {
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      var metadata = doc.getDocumentCatalog().getMetadata();
      return metadata == null ? "" : new String(metadata.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static void assertNoConformanceWording(WorkerResult result) {
    String json = WorkerResultCodec.toJson(result).toLowerCase(java.util.Locale.ROOT);
    // Character classes keep the claim words out of this source file.
    var claim = java.util.regex.Pattern.compile("compl[i]ant|compl[i]ance|certif[i]ed|certif[i]cate|100%|accessible pdf").matcher(json);
    assertFalse(claim.find(), () -> "no claim wording: " + json);
  }
  @Test
  void aGeneratedRunWhoseFinalValidationFailsAnExtraRuleRollsBackWhenTheFallbackAlsoRegresses() throws IOException {
    byte[] original = install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    // The fallback copy (no normalization, no identifier) regresses too: today's failure stands.
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));

    WorkerResult result = production().process(generatedWithTitle(), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().startsWith("generated gate:"), result.error().message());
    assertTrue(result.error().message().contains("7.2-20"), result.error().message());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertEquals(ActionOutcome.ROLLED_BACK, action(result, RuleId.PDF_GENERATED_STRUCTURE_V1).outcome());
    assertEquals(3, validator.calls.size(), "one fallback run, not more");
    assertEquals("{\"fake\":true,\"call\":2}", Files.readString(workspace.outputFile("final-report.json")), "the reported final report is the first run's");
    assertEquals(sha(Files.readAllBytes(workspace.outputFile("final-report.json"))), artifact(result, ArtifactKind.FINAL_REPORT).sha256());
    assertNoOutputPdf();
    assertScratchClean();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertSchemaValid(result);
  }

  @Test
  void aGeneratedRunWhoseExistingFailureGrowsRollsBackWithNoAllowList() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.21.7-1", 3)));
    validator.enqueue(failed(Map.of("ISO 14289-1:2014 7.21.7-1", 4)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.21.7-1", 4)));

    WorkerResult result = production().process(generatedWithTitle(), workspace);

    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().contains("7.21.7-1"), result.error().message());
    assertNoOutputPdf();
  }

  // ---- untagged text marked as artifact in generated PDFs ---------------------------------------

  @Test
  void aPublishedGeneratedCopyListsTheUntaggedTextItMarkedAsArtifactForAPersonToCheck() throws IOException {
    install(generatedFixture("generated-untagged-text.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, FONT_RULE, 2)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, FONT_RULE, 2)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_GENERATED_STRUCTURE_V1).outcome());
    List<WorkerResult.Finding> untagged = result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.UNTAGGED_TEXT_MARKED_ARTIFACT)).toList();
    assertEquals(1, untagged.size(), result.findings().toString());
    WorkerResult.Finding finding = untagged.get(0);
    assertTrue(finding.needsHumanReview());
    assertNull(finding.pageNumber());
    assertNull(finding.resolution(), "not a machine check");
    int runs = GeneratedStructureRule.untaggedText(action(result, RuleId.PDF_GENERATED_STRUCTURE_V1)).orElseThrow().runs();
    assertTrue(finding.message().startsWith(runs + " runs of text painted outside the tagged content"), finding.message());
    assertTrue(finding.message().contains("for example \"Example Co.\""), finding.message());
    assertNoConformanceWording(result);
    assertSchemaValid(result);
  }

  @Test
  void aPublishedGeneratedCopyWhoseHeadingLevelsChangedSaysHowForAPersonToCheck() throws IOException {
    install(generatedFixture("generated-headings.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.4.2-1", 1, FONT_RULE, 2)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, FONT_RULE, 2)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    List<WorkerResult.Finding> renumbered = result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.HEADING_LEVELS_RENUMBERED)).toList();
    assertEquals(1, renumbered.size(), result.findings().toString());
    assertTrue(renumbered.get(0).needsHumanReview());
    assertNull(renumbered.get(0).resolution());
    assertTrue(renumbered.get(0).message().contains("(H2→H1 ×1, H3→H2 ×1, H4→H3 ×2)"), renumbered.get(0).message());
    assertNoConformanceWording(result);
    assertSchemaValid(result);
  }

  @Test
  void aRolledBackGeneratedRunListsNoHeadingFinding() throws IOException {
    install(generatedFixture("generated-headings.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));

    WorkerResult result = production().process(generatedWithTitle(), workspace);

    assertEquals(ActionOutcome.ROLLED_BACK, action(result, RuleId.PDF_GENERATED_STRUCTURE_V1).outcome());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.HEADING_LEVELS_RENUMBERED)), "the fallback copy kept the source levels");
  }

  @Test
  void aRolledBackGeneratedRunListsNoUntaggedTextFinding() throws IOException {
    install(generatedFixture("generated-untagged-text.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));

    WorkerResult result = production().process(generatedWithTitle(), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(ActionOutcome.ROLLED_BACK, action(result, RuleId.PDF_GENERATED_STRUCTURE_V1).outcome());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.UNTAGGED_TEXT_MARKED_ARTIFACT)), "the fallback copy marked nothing");
  }

  // ---- fallback: a generated run whose normalization fails or regresses -------------------------

  @Test
  void aGeneratedGateRegressionPublishesTheCopyWithoutNormalizationOrIdentifierAndSaysSo() throws IOException {
    byte[] original = install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));

    WorkerResult result = production().process(generatedWithTitle(), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertNull(result.error());
    assertEquals(
        List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_TITLE_V1, RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
            RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1, RuleId.PDF_GENERATED_STRUCTURE_V1, RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_XMP_METADATA_FROM_INFO_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList(),
        "the generated rule keeps its place; no identifier step");
    WorkerResult.Action generated = action(result, RuleId.PDF_GENERATED_STRUCTURE_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, generated.outcome());
    assertTrue(generated.reason().contains("7.2-20"), generated.reason());
    assertTrue(generated.reason().contains("without this normalization"), generated.reason());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_TITLE_V1).outcome(), "the title fix is kept");
    assertEquals(ActionOutcome.SKIPPED, action(result, RuleId.PDF_XMP_METADATA_FROM_INFO_V1).outcome(), "as for any tagged PDF before");
    assertEquals(3, validator.calls.size(), "baseline, generated copy, fallback copy");
    assertEquals("{\"fake\":true,\"call\":3}", Files.readString(workspace.outputFile("final-report.json")), "the published copy's report");
    assertEquals(sha(Files.readAllBytes(workspace.outputFile("remediated.pdf"))), artifact(result, ArtifactKind.REMEDIATED).sha256());
    assertFalse(remediatedXmp().contains("pdfuaid"), "no identifier");
    assertEquals("Fallback title", remediatedTitle());
    assertEquals(0, remediatedCount("LBody"), "the published copy is not normalized");
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY)));
    assertTrue(result.manualReviewRequired());
    assertNoConformanceWording(result);
    assertScratchClean();
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()));
    assertSchemaValid(result);
  }

  @Test
  void aGeneratedGateRegressionWhoseFallbackHasNothingToApplyCompletesAsBeforeWithNoStaleReport() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));

    WorkerResult result = production().process(generatedOrUpload(WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.NO_APPLICABLE_FIX, result.remediationStatus(), "what this PDF got before the generated steps existed");
    assertEquals(ActionOutcome.ROLLED_BACK, action(result, RuleId.PDF_GENERATED_STRUCTURE_V1).outcome());
    assertEquals(2, validator.calls.size(), "nothing to apply: the fallback does not validate again");
    assertNull(result.finalValidation());
    assertFalse(kinds(result).contains(ArtifactKind.FINAL_REPORT), "the rolled-back copy's report is not advertised");
    assertFalse(Files.exists(workspace.outputFile("final-report.json")), "and not left in out/");
    assertNoOutputPdf();
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aFailedGeneratedNormalizationPublishesTheCopyWithoutItAndListsTheFailure() throws IOException {
    install(generatedFixture("generated-lists.pdf"));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    List<RemediationRule> rules = new ArrayList<>(com.koldera.pdf.cli.Main.productionRules());
    rules.replaceAll(r -> r.id() == RuleId.PDF_GENERATED_STRUCTURE_V1 ? failingNormalizer() : r);

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, rules).process(generatedWithTitle(), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action generated = action(result, RuleId.PDF_GENERATED_STRUCTURE_V1);
    assertEquals(ActionOutcome.FAILED, generated.outcome());
    assertTrue(generated.reason().startsWith("could not normalize the generated structure (IllegalStateException)"), generated.reason());
    assertTrue(generated.reason().contains("without this normalization"), generated.reason());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_TITLE_V1).outcome());
    assertTrue(result.actions().stream().noneMatch(a -> a.ruleId() == RuleId.PDF_UA_IDENTIFIER_V1), result.actions().toString());
    assertEquals(2, validator.calls.size(), "baseline and the fallback copy; the failed run never saved a copy");
    assertEquals("Fallback title", remediatedTitle());
    assertFalse(remediatedXmp().contains("pdfuaid"));
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void anUploadWithAFailingRuleOrARegressionStillFailsWithoutAFallback() throws IOException {
    install(taggedUploadWithLink());
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    // Without the upload repairs, whose own retry is pinned below.
    List<RemediationRule> withoutRepairs = new ArrayList<>(com.koldera.pdf.cli.Main.productionRules());
    withoutRepairs.removeIf(r -> PdfProcessor.UPLOAD_REPAIRS.contains(r.id()));

    WorkerResult regressed = new PdfProcessor(new PdfPreflight(), validator, withoutRepairs)
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "Fallback title"), workspace);

    assertEquals(Status.FAILED, regressed.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, regressed.error().code());
    assertTrue(regressed.error().message().startsWith("validation regression:"), regressed.error().message());
    assertEquals(2, validator.calls.size(), "no second run for an upload");
    assertNoOutputPdf();

    validator.calls.clear();
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    List<RemediationRule> rules = new ArrayList<>(com.koldera.pdf.cli.Main.productionRules());
    rules.replaceAll(r -> r.id() == RuleId.PDF_TITLE_V1 ? failingRule(RuleId.PDF_TITLE_V1) : r);
    rules.removeIf(r -> PdfProcessor.UPLOAD_REPAIRS.contains(r.id()));
    WorkerResult broken = new PdfProcessor(new PdfPreflight(), validator, rules).process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "Fallback title"), workspace);

    assertEquals(Status.FAILED, broken.status());
    assertEquals(ErrorCode.WORKER_ERROR, broken.error().code());
    assertEquals(1, validator.calls.size());
    assertNoOutputPdf();
    assertSchemaValid(broken);
  }

  @Test
  void anUploadRolledBackAfterARepairIsProducedOnceMoreWithoutTheRepairs() throws IOException {
    install(taggedUploadWithLink());
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));

    WorkerResult result = production().process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "Fallback title"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(3, validator.calls.size(), "baseline, the run with the repairs, the run without them");
    List<WorkerResult.Action> repairs = result.actions().stream().filter(a -> PdfProcessor.UPLOAD_REPAIRS.contains(a.ruleId())).toList();
    assertEquals(3, repairs.size(), "every repair keeps its place in the list: " + result.actions());
    WorkerResult.Action rolledBack = repairs.stream().filter(a -> a.outcome() == ActionOutcome.ROLLED_BACK).findFirst().orElseThrow();
    assertTrue(rolledBack.reason().startsWith("rolled back: "), rolledBack.reason());
    assertTrue(rolledBack.reason().contains("validation regression: newly failing rule ISO 14289-1:2014 7.2-20 (1)"), rolledBack.reason());
    assertTrue(rolledBack.reason().endsWith(PdfProcessor.REPAIRS_NOTE), rolledBack.reason());
    List<RuleId> order = result.actions().stream().map(WorkerResult.Action::ruleId).toList();
    assertEquals(List.of(RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1, RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_TITLE_V1,
        RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1, RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1, RuleId.PDF_LINK_TAGS_V1,
        RuleId.PDF_TAB_ORDER_V1, RuleId.PDF_ROLE_MAP_V1, RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1, RuleId.PDF_XMP_METADATA_FROM_INFO_V1, RuleId.PDF_UA_IDENTIFIER_V1), order);
    assertEquals("Fallback title", remediatedTitle());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void anUploadThatAlsoFailsWithoutTheRepairsReturnsTheFirstFailure() throws IOException {
    install(taggedUploadWithLink());
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, "ISO 14289-1:2014 7.2-20", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 2)));

    WorkerResult result = production().process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "Fallback title"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertEquals("validation regression: newly failing rule ISO 14289-1:2014 7.2-20 (1)", result.error().message(), "the first run's failure");
    assertEquals(3, validator.calls.size());
    assertNoOutputPdf();
    assertScratchClean();
    assertSchemaValid(result);
  }

  // ---- font repairs ----------------------------------------------------------

  private static final String SPEC = "ISO 14289-1:2014 ";
  private static final String UNICODE_RULE = SPEC + "7.21.7-1";
  private static final Map<String, Integer> UNTAGGED_BASELINE =
      Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1, SPEC + "7.1-11", 1, SPEC + "7.1-3", 3, SPEC + "6.2-1", 1, SPEC + "7.1-8", 1, UNICODE_RULE, 1);

  @Test
  void aFilledUnicodeMappingPassesTheTextInvariant() throws IOException {
    // f_f at code 81 extracts as "Q" before and as "ff" after: the digest changes, so the glyph audit decides.
    install(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(80, "summationtext", 81, "f_f"), "APQ".getBytes(StandardCharsets.US_ASCII), null));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, List.of(new FontUnicodeRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_FONT_TOUNICODE_V1).outcome());
    WorkerResult.Finding derived = result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.FONT_UNICODE_DERIVED)).findFirst().orElseThrow();
    assertEquals("Unicode mappings were added for 1 character code that had no Unicode value, so its extracted text changed (glyph names 1).",
        derived.message(), "summationtext already extracted as a sum sign; only f_f changed the text");
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.FONT_GLYPHS_WITHOUT_UNICODE)));
    try (PDDocument in = Loader.loadPDF(Files.readAllBytes(workspace.inputFile()));
        PDDocument out = Loader.loadPDF(Files.readAllBytes(workspace.outputFile("remediated.pdf")))) {
      assertEquals("A∑Q", PdfPreflight.extractText(in).strip(), "precondition: the ligature extracts as its raw code");
      assertEquals("A∑ff", PdfPreflight.extractText(out).strip(), "the digest differs, so the glyph audit passed it");
    }
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aMacOrderFillAndAnUnmappedFontAreReportedAfterAProcessorRun() throws IOException {
    install(SyntheticPdfFactory.symbolicTrueTypeWithoutUnicodeTables("Hello", false));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1)));
    validator.enqueue(passed());
    WorkerResult mapped = new PdfProcessor(new PdfPreflight(), validator, List.of(new FontUnicodeRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);
    assertEquals(Status.COMPLETED, mapped.status(), String.valueOf(mapped.error()));
    assertEquals(ActionOutcome.APPLIED, action(mapped, RuleId.PDF_FONT_TOUNICODE_V1).outcome());
    assertTrue(mapped.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.FONT_UNICODE_DERIVED)),
        "the Mac-order values equal the raw codes already extracted: no text changed, so no finding " + mapped.findings());

    install(SyntheticPdfFactory.untaggedType3WithMeaninglessNames());
    validator.enqueue(failed(Map.of(UNICODE_RULE, 2, SPEC + "7.1-10", 1)));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 2)));
    WorkerResult unmapped = new PdfProcessor(new PdfPreflight(), validator, List.of(new TitleRule(), new FontUnicodeRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);
    assertEquals(Status.COMPLETED, unmapped.status(), String.valueOf(unmapped.error()));
    assertEquals(ActionOutcome.SKIPPED, action(unmapped, RuleId.PDF_FONT_TOUNICODE_V1).outcome());
    WorkerResult.Finding none = unmapped.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.FONT_GLYPHS_WITHOUT_UNICODE)).findFirst().orElseThrow();
    assertTrue(none.message().startsWith("2 character codes in 1 font (Type 3 font) have no Unicode mapping"), none.message());
    assertTrue(none.needsHumanReview());
    assertSchemaValid(unmapped);
  }

  @Test
  void aFontRuleThatChangesMappedTextIsRolledBackAndTheCopyProducedWithoutTheFontRepairs() throws IOException {
    install(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(), "AB".getBytes(StandardCharsets.US_ASCII), null));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1, TITLE_RULE, 1)));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, List.of(new TitleRule(), textChangingFontRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(2, validator.calls.size(), "baseline and the run without the font repairs; the first run failed before validation");
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_TITLE_V1).outcome());
    WorkerResult.Action font = action(result, RuleId.PDF_FONT_TOUNICODE_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, font.outcome());
    assertTrue(font.reason().endsWith("text check: glyph 1 on page 1 (Helvetica, code 65) changed its text from \"A\" to \"X\"" + PdfProcessor.FONTS_NOTE),
        font.reason());
    assertEquals(List.of(RuleId.PDF_TITLE_V1, RuleId.PDF_FONT_TOUNICODE_V1, RuleId.PDF_UA_IDENTIFIER_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aFontOnlyRegressionDropsTheFontStepsFirst() throws IOException {
    install(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(80, "summationtext", 81, "a0"), "APQ".getBytes(StandardCharsets.US_ASCII), null));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1)));
    validator.enqueue(failed(Map.of(SPEC + "7.21.5-1", 1)));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, List.of(new TitleRule(), new FontUnicodeRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(3, validator.calls.size());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_TITLE_V1).outcome());
    WorkerResult.Action font = action(result, RuleId.PDF_FONT_TOUNICODE_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, font.outcome());
    assertTrue(font.reason().contains("validation regression: newly failing rule ISO 14289-1:2014 7.21.5-1 (1)"), font.reason());
    assertTrue(font.reason().endsWith(PdfProcessor.FONTS_NOTE), font.reason());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.FONT_UNICODE_DERIVED)), "the published copy has no font repair");
    WorkerResult.Finding unmapped = result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.FONT_GLYPHS_WITHOUT_UNICODE)).findFirst()
        .orElseThrow(() -> new AssertionError("the copy without the font rule still names its unmapped font: " + result.findings()));
    assertTrue(unmapped.message().startsWith("1 character code in 1 font (Helvetica) has no Unicode mapping"), unmapped.message());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aNonFontRegressionKeepsTheFontStepsInTheStructureLadder() throws IOException {
    install(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(80, "summationtext"), "AP".getBytes(StandardCharsets.US_ASCII), null));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1, SPEC + "7.1-3", 1)));
    validator.enqueue(failed(Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator,
        List.of(new StructureInferenceRule(InferenceSettings.DEFAULTS), new BasicStructureRule(),
            new FontUnicodeRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(3, validator.calls.size(), "baseline, the first run, the run without the structure steps");
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1).outcome());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_FONT_TOUNICODE_V1).outcome(), "the font step stays in the published run");
    WorkerResult.Action inferred = action(result, RuleId.PDF_STRUCTURE_INFERRED_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, inferred.outcome());
    assertTrue(inferred.reason().endsWith(PdfProcessor.STRUCTURE_NOTE), inferred.reason());
    assertSchemaValid(result);
  }

  @Test
  void aFontCheckFailureThatPersistsWithoutTheFontStepsKeepsThemInTheStructureLadder() throws IOException {
    install(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(80, "summationtext"), "AP".getBytes(StandardCharsets.US_ASCII), null));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1, SPEC + "7.21.5-1", 1)));
    // Rung B: the same font check fails without the font steps, so they were not its cause.
    validator.enqueue(failed(Map.of(UNICODE_RULE, 1, SPEC + "7.21.5-1", 1)));
    validator.enqueue(failed(Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator,
        List.of(new StructureInferenceRule(InferenceSettings.DEFAULTS), new BasicStructureRule(), new FontUnicodeRule()))
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(4, validator.calls.size(), "baseline, the first run, rung B, the run without the structure steps");
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_FONT_TOUNICODE_V1).outcome(), "the font step is kept in the structure ladder's repeat");
    assertEquals(ActionOutcome.ROLLED_BACK, action(result, RuleId.PDF_STRUCTURE_INFERRED_V1).outcome());
    assertSchemaValid(result);
  }

  @Test
  void aTaggedUploadWhoseLadderAlreadyRanWithoutTheRepairsAndFontsIsNotRepeatedAgain() throws IOException {
    install(taggedUploadWithLink());
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, SPEC + "7.21.5-1", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, SPEC + "7.2-20", 1)));
    validator.enqueue(failed(Map.of(METADATA_RULE, 1, SPEC + "7.2-20", 1)));
    List<RemediationRule> rules = new ArrayList<>(com.koldera.pdf.cli.Main.productionRules());
    rules.replaceAll(r -> r.id() == RuleId.PDF_FONT_TOUNICODE_V1 ? appliedNoOpFontRule() : r);

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, rules)
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "Fallback title"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals("validation regression: newly failing rule ISO 14289-1:2014 7.21.5-1 (1)", result.error().message(), "the first run's failure");
    assertEquals(4, validator.calls.size(), "baseline, first run, without the fonts, without the repairs and fonts; no fifth equivalent repeat");
    assertNoOutputPdf();
    assertScratchClean();
  }

  private static final Map<String, Integer> UNTAGGED_OCR_BASELINE =
      Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1, SPEC + "7.1-11", 1, SPEC + "7.1-3", 3, SPEC + "6.2-1", 1, SPEC + "7.1-8", 1, UNICODE_RULE, 2);

  private static List<RemediationRule> structureAndFontProgramRules() {
    return List.of(new StructureInferenceRule(InferenceSettings.DEFAULTS), new BasicStructureRule(), new InvisibleTextFontRule(), appliedNoOpFontRule());
  }

  @Test
  void aFontProgramStepIsDroppedFirstWhenOnlyFontChecksGrew() throws IOException {
    install(SyntheticPdfFactory.untaggedInvisibleOcrText(false, false));
    validator.enqueue(failed(UNTAGGED_OCR_BASELINE));
    // The replaced program changes the glyph inventory, so a grown font check is a regression, attributable to fonts only.
    validator.enqueue(failed(Map.of(UNICODE_RULE, 5)));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 2)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, structureAndFontProgramRules())
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(3, validator.calls.size(), "baseline, the first run, rung A (without the font program steps)");
    WorkerResult.Action invisible = action(result, RuleId.PDF_FONT_INVISIBLE_TEXT_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, invisible.outcome());
    assertTrue(invisible.reason().contains("rule ISO 14289-1:2014 7.21.7-1 failures increased 2 -> 5 (glyph inventory differs)"), invisible.reason());
    assertTrue(invisible.reason().endsWith(PdfProcessor.FONT_PROGRAM_NOTE), invisible.reason());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_FONT_TOUNICODE_V1).outcome(), "the other font repairs stay");
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_STRUCTURE_INFERRED_V1).outcome(), "the structure steps stay");
    assertEquals(List.of(RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1,
        RuleId.PDF_FONT_TOUNICODE_V1, RuleId.PDF_UA_IDENTIFIER_V1), result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void aFontProgramStepLeftOutByRungAStaysOutOfTheStructureLadderWithItsOwnNote() throws IOException {
    install(SyntheticPdfFactory.untaggedInvisibleOcrText(false, false));
    validator.enqueue(failed(UNTAGGED_OCR_BASELINE));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 5)));
    // Rung A fails for a structure reason (7.1-3 must reach zero): not attributable to fonts, so rung B is not run.
    validator.enqueue(failed(Map.of(UNICODE_RULE, 2, SPEC + "7.1-3", 1)));
    validator.enqueue(failed(Map.of(UNICODE_RULE, 2)));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, structureAndFontProgramRules())
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(4, validator.calls.size(), "baseline, the first run, rung A, the basic tagger without the structure and program steps");
    WorkerResult.Action invisible = action(result, RuleId.PDF_FONT_INVISIBLE_TEXT_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, invisible.outcome());
    assertTrue(invisible.reason().endsWith(PdfProcessor.FONT_PROGRAM_NOTE), "withFontSteps picks the program note: " + invisible.reason());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_FONT_TOUNICODE_V1).outcome(), "the other font repairs stay");
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1).outcome());
    WorkerResult.Action inferred = action(result, RuleId.PDF_STRUCTURE_INFERRED_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, inferred.outcome());
    assertTrue(inferred.reason().endsWith(PdfProcessor.STRUCTURE_NOTE), inferred.reason());
    assertScratchClean();
    assertSchemaValid(result);
  }

  private static final Map<String, Integer> UNTAGGED_NON_EMBEDDED_BASELINE =
      Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1, SPEC + "7.1-11", 1, SPEC + "7.1-3", 3, SPEC + "6.2-1", 1, SPEC + "7.1-8", 1, SPEC + "7.21.4.1-1", 1);

  @Test
  void theFontEmbeddingStepIsAFontProgramStepDroppedFirst() throws IOException {
    assertTrue(PdfProcessor.FONT_PROGRAM_STEPS.contains(RuleId.PDF_FONT_EMBEDDING_V1));
    assertTrue(PdfProcessor.FONT_REPAIRS.contains(RuleId.PDF_FONT_EMBEDDING_V1));
    install(SyntheticPdfFactory.untaggedNonEmbedded("Helvetica", false, 0));
    validator.enqueue(failed(UNTAGGED_NON_EMBEDDED_BASELINE));
    // A width check that newly fails after the embedding: attributable to fonts only, so rung A leaves out the program steps.
    validator.enqueue(failed(Map.of(SPEC + "7.21.5-1", 1)));
    validator.enqueue(failed(Map.of(SPEC + "7.21.4.1-1", 1)));
    List<RemediationRule> rules =
        List.of(new StructureInferenceRule(InferenceSettings.DEFAULTS), new BasicStructureRule(), new com.koldera.pdf.fonts.FontEmbeddingRule(), appliedNoOpFontRule());

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, rules)
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(3, validator.calls.size(), "baseline, the first run, rung A (without the font program steps)");
    WorkerResult.Action embedding = action(result, RuleId.PDF_FONT_EMBEDDING_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, embedding.outcome());
    assertTrue(embedding.reason().endsWith(PdfProcessor.FONT_PROGRAM_NOTE), embedding.reason());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_FONT_TOUNICODE_V1).outcome(), "the other font repairs stay");
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.FONT_EMBEDDED_SUBSTITUTE)), "no finding for a rolled-back embedding");
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void anAppliedEmbeddingAddsOneFindingPerFont() throws IOException {
    install(SyntheticPdfFactory.untaggedNonEmbeddedMix());
    validator.enqueue(failed(UNTAGGED_NON_EMBEDDED_BASELINE));
    validator.enqueue(passed());
    List<RemediationRule> rules = List.of(new StructureInferenceRule(InferenceSettings.DEFAULTS), new BasicStructureRule(), new com.koldera.pdf.fonts.FontEmbeddingRule());

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, rules)
        .process(originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, "T"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(List.of("Helvetica", "ArialMT", "TimesNewRomanPSMT"), result.findings().stream()
        .filter(f -> f.ruleId().equals(FindingsBuilder.FONT_EMBEDDED_SUBSTITUTE))
        .map(f -> f.message().substring("The font '".length(), f.message().indexOf("' was not embedded")))
        .toList());
    assertSchemaValid(result);
  }

  /** A PDF_FONT_TOUNICODE_V1 stand-in for fix uploads that reports applied and changes nothing. */
  private static RemediationRule appliedNoOpFontRule() {
    return new RemediationRule() {
      @Override
      public RuleId id() {
        return RuleId.PDF_FONT_TOUNICODE_V1;
      }

      @Override
      public boolean participates(WorkerRequest request) {
        return request.isFix() && !request.isHtmlGenerated();
      }

      @Override
      public RuleDecision inspect(PDDocument document, WorkerRequest request) {
        return RuleDecision.apply("test double", null, null);
      }

      @Override
      public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
        return new WorkerResult.Action(id(), ActionOutcome.APPLIED, "ToUnicode written for 1 fonts (test double)", null, null);
      }
    };
  }

  @Test
  void fontAttributableIsExact() {
    assertTrue(PdfProcessor.fontAttributable(rolledBack(ErrorCode.INTEGRITY_REGRESSION, "text check: glyph 1 on page 1 changed", null, null)));
    assertTrue(PdfProcessor.fontAttributable(rolledBack(ErrorCode.INTEGRITY_REGRESSION, "validation regression: x",
        Map.of(UNICODE_RULE, 3), Map.of(UNICODE_RULE, 4))));
    assertFalse(PdfProcessor.fontAttributable(rolledBack(ErrorCode.INTEGRITY_REGRESSION, "validation regression: x",
        Map.of(UNICODE_RULE, 3), Map.of(UNICODE_RULE, 4, SPEC + "7.1-3", 1))));
    assertFalse(PdfProcessor.fontAttributable(rolledBack(ErrorCode.INTEGRITY_REGRESSION, "validation regression: x",
        Map.of(UNICODE_RULE, 3), Map.of(UNICODE_RULE, 3))), "no rule grew");
    assertFalse(PdfProcessor.fontAttributable(rolledBack(ErrorCode.INTEGRITY_REGRESSION, "extracted text digest changed", null, null)));
    assertFalse(PdfProcessor.fontAttributable(rolledBack(ErrorCode.WORKER_ERROR, "text check: glyph 1", Map.of(UNICODE_RULE, 3), Map.of(UNICODE_RULE, 4))));
  }

  private static WorkerResult rolledBack(ErrorCode code, String message, Map<String, Integer> baseline, Map<String, Integer> fin) {
    return new WorkerResult("1.0", JOB, Status.FAILED, "0".repeat(64), "test", validation(baseline), fin == null ? null : validation(fin),
        RemediationStatus.ROLLED_BACK, List.of(), List.of(), List.of(), true, new WorkerResult.WorkerError(code, message));
  }

  private static WorkerResult.Validation validation(Map<String, Integer> counts) {
    if (counts == null) {
      return WorkerResult.Validation.notRun();
    }
    return new WorkerResult.Validation("ua1", ValidationStatus.FAILED,
        counts.entrySet().stream().map(e -> new WorkerResult.FailedRule(e.getKey(), e.getValue())).toList(), null);
  }

  /** A PDF_FONT_TOUNICODE_V1 stand-in for fix uploads that maps code 65 of font /F1 to "X": it changes a mapped glyph's text. */
  private static RemediationRule textChangingFontRule() {
    return new RemediationRule() {
      @Override
      public RuleId id() {
        return RuleId.PDF_FONT_TOUNICODE_V1;
      }

      @Override
      public boolean participates(WorkerRequest request) {
        return request.isFix() && !request.isHtmlGenerated();
      }

      @Override
      public RuleDecision inspect(PDDocument document, WorkerRequest request) {
        return RuleDecision.apply("test double", null, null);
      }

      @Override
      public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
        try {
          COSDictionary font = document.getPage(0).getResources().getFont(COSName.getPDFName("F1")).getCOSObject();
          COSStream stream = document.getDocument().createCOSStream();
          try (var out = stream.createOutputStream()) {
            out.write(ToUnicodeCMapWriter.write(1, new TreeMap<>(Map.of(65, "X"))));
          }
          font.setItem(COSName.TO_UNICODE, stream);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        return new WorkerResult.Action(id(), ActionOutcome.APPLIED, "ToUnicode written for 1 fonts (test double)", null, null);
      }
    };
  }

  /** A PDF_GENERATED_STRUCTURE_V1 stand-in that participates like the real one and fails like a thrown normalizer. */
  private static RemediationRule failingNormalizer() {
    return new RemediationRule() {
      @Override
      public RuleId id() {
        return RuleId.PDF_GENERATED_STRUCTURE_V1;
      }

      @Override
      public boolean participates(WorkerRequest request) {
        return request.isFix() && request.isHtmlGenerated();
      }

      @Override
      public RuleDecision inspect(PDDocument document, WorkerRequest request) {
        return RuleDecision.apply("test double", "chromium tags", "normalized tags");
      }

      @Override
      public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
        return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not normalize the generated structure (IllegalStateException)", decision.before(), decision.after());
      }
    };
  }

  private static RemediationRule failingRule(RuleId id) {
    return new RemediationRule() {
      @Override
      public RuleId id() {
        return id;
      }

      @Override
      public RuleDecision inspect(PDDocument document, WorkerRequest request) {
        return RuleDecision.apply("test double", null, "x");
      }

      @Override
      public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
        return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not write", null, "x");
      }
    };
  }

  private static WorkerRequest generatedWithTitle() {
    return originRequest(WorkerRequest.Mode.FIX, WorkerRequest.Origin.HTML_GENERATED, "Fallback title");
  }

  private String remediatedTitle() throws IOException {
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      return doc.getDocumentInformation().getTitle();
    }
  }

  /** How many structure elements of type {@code type} the published copy holds. */
  private int remediatedCount(String type) throws IOException {
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      int[] count = {0};
      java.util.ArrayDeque<Object> stack = new java.util.ArrayDeque<>(doc.getDocumentCatalog().getStructureTreeRoot().getKids());
      while (!stack.isEmpty()) {
        if (stack.pop() instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement e) {
          if (type.equals(e.getStructureType())) {
            count[0]++;
          }
          stack.addAll(e.getKids());
        }
      }
      return count[0];
    }
  }

  @Test
  void generatedGateAcceptsOnlyTheIdentifierCheckAsNewAndOnlyOnceXmpWasWritten() {
    Map<String, Integer> baseline = Map.of(METADATA_RULE, 1);
    assertNull(PdfProcessor.generatedRegression(baseline, Map.of(UAID_RULE, 1), true));
    assertNull(PdfProcessor.generatedRegression(baseline, Map.of(), true));
    String withoutXmp = PdfProcessor.generatedRegression(baseline, Map.of(UAID_RULE, 1), false);
    assertTrue(withoutXmp != null && withoutXmp.contains("5-1"), withoutXmp);
    String structureCheck = PdfProcessor.generatedRegression(baseline, Map.of(UAID_RULE, 1, "ISO 14289-1:2014 7.3-1", 1), true);
    assertTrue(structureCheck != null && structureCheck.contains("7.3-1"), "no structure allow-list: " + structureCheck);
    String grew = PdfProcessor.generatedRegression(Map.of(METADATA_RULE, 1), Map.of(METADATA_RULE, 2), true);
    assertTrue(grew != null && grew.contains("1 -> 2"), grew);
  }

  @Test
  void theStrictGateAllowsANewIdentifierCheckOnlyWhenThisRunWroteThePacket() {
    String spec = "ISO 14289-1:2014 ";
    assertNull(PdfProcessor.regression(Map.of(), Map.of(spec + "5-1", 1), true));
    assertEquals("validation regression: newly failing rule ISO 14289-1:2014 5-1 (1)", PdfProcessor.regression(Map.of(), Map.of(spec + "5-1", 1), false));
    assertNull(PdfProcessor.regression(Map.of(), Map.of(spec + "7.1-9", 1), true));
    assertEquals("validation regression: newly failing rule ISO 14289-1:2014 7.1-9 (1)", PdfProcessor.regression(Map.of(), Map.of(spec + "7.1-9", 1), false));
    assertEquals("validation regression: newly failing rule ISO 14289-1:2014 7.1-3 (1)", PdfProcessor.regression(Map.of(), Map.of(spec + "7.1-3", 1), true));
    assertEquals("validation regression: rule ISO 14289-1:2014 5-1 failures increased 1 -> 2",
        PdfProcessor.regression(Map.of(spec + "5-1", 1), Map.of(spec + "5-1", 2), true), "only a newly failing identifier check is exempt");
  }

  @Test
  void aGeneratedPdfInSafeFixOrStructureFixBehavesExactlyAsAnUploadDid() throws IOException {
    for (WorkerRequest.Mode mode : List.of(WorkerRequest.Mode.SAFE_FIX, WorkerRequest.Mode.STRUCTURE_FIX)) {
      List<WorkerResult> results = new ArrayList<>();
      for (WorkerRequest.Origin origin : List.of(WorkerRequest.Origin.UPLOAD, WorkerRequest.Origin.HTML_GENERATED)) {
        install(generatedFixture("generated-lists.pdf"));
        Files.deleteIfExists(workspace.outputFile("remediated.pdf"));
        validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
        validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
        results.add(production().process(originRequest(mode, origin), workspace));
      }
      WorkerResult upload = results.get(0);
      WorkerResult generated = results.get(1);
      assertEquals(upload.actions(), generated.actions(), mode + ": same actions");
      assertEquals(upload.status(), generated.status(), mode.toString());
      assertEquals(upload.remediationStatus(), generated.remediationStatus(), mode.toString());
      assertEquals(upload.error(), generated.error(), mode.toString());
      assertTrue(generated.actions().stream().noneMatch(a -> a.ruleId() == RuleId.PDF_GENERATED_STRUCTURE_V1), mode.toString());
    }
  }

  private PdfProcessor production() {
    return new PdfProcessor(new PdfPreflight(), validator, com.koldera.pdf.cli.Main.productionRules());
  }

  /**
   * A tagged upload whose tags every structure step keeps as they are (usable tags, nothing untagged, no Figure, a tree
   * the repair leaves alone) and on which the upload repairs act (a link without a description, a page with an
   * annotation and no /Tabs): a rollback then takes the upload-repair retry, not the structure ladder.
   */
  private static byte[] taggedUploadWithLink() {
    return SyntheticPdfFactory.taggedBlocks(
        SyntheticPdfFactory.TaggedOptions.of(SyntheticPdfFactory.Block.of("P", "Introduction"), SyntheticPdfFactory.Block.of("P", "Read the report")).link(1));
  }

  private static WorkerResult.Action action(WorkerResult result, RuleId id) {
    return result.actions().stream().filter(a -> a.ruleId() == id).findFirst().orElseThrow(() -> new AssertionError(id + " in " + result.actions()));
  }

  private static byte[] generatedFixture(String name) throws IOException {
    try (var in = PdfProcessorTest.class.getResourceAsStream("/generated/" + name)) {
      assertNotNull(in, name);
      return in.readAllBytes();
    }
  }

  private static WorkerRequest generatedOrUpload(WorkerRequest.Origin origin) {
    return originRequest(WorkerRequest.Mode.FIX, origin);
  }

  private static WorkerRequest originRequest(WorkerRequest.Mode mode, WorkerRequest.Origin origin) {
    return originRequest(mode, origin, null);
  }

  private static WorkerRequest originRequest(WorkerRequest.Mode mode, WorkerRequest.Origin origin, String explicitTitle) {
    return new WorkerRequest(
        "1.0", JOB, WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, mode, "ua1",
        null, explicitTitle, "page.pdf", origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  // ---- helpers ----------------------------------------------------------------------------

  private PdfProcessor processor() {
    return new PdfProcessor(new PdfPreflight(), validator, rules);
  }

  private byte[] install(byte[] bytes) throws IOException {
    Files.write(workspace.inputFile(), bytes);
    return bytes;
  }

  private void assertNoOutputPdf() throws IOException {
    assertFalse(Files.exists(workspace.outputFile("remediated.pdf")), "no remediated.pdf may be published");
    try (var files = Files.list(workspace.outputDirectory())) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".pdf")), "no pdf in out/");
    }
  }

  private void assertScratchClean() throws IOException {
    try (var files = Files.list(workspace.scratchDirectory())) {
      assertEquals(List.of(), files.toList(), "scratch must not leak candidates");
    }
  }

  private static void assertSchemaValid(WorkerResult result) {
    var errors = ContractSchemas.validateResult(WorkerResultCodec.toJson(result));
    assertTrue(errors.isEmpty(), errors::toString);
  }

  private static List<ArtifactKind> kinds(WorkerResult result) {
    return result.artifacts().stream().map(WorkerResult.Artifact::kind).toList();
  }

  private static WorkerResult.Artifact artifact(WorkerResult result, ArtifactKind kind) {
    return result.artifacts().stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
  }

  private static String sha(byte[] bytes) {
    return PdfPreflight.sha256Hex(bytes);
  }

  private static WorkerRequest request(WorkerRequest.Mode mode, String language) {
    return request(mode, language, 10_485_760, 20);
  }

  private static WorkerRequest request(WorkerRequest.Mode mode, String language, long maxBytes, int maxPages) {
    return new WorkerRequest(
        "1.0",
        JOB,
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        language,
        new WorkerRequest.Limits(maxBytes, maxPages, 60));
  }

  private static ValidationOutcome failed(Map<String, Integer> rules) {
    return new ValidationOutcome(ValidationStatus.FAILED, rules, Map.of(), null, null, null, "fake");
  }

  private static ValidationOutcome passed() {
    return new ValidationOutcome(ValidationStatus.PASSED, Map.of(), Map.of(), null, null, null, "fake");
  }

  /** Returns queued outcomes in order, records calls and writes a stand-in raw report file. */
  private static final class ScriptedValidator implements PdfValidator {
    record Call(Path pdf, Path report, Duration timeout) {}

    final List<Call> calls = new ArrayList<>();
    private final Deque<ValidationOutcome> queue = new ArrayDeque<>();

    void enqueue(ValidationOutcome outcome) {
      queue.add(outcome);
    }

    @Override
    public ValidationOutcome validate(Path pdf, Path reportPath, Duration timeout) {
      calls.add(new Call(pdf, reportPath, timeout));
      if (queue.isEmpty()) {
        throw new AssertionError("validator called more often than scripted");
      }
      ValidationOutcome scripted = queue.poll();
      if (scripted.errorCode() == ErrorCode.VALIDATOR_UNAVAILABLE) {
        return scripted;
      }
      try {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, "{\"fake\":true,\"call\":" + calls.size() + "}", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return new ValidationOutcome(
          scripted.status(),
          scripted.failedRuleCounts(),
          scripted.ruleDescriptions(),
          reportPath,
          scripted.errorCode(),
          scripted.errorMessage(),
          scripted.validatorVersion());
    }
  }
}
