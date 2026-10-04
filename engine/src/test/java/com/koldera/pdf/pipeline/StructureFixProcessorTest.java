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

  @Test
  void fontCheckRecountAfterTaggingIsPublishedWithAnExplanatoryFinding() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 3)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Finding recount = result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.FONT_CHECKS_RECOUNTED)).findFirst().orElseThrow();
    assertTrue(recount.message().contains("7.21.4.1-1 1 -> 3"), recount.message());
    assertTrue(recount.message().contains("unchanged"), recount.message());
    assertSchemaValid(result);
  }

  @Test
  void fontCheckGrowthWithAChangedGlyphInventoryRollsBackWithoutARecountFinding() throws IOException {
    // A rule after the structure rule swaps the page font's program (BaseFont) without changing the
    // extracted text: the glyph inventory differs, so a 7.21 increase is a regression, not a recount.
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 3)));
    RemediationRule fontSwap =
        new RemediationRule() {
          @Override
          public WorkerResult.RuleId id() {
            return RuleId.PDF_XMP_METADATA_FROM_INFO_V1;
          }

          @Override
          public RuleDecision inspect(PDDocument document, WorkerRequest request) {
            return RuleDecision.apply("test: swap the font program", null, null);
          }

          @Override
          public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
            var resources = document.getPage(0).getResources();
            for (COSName name : resources.getFontNames()) {
              COSDictionary font = (COSDictionary) resources.getCOSObject().getCOSDictionary(COSName.FONT).getDictionaryObject(name);
              font.setName(COSName.BASE_FONT, "Courier");
            }
            return new WorkerResult.Action(id(), ActionOutcome.APPLIED, "test: swapped the font program", null, null);
          }
        };
    BasicStructureRule structure = new BasicStructureRule();
    PdfProcessor processor =
        new PdfProcessor(new PdfPreflight(), validator, List.of(new ExplicitLanguageRule(), new DisplayDocumentTitleRule(), structure, new XmpMetadataRule(structure), fontSwap));

    WorkerResult result = processor.process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().contains("7.21.4.1-1 failures increased 1 -> 3"), result.error().message());
    assertTrue(result.error().message().contains("glyph inventory differs"), result.error().message());
    assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.FONT_CHECKS_RECOUNTED)), result.findings().toString());
    assertNoOutputPdf();
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void safeFixDoesNotComputeAGlyphInventory() throws IOException {
    // The inventory is a structure_fix concern; safe_fix must not parse every content stream for it.
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(Map.of(LANG_RULE, 1, FONT_RULE, 1)));
    validator.enqueue(failed(Map.of(FONT_RULE, 1)));
    PdfProcessor processor = processor();

    WorkerResult result = processor.process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(0, PdfProcessor.glyphInventoriesComputedForTests.get() - glyphInventoriesBefore, "safe_fix never computes the glyph inventory");
  }

  private int glyphInventoriesBefore;

  @Test
  void safeFixGateIsUnchanged() {
    assertNotNull(PdfProcessor.regression(Map.of(STRUCT_RULE, 1), Map.of(STRUCT_RULE, 1, SPEC + "7.3-1", 1)), "safe_fix tolerates no new failure, allow-listed or not");
    assertNull(PdfProcessor.regression(Map.of(STRUCT_RULE, 1), Map.of()));
  }

  // ---- pipeline -----------------------------------------------------------------------------

  @Test
  void structureFixTagsUntaggedDocumentWritesMetadataAndPublishesWithStructureGate() throws IOException {
    byte[] original = install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report").image()));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.3-1", 1, SPEC + "5-1", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(
        List.of(
            RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1,
            RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
            RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1,
            RuleId.PDF_XMP_METADATA_FROM_INFO_V1),
        result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    assertTrue(result.actions().stream().allMatch(a -> a.outcome() == ActionOutcome.APPLIED), result.actions().toString());
    assertEquals(2, validator.calls.size());
    assertEquals(ValidationStatus.FAILED, result.finalValidation().status(), "allow-listed findings remain real findings");
    assertEquals(List.of(ArtifactKind.ORIGINAL, ArtifactKind.BASELINE_REPORT, ArtifactKind.REMEDIATED, ArtifactKind.FINAL_REPORT), kinds(result));
    byte[] out = Files.readAllBytes(workspace.outputFile("remediated.pdf"));
    try (PDDocument doc = Loader.loadPDF(out)) {
      assertNotNull(doc.getDocumentCatalog().getStructureTreeRoot());
      assertTrue(doc.getDocumentCatalog().getMarkInfo().isMarked());
      assertNotNull(doc.getDocumentCatalog().getMetadata());
      String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("Report"), xmp);
      assertTrue(xmp.contains("en-US"), "dc:language reflects the language applied earlier in the same run: " + xmp);
      assertFalse(xmp.contains("pdfuaid"), xmp);
      assertEquals("en-US", doc.getDocumentCatalog().getLanguage());
      assertEquals(1, doc.getNumberOfPages());
    }
    assertArrayEquals(original, Files.readAllBytes(workspace.inputFile()), "original immutable");
    WorkerResult.Finding review =
        result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW)).findFirst().orElseThrow();
    assertEquals(
        "Basic tags were added from the page content in content-stream order: paragraphs, figures without alternative text,"
            + " decorative artifacts. Headings, tables, alternative text and the reading order were not inferred and must be reviewed.",
        review.message());
    assertTrue(review.needsHumanReview());
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals(FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW)));
    assertTrue(result.findings().stream().noneMatch(f -> f.message().toLowerCase().contains("accessible")), result.findings().toString());
    assertTrue(result.manualReviewRequired());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void structureFixResolvesBaselineChecksAndListsTheAllowListedNewFailuresAsStillFailing() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report").image()));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.3-1", 1, SPEC + "5-1", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    for (String fixed : List.of(CONTENT_RULE, STRUCT_RULE, MARKINFO_RULE, METADATA_RULE, LANG_RULE, TITLE_RULE)) {
      assertEquals(Resolution.FIXED_IN_COPY, finding(result, fixed).resolution(), fixed);
    }
    assertEquals(Resolution.STILL_FAILING, finding(result, FONT_RULE).resolution());
    WorkerResult.Finding figure = finding(result, SPEC + "7.3-1");
    assertEquals(Resolution.STILL_FAILING, figure.resolution(), "a rule that first fails in the copy is listed like the after-fix report");
    assertFalse(figure.needsHumanReview());
    assertEquals(Resolution.STILL_FAILING, finding(result, SPEC + "5-1").resolution());
    List<String> ids = result.findings().stream().map(WorkerResult.Finding::ruleId).toList();
    assertTrue(ids.indexOf(SPEC + "7.3-1") < ids.indexOf(FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW), "machine checks stay grouped before review items: " + ids);
    assertNull(finding(result, FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW).resolution());
    assertNull(finding(result, FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW).resolution());
    assertNull(finding(result, FindingsBuilder.MANUAL_REVIEW_REQUIRED).resolution());
    assertSchemaValid(result);
  }

  @Test
  void fontRecountNoteCarriesNoResolutionWhileTheRecountedRuleStaysAMachineCheck() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 3)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), String.valueOf(result.error()));
    WorkerResult.Finding note = finding(result, FindingsBuilder.FONT_CHECKS_RECOUNTED);
    assertFalse(note.needsHumanReview());
    assertNull(note.resolution(), "an informational note is neither fixed nor failing");
    assertEquals(Resolution.STILL_FAILING, finding(result, FONT_RULE).resolution());
    assertEquals(Resolution.FIXED_IN_COPY, finding(result, CONTENT_RULE).resolution());
  }

  @Test
  void rolledBackStructureFixCarriesNoResolutionAnywhere() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.20-2", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

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

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

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
  void structureRuleThatLeavesUntaggedContentIsRolledBack() throws IOException {
    install(SyntheticPdfFactory.build(Options.plain().infoTitle("Report")));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, CONTENT_RULE, 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertTrue(result.error().message().contains("7.1-3"), result.error().message());
    assertNoOutputPdf();
  }

  @Test
  void structureFixOnTaggedDocumentSkipsStructureRulesAndBehavesExactlyLikeSafeFix() throws IOException {
    byte[] tagged = SyntheticPdfFactory.tagged();
    Map<String, Integer> baseline = Map.of(TITLE_RULE, 1, METADATA_RULE, 1, FONT_RULE, 1);
    Map<String, Integer> after = Map.of(METADATA_RULE, 1, FONT_RULE, 1);

    install(tagged);
    validator.enqueue(failed(baseline));
    validator.enqueue(failed(after));
    WorkerResult structure = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);
    byte[] structureOut = Files.readAllBytes(workspace.outputFile("remediated.pdf"));

    install(tagged);
    validator.enqueue(failed(baseline));
    validator.enqueue(failed(after));
    WorkerResult safe = processor().process(request(WorkerRequest.Mode.SAFE_FIX, "en-US"), workspace);
    byte[] safeOut = Files.readAllBytes(workspace.outputFile("remediated.pdf"));

    assertEquals(Status.COMPLETED, structure.status(), String.valueOf(structure.error()));
    assertEquals(RemediationStatus.APPLIED, structure.remediationStatus());
    Map<RuleId, ActionOutcome> outcomes = new java.util.EnumMap<>(RuleId.class);
    structure.actions().forEach(a -> outcomes.put(a.ruleId(), a.outcome()));
    assertEquals(ActionOutcome.SKIPPED, outcomes.get(RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1));
    assertEquals(ActionOutcome.SKIPPED, outcomes.get(RuleId.PDF_XMP_METADATA_FROM_INFO_V1));
    assertTrue(structure.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1).findFirst().orElseThrow().reason().contains("already tagged"));
    assertEquals(2, safe.actions().size(), "safe_fix lists exactly the two metadata rules; structure rules do not participate");
    assertEquals(safe.actions(), structure.actions().subList(0, 2));
    assertArrayEquals(safeOut, structureOut, "same remediated bytes as safe_fix");
    assertEquals(safe.findings(), structure.findings());
    assertTrue(structure.findings().stream().noneMatch(f -> f.ruleId().equals(FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW)));
    assertSchemaValid(structure);
  }

  @Test
  void structureFixOnTaggedDocumentKeepsTheStrictGate() throws IOException {
    install(SyntheticPdfFactory.tagged());
    validator.enqueue(failed(Map.of(TITLE_RULE, 1, FONT_RULE, 1)));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "7.3-1", 1)));

    WorkerResult result = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertNoOutputPdf();
  }

  @Test
  void signedAndEncryptedInputsKeepTheirRefusalCodes() throws IOException {
    install(SyntheticPdfFactory.signed());
    WorkerResult signed = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);
    assertEquals(Status.UNSUPPORTED, signed.status());
    assertEquals(ErrorCode.UNSUPPORTED_SIGNED_MUTATION, signed.error().code());
    assertEquals(RemediationStatus.SKIPPED, signed.remediationStatus());

    install(SyntheticPdfFactory.encrypted("pw"));
    WorkerResult encrypted = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en-US"), workspace);
    assertEquals(Status.UNSUPPORTED, encrypted.status());
    assertEquals(ErrorCode.UNSUPPORTED_ENCRYPTED, encrypted.error().code());

    assertTrue(validator.calls.isEmpty());
    assertNoOutputPdf();
    assertSchemaValid(signed);
    assertSchemaValid(encrypted);
  }

  @Test
  void outputIsByteDeterministicEvenWhenTheInputHasNoTrailerId() throws IOException {
    byte[] original = SyntheticPdfFactory.untaggedWithoutTrailerId();
    try (PDDocument doc = Loader.loadPDF(original)) {
      assertNull(doc.getDocument().getTrailer().getItem(org.apache.pdfbox.cos.COSName.ID), "fixture really lacks /ID");
    }

    install(original);
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "5-1", 1, SPEC + "7.1-9", 1)));
    WorkerResult first = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en"), workspace);
    byte[] firstOut = Files.readAllBytes(workspace.outputFile("remediated.pdf"));

    install(original);
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(FONT_RULE, 1, SPEC + "5-1", 1, SPEC + "7.1-9", 1)));
    WorkerResult second = processor().process(request(WorkerRequest.Mode.STRUCTURE_FIX, "en"), workspace);
    byte[] secondOut = Files.readAllBytes(workspace.outputFile("remediated.pdf"));

    assertEquals(Status.COMPLETED, first.status(), String.valueOf(first.error()));
    assertEquals(Status.COMPLETED, second.status(), String.valueOf(second.error()));
    assertArrayEquals(firstOut, secondOut, "structure_fix output is reproducible");
    try (PDDocument doc = Loader.loadPDF(firstOut)) {
      assertNotNull(doc.getDocumentCatalog().getStructureTreeRoot());
      assertNull(doc.getDocumentInformation().getModificationDate(), "input had no Info dates; none are invented");
    }
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
