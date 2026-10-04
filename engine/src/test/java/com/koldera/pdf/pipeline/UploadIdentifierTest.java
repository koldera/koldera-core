package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.generated.StructureRules;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PDF_UA_IDENTIFIER_V1} for uploads: exactly the generated-PDF rule. Only
 * when the final validation fails nothing but {@code 5-1}, the PAC-only structure checks report nothing and the
 * identified copy validates with zero failures; deprecated modes and a generated run's fallback never write it.
 */
class UploadIdentifierTest {

  private static final UUID JOB = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final String SPEC = "ISO 14289-1:2014 ";
  private static final String UAID_RULE = SPEC + "5-1";
  private static final String FONT_RULE = SPEC + "7.21.4.1-1";
  private static final String METADATA_RULE = SPEC + "7.1-8";
  private static final Map<String, Integer> UNTAGGED_BASELINE =
      Map.of(SPEC + "7.2-34", 1, SPEC + "7.1-10", 1, SPEC + "7.1-11", 1, SPEC + "7.1-3", 3, SPEC + "6.2-1", 1, METADATA_RULE, 1);

  @TempDir Path root;
  private JobWorkspace workspace;
  private ScriptedValidator validator;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("in"));
    workspace = JobWorkspace.open(root);
    validator = new ScriptedValidator();
  }

  @Test
  void anUploadCleanExceptTheIdentifierGetsIt() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedEmbeddedFontArticle());
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(upload(WorkerRequest.Mode.FIX), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.APPLIED, ua.outcome(), ua.reason());
    assertEquals(RuleId.PDF_UA_IDENTIFIER_V1, result.actions().get(result.actions().size() - 1).ruleId(), "listed last: it runs after the final validation");
    assertEquals(3, validator.calls.size(), "baseline, the copy, the identified copy");
    assertEquals(ValidationStatus.PASSED, result.finalValidation().status(), "the published copy's own validation");
    assertTrue(result.finalValidation().failedRules().isEmpty());
    assertEquals("1", pdfUaPart(), "pdfuaid:part in the published copy's XMP");
    assertTrue(has(result, FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY), result.findings().toString());
    assertTrue(has(result, FindingsBuilder.STRUCTURE_INFERRED), "the findings list what was inferred: " + result.findings());
    assertTrue(result.manualReviewRequired());
    assertNoConformanceWording(result);
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void anUploadWithAnotherFailureGetsNoIdentifier() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedEmbeddedFontArticle());
    Map<String, Integer> baseline = new java.util.HashMap<>(UNTAGGED_BASELINE);
    baseline.put(FONT_RULE, 1);
    validator.enqueue(failed(baseline));
    validator.enqueue(failed(Map.of(UAID_RULE, 1, FONT_RULE, 1)));

    WorkerResult result = production().process(upload(WorkerRequest.Mode.FIX), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome());
    assertTrue(ua.reason().startsWith("not written: the final validation still fails " + FONT_RULE), ua.reason());
    assertEquals(2, validator.calls.size(), "no identified copy is made or validated");
    assertEquals("", pdfUaPart());
    assertFalse(has(result, FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY));
    assertTrue(result.manualReviewRequired());
    assertScratchClean();
    assertSchemaValid(result);
  }

  @Test
  void anUploadWhoseStructureChecksReportSomethingGetsNoIdentifier() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.taggedRuledTableWithoutHead());
    validator.enqueue(failed(Map.of(METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));

    WorkerResult result = production().process(upload(WorkerRequest.Mode.FIX), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome(), ua.reason());
    assertTrue(ua.reason().contains("the internal structure checks") && ua.reason().contains(StructureRules.TBODY_WITHOUT_THEAD), ua.reason());
    assertEquals(2, validator.calls.size(), "no identified copy is made or validated");
    assertEquals("", pdfUaPart());
    assertEquals(List.of(UAID_RULE), result.finalValidation().failedRules().stream().map(WorkerResult.FailedRule::ruleId).toList());
    assertFalse(has(result, FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY));
    assertScratchClean();
    assertSchemaValid(result);
  }

  /** A copy with no tagged text presents nothing a machine check can judge. */
  @Test
  void anImageOnlyScanGetsNoIdentifier() throws IOException {
    assertNoTaggedTextBlocksTheIdentifier(SyntheticPdfFactory.untaggedImageOnlyScan());
  }

  @Test
  void anOutlineOnlyPageGetsNoIdentifier() throws IOException {
    assertNoTaggedTextBlocksTheIdentifier(SyntheticPdfFactory.untaggedOutlinesOnly());
  }

  private void assertNoTaggedTextBlocksTheIdentifier(byte[] input) throws IOException {
    Files.write(workspace.inputFile(), input);
    validator.enqueue(failed(Map.of(SPEC + "7.1-3", 1, METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(upload(WorkerRequest.Mode.FIX), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome(), ua.reason());
    assertEquals("not written: the copy has no tagged text (all content is decoration), which no machine check can judge", ua.reason());
    assertEquals(2, validator.calls.size(), "no identified copy is made or validated");
    assertEquals("", pdfUaPart());
    assertFalse(has(result, FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY));
    assertTrue(result.manualReviewRequired());
    assertScratchClean();
    assertSchemaValid(result);
  }

  /** PAC "Title in XMP metadata" (un-charter-aspose): the producer's empty dc:title is filled with the resolved title. */
  @Test
  void anEmptyXmpTitleIsFilledWithTheFileNameTitleAndTheCopyIsIdentified() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.withXmpPacket(SyntheticPdfFactory.untaggedEmbeddedFontArticle(), SyntheticPdfFactory.EMPTY_DC_TITLE_PACKET));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(request(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, null, "un-charter.pdf"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action title = action(result, RuleId.PDF_TITLE_V1);
    assertEquals("title taken from the file name; check it", title.reason());
    assertEquals("un-charter", dcTitle());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_UA_IDENTIFIER_V1).outcome());
    assertSchemaValid(result);
  }

  @Test
  void aCopyWhoseXmpTitleStaysBlankGetsNoIdentifier() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.withXmpPacket(SyntheticPdfFactory.untaggedEmbeddedFontArticle(), SyntheticPdfFactory.EMPTY_DC_TITLE_PACKET));
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    // No explicit title and a placeholder file name: nothing resolves, the empty dc:title stays.
    WorkerResult result = production().process(request(WorkerRequest.Mode.FIX, WorkerRequest.Origin.UPLOAD, null, "document.pdf"), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action ua = action(result, RuleId.PDF_UA_IDENTIFIER_V1);
    assertEquals(ActionOutcome.SKIPPED, ua.outcome(), ua.reason());
    assertEquals("not written: the XMP metadata has no title", ua.reason());
    for (WorkerResult.Action a : result.actions()) {
      assertFalse(a.reason() != null && a.reason().contains("already has a title"), "no reason claims a title: " + a.reason());
      assertFalse(a.reason() != null && a.reason().contains("already has dc:title"), "no reason claims a title: " + a.reason());
    }
    assertEquals(2, validator.calls.size(), "no identified copy is made or validated");
    assertEquals("", pdfUaPart());
    assertScratchClean();
    assertSchemaValid(result);
  }

  /** The x-default dc:title of the published copy, or {@code ""}. */
  private String dcTitle() throws IOException {
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      DomXmpParser parser = new DomXmpParser();
      parser.setStrictParsing(false);
      String title = parser.parse(doc.getDocumentCatalog().getMetadata().toByteArray()).getDublinCoreSchema().getTitle("x-default");
      return title == null ? "" : title;
    } catch (org.apache.xmpbox.xml.XmpParsingException | org.apache.xmpbox.type.BadFieldValueException e) {
      throw new IOException(e);
    }
  }

  @Test
  void structureFixNeverListsTheIdentifier() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedEmbeddedFontArticle());
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));

    WorkerResult result = production().process(upload(WorkerRequest.Mode.STRUCTURE_FIX), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertTrue(result.actions().stream().noneMatch(a -> a.ruleId() == RuleId.PDF_UA_IDENTIFIER_V1), result.actions().toString());
    assertEquals(2, validator.calls.size());
    assertEquals("", pdfUaPart());
    assertSchemaValid(result);
  }

  @Test
  void aGeneratedFallbackStillWritesNoIdentifier() throws IOException {
    try (var in = UploadIdentifierTest.class.getResourceAsStream("/generated/generated-lists.pdf")) {
      Files.write(workspace.inputFile(), in.readAllBytes());
    }
    // The fallback's final validation fails only 5-1: an upload run would try the identifier here.
    validator.enqueue(failed(Map.of(UAID_RULE, 1, METADATA_RULE, 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());
    List<RemediationRule> rules = new ArrayList<>(Main.productionRules());
    rules.replaceAll(r -> r.id() == RuleId.PDF_GENERATED_STRUCTURE_V1 ? failingNormalizer() : r);

    WorkerResult result = new PdfProcessor(new PdfPreflight(), validator, rules)
        .process(request(WorkerRequest.Mode.FIX, WorkerRequest.Origin.HTML_GENERATED), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertTrue(result.actions().stream().noneMatch(a -> a.ruleId() == RuleId.PDF_UA_IDENTIFIER_V1), result.actions().toString());
    WorkerResult.Action generated = action(result, RuleId.PDF_GENERATED_STRUCTURE_V1);
    assertEquals(ActionOutcome.FAILED, generated.outcome());
    assertTrue(generated.reason().endsWith(PdfProcessor.FALLBACK_NOTE), generated.reason());
    assertEquals(2, validator.calls.size(), "baseline and the fallback copy; no identified copy");
    assertEquals("", pdfUaPart());
    assertFalse(has(result, FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY));
    assertSchemaValid(result);
  }

  /**
   * Decision: a ladder rerun (an upload run repeated without the structure steps after the first run was rolled back)
   * is an ordinary upload {@code fix} run, so the same machine check applies to the copy it publishes; the identifier is
   * written when that copy's final validation fails only {@code 5-1} and the identified copy validates clean.
   */
  @Test
  void aLadderRerunWhoseCopyIsCleanExceptTheIdentifierGetsIt() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedEmbeddedFontArticle());
    validator.enqueue(failed(UNTAGGED_BASELINE));
    validator.enqueue(failed(Map.of(SPEC + "7.1-3", 1)));
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(upload(WorkerRequest.Mode.FIX), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(4, validator.calls.size(), "baseline, the rolled-back run, the basic-tagger run, its identified copy");
    WorkerResult.Action inferred = action(result, RuleId.PDF_STRUCTURE_INFERRED_V1);
    assertEquals(ActionOutcome.ROLLED_BACK, inferred.outcome());
    assertTrue(inferred.reason().endsWith(PdfProcessor.STRUCTURE_NOTE), inferred.reason());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1).outcome());
    assertEquals(ActionOutcome.APPLIED, action(result, RuleId.PDF_UA_IDENTIFIER_V1).outcome());
    assertEquals(1, result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_UA_IDENTIFIER_V1).count());
    assertEquals("1", pdfUaPart());
    assertEquals(ValidationStatus.PASSED, result.finalValidation().status());
    assertTrue(has(result, FindingsBuilder.PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY));
    assertTrue(result.manualReviewRequired());
    assertScratchClean();
    assertSchemaValid(result);
  }

  // ---- helpers ----------------------------------------------------------------------------

  private PdfProcessor production() {
    return new PdfProcessor(new PdfPreflight(), validator, Main.productionRules());
  }

  private static WorkerRequest upload(WorkerRequest.Mode mode) {
    return request(mode, WorkerRequest.Origin.UPLOAD);
  }

  private static WorkerRequest request(WorkerRequest.Mode mode, WorkerRequest.Origin origin) {
    return new WorkerRequest(
        "1.0", JOB, WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, mode, "ua1", "en",
        "Quarterly summary", "summary.pdf", origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  private static WorkerRequest request(WorkerRequest.Mode mode, WorkerRequest.Origin origin, String explicitTitle, String fileName) {
    return new WorkerRequest(
        "1.0", JOB, WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, mode, "ua1", "en",
        explicitTitle, fileName, origin, new WorkerRequest.Limits(10_485_760, 20, 60));
  }

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

  /** The {@code pdfuaid:part} value in the published copy's XMP packet, or {@code ""} when there is none. */
  private String pdfUaPart() throws IOException {
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      var metadata = doc.getDocumentCatalog().getMetadata();
      if (metadata == null) {
        return "";
      }
      DomXmpParser parser = new DomXmpParser();
      parser.setStrictParsing(false);
      XMPMetadata xmp = parser.parse(metadata.toByteArray());
      XMPSchema schema = xmp.getSchema("http://www.aiim.org/pdfua/ns/id/");
      return schema != null && schema.getProperty("part") instanceof org.apache.xmpbox.type.AbstractSimpleProperty part ? String.valueOf(part.getValue()) : "";
    } catch (org.apache.xmpbox.xml.XmpParsingException e) {
      throw new IOException(e);
    }
  }

  private static WorkerResult.Action action(WorkerResult result, RuleId id) {
    return result.actions().stream().filter(a -> a.ruleId() == id).findFirst().orElseThrow(() -> new AssertionError(id + " in " + result.actions()));
  }

  private static boolean has(WorkerResult result, String findingId) {
    return result.findings().stream().anyMatch(f -> f.ruleId().equals(findingId));
  }

  private static void assertNoConformanceWording(WorkerResult result) {
    String json = WorkerResultCodec.toJson(result).toLowerCase(Locale.ROOT);
    for (String word : List.of("compliant", "compliance", "certified", "certificate", "100%", "accessible pdf")) { // hygiene-allow: claim-word
      assertFalse(json.contains(word), "no '" + word + "' wording: " + json);
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
      try {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, "{\"fake\":true,\"call\":" + calls.size() + "}", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return new ValidationOutcome(
          scripted.status(), scripted.failedRuleCounts(), scripted.ruleDescriptions(), reportPath, scripted.errorCode(), scripted.errorMessage(),
          scripted.validatorVersion());
    }
  }
}
