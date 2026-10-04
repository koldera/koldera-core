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
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.rules.BasicStructureRule;
import com.koldera.pdf.rules.DisplayDocumentTitleRule;
import com.koldera.pdf.rules.ExplicitLanguageRule;
import com.koldera.pdf.rules.RemediationRule;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSDictionary;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.rules.XmpMetadataRule;
import com.koldera.pdf.validation.PdfValidator;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code structure_fix}: mode dispatch, the structure gate and its allow-list, findings and determinism. */
class StructureFixProcessorTest {

  private static final UUID JOB = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final String SPEC = "ISO 14289-1:2014 ";
  private static final String LANG_RULE = SPEC + "7.2-34";
  private static final String TITLE_RULE = SPEC + "7.1-10";
  private static final String STRUCT_RULE = SPEC + "7.1-11";
  private static final String CONTENT_RULE = SPEC + "7.1-3";
  private static final String MARKINFO_RULE = SPEC + "6.2-1";
  private static final String METADATA_RULE = SPEC + "7.1-8";
  private static final String FONT_RULE = SPEC + "7.21.4.1-1";
  private static final Map<String, Integer> UNTAGGED_BASELINE =
      Map.of(LANG_RULE, 1, TITLE_RULE, 1, STRUCT_RULE, 1, CONTENT_RULE, 3, MARKINFO_RULE, 1, METADATA_RULE, 1, FONT_RULE, 1);

  @TempDir Path root;
  private JobWorkspace workspace;
  private ScriptedValidator validator;

  @BeforeEach
  void setUp() throws IOException {
    glyphInventoriesBefore = PdfProcessor.glyphInventoriesComputedForTests.get();
    Files.createDirectories(root.resolve("in"));
    workspace = JobWorkspace.open(root);
    validator = new ScriptedValidator();
  }

  // ---- gate pinned ------------------------------------------------------------------------

  @Test
  void allowListAndMustReachZeroRulesArePinned() {
    assertEquals(List.of("7.2", "7.3", "7.4", "7.5", "7.17", "7.18"), PdfProcessor.STRUCTURE_GATE_ALLOWED_NEW_CLAUSES);
    assertEquals(List.of("5-1", "7.1-9"), PdfProcessor.STRUCTURE_GATE_ALLOWED_NEW_RULES);
    assertEquals(List.of("7.1-3", "7.1-11", "6.2-1", "7.1-8"), PdfProcessor.STRUCTURE_GATE_MUST_REACH_ZERO);
  }

  @Test
  void structureGateAcceptsOnlyAllowListedNewFailures() {
    Map<String, Integer> baseline = Map.of(STRUCT_RULE, 1, CONTENT_RULE, 3, MARKINFO_RULE, 1, METADATA_RULE, 1, FONT_RULE, 1);

    assertNull(PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1)));
    assertNull(PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, SPEC + "7.3-1", 2, SPEC + "5-1", 1, SPEC + "7.1-9", 1)));
    assertNull(PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, SPEC + "7.18.5-1", 1, SPEC + "7.4.4-1", 1, SPEC + "7.5-2", 1, SPEC + "7.2-33", 1)));
    assertNull(PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, SPEC + "7.17-1", 1)));

    String disallowed = PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, SPEC + "7.20-2", 1));
    assertNotNull(disallowed);
    assertTrue(disallowed.contains("7.20-2"), disallowed);
    String prefixTrick = PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, SPEC + "7.30-1", 1));
    assertTrue(prefixTrick != null && prefixTrick.contains("7.30-1"), "7.30 is not 7.3: " + prefixTrick);
    String increased = PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 2));
    assertTrue(increased != null && increased.contains("7.21.4.1-1"), increased);
    String notZero = PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, CONTENT_RULE, 1));
    assertTrue(notZero != null && notZero.contains("7.1-3"), notZero);
    String stillUntagged = PdfProcessor.structureRegression(baseline, Map.of(FONT_RULE, 1, STRUCT_RULE, 1));
    assertTrue(stillUntagged != null && stillUntagged.contains("7.1-11"), stillUntagged);
  }

  @Test
  void fontCheckIncreasesAreAcceptedOnlyWhenTheGlyphInventoryIsUnchanged() {
    // veraPDF identifies a glyph per marked-content sequence, so 7.21.x counts grow with the number of
    // MCIDs although not one glyph changed; a real glyph regression (inventory differs) still fails.
    Map<String, Integer> baseline = Map.of(STRUCT_RULE, 1, CONTENT_RULE, 3, MARKINFO_RULE, 1, METADATA_RULE, 1, SPEC + "7.21.7-1", 49, SPEC + "7.21.8-1", 302);

    assertNull(PdfProcessor.structureRegression(baseline, Map.of(SPEC + "7.21.7-1", 176, SPEC + "7.21.8-1", 722), true));
    String strict = PdfProcessor.structureRegression(baseline, Map.of(SPEC + "7.21.7-1", 176), false);
    assertTrue(strict != null && strict.contains("7.21.7-1") && strict.contains("49 -> 176"), strict);
    assertEquals(strict, PdfProcessor.structureRegression(baseline, Map.of(SPEC + "7.21.7-1", 176)), "the two-argument gate stays strict");
    String otherClause = PdfProcessor.structureRegression(Map.of(STRUCT_RULE, 1, CONTENT_RULE, 3, MARKINFO_RULE, 1, METADATA_RULE, 1, SPEC + "7.18.1-2", 4), Map.of(SPEC + "7.18.1-2", 5), true);
    assertTrue(otherClause != null && otherClause.contains("7.18.1-2"), "only font/glyph checks are recounted: " + otherClause);
    String newFontRule = PdfProcessor.structureRegression(baseline, Map.of(SPEC + "7.21.7-1", 49, SPEC + "7.21.8-1", 302, SPEC + "7.21.4.1-1", 1), true);
    assertTrue(newFontRule != null && newFontRule.contains("7.21.4.1-1"), "a font check that did not fail before is still new: " + newFontRule);
  }

  private int glyphInventoriesBefore;

  // ---- pipeline -----------------------------------------------------------------------------

  @Test
  void rolledBackStructureFixCarriesNoResolutionAnywhere() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.20-2", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.FIX, "en-US"), workspace);

    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertTrue(result.findings().stream().allMatch(f -> f.resolution() == null), result.findings().toString());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().contains("7.20-2")), result.findings().toString());
  }

  private static WorkerResult.Finding finding(WorkerResult result, String ruleId) {
    return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).findFirst().orElseThrow(() -> new AssertionError("no finding " + ruleId + " in " + result.findings()));
  }

  @Test
  void disallowedNewFailureAfterStructureFixIsRolledBack() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.20-2", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().contains("7.20-2"), result.error().message());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.ROLLED_BACK), result.actions().toString());
    assertNoOutputPdf();
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void structureFixOnTaggedDocumentKeepsTheStrictGate() throws IOException {
    install(SyntheticPdfFactory.tagged());
    validator.enqueue(failed(Map.of(TITLE_RULE, 1, FONT_RULE, 1)));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.3-1", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertNoOutputPdf();
  }

  @Test
  void signedAndEncryptedInputsKeepTheirRefusalCodes() throws IOException {
    install(SyntheticPdfFactory.signed());
    WorkerResult signed = processor().process(request(WorkerRequest.Mode.FIX, "en-US"), workspace);
    assertEquals(Status.UNSUPPORTED, signed.status());
    assertEquals(ErrorCode.UNSUPPORTED_SIGNED_MUTATION, signed.error().code());
    assertEquals(RemediationStatus.SKIPPED, signed.remediationStatus());

    install(SyntheticPdfFactory.encrypted("pw"));
    WorkerResult encrypted = processor().process(request(WorkerRequest.Mode.FIX, "en-US"), workspace);
    assertEquals(Status.UNSUPPORTED, encrypted.status());
    assertEquals(ErrorCode.UNSUPPORTED_ENCRYPTED, encrypted.error().code());

    assertTrue(validator.calls.isEmpty());
    assertNoOutputPdf();
    assertSchemaValid(signed);
    assertSchemaValid(encrypted);
  }

  // ---- helpers ----------------------------------------------------------------------------

  private PdfProcessor processor() {
    BasicStructureRule structure = new BasicStructureRule();
    List<RemediationRule> rules =
        List.of(new ExplicitLanguageRule(), new DisplayDocumentTitleRule(), structure, new XmpMetadataRule(structure));
    return new PdfProcessor(new PdfPreflight(), validator, rules);
  }

  private byte[] install(byte[] bytes) throws IOException {
    Files.write(workspace.inputFile(), bytes);
    return bytes;
  }

  private void assertNoOutputPdf() throws IOException {
    assertFalse(Files.exists(workspace.outputFile("remediated.pdf")), "no remediated.pdf may be published");
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

  private static WorkerRequest request(WorkerRequest.Mode mode, String language) {
    return new WorkerRequest(
        "1.0",
        JOB,
        WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        mode,
        "ua1",
        language,
        new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  private static ValidationOutcome failed(Map<String, Integer> rules) {
    return new ValidationOutcome(ValidationStatus.FAILED, rules, Map.of(), null, null, null, "fake");
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
      try {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, "{\"fake\":true}", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return new ValidationOutcome(
          scripted.status(), scripted.failedRuleCounts(), scripted.ruleDescriptions(), reportPath, scripted.errorCode(), scripted.errorMessage(), scripted.validatorVersion());
    }
  }
}
