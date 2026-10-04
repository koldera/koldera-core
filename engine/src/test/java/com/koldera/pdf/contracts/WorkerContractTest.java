package com.koldera.pdf.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.report.ReportWriter;
import com.koldera.pdf.rules.ExplicitLanguageRuleTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerContractTest {

  /** The example shipped in the repository-level contracts directory must stay parseable. */
  private static final Path REPO_EXAMPLE =
      Path.of("..", "contracts", "examples", "pdf-worker-request.fix.json");

  private static final String VALID_ANALYZE_REQUEST =
      """
      {
        "specVersion": "1.0",
        "jobId": "123e4567-e89b-42d3-a456-426614174000",
        "inputPath": "/work/in/document.pdf",
        "outputDirectory": "/work/out",
        "mode": "analyze",
        "profile": "ua1",
        "limits": { "maxBytes": 10485760, "maxPages": 20, "timeoutSeconds": 60 }
      }
      """;

  @Test
  void parsesRepositoryExampleFixRequest() throws IOException {
    WorkerRequest request = WorkerRequestParser.parse(Files.readString(REPO_EXAMPLE));

    assertEquals("1.0", request.specVersion());
    assertEquals(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), request.jobId());
    assertEquals("/work/in/document.pdf", request.inputPath());
    assertEquals("/work/out", request.outputDirectory());
    assertEquals(WorkerRequest.Mode.FIX, request.mode());
    assertEquals("ua1", request.profile());
    assertEquals("en-US", request.explicitLanguage());
    assertEquals(10_485_760L, request.limits().maxBytes());
    assertEquals(20, request.limits().maxPages());
    assertEquals(60, request.limits().timeoutSeconds());
  }

  @Test
  void explicitLanguageDefaultsToNullWhenAbsent() {
    WorkerRequest request = WorkerRequestParser.parse(VALID_ANALYZE_REQUEST);

    assertEquals(WorkerRequest.Mode.ANALYZE, request.mode());
    assertNull(request.explicitLanguage());
  }

  @Test
  void rejectsUnsupportedProfile() {
    String json = VALID_ANALYZE_REQUEST.replace("\"ua1\"", "\"ua2\"");

    RequestValidationException ex =
        assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
    assertTrue(ex.getMessage().contains("profile"), ex.getMessage());
  }

  @Test
  void rejectsUnsupportedMode() {
    String json = VALID_ANALYZE_REQUEST.replace("\"analyze\"", "\"auto_fix\"");

    assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
  }

  @Test
  void theRemovedSafeFixAndStructureFixModesAreRejectedAndFixIsTheOnlyMutatingMode() {
    for (String removed : new String[] {"safe_fix", "structure_fix"}) {
      assertThrows(
          RequestValidationException.class,
          () -> WorkerRequestParser.parse(VALID_ANALYZE_REQUEST.replace("\"analyze\"", "\"" + removed + "\"")),
          removed);
    }
    assertTrue(WorkerRequestParser.parse(VALID_ANALYZE_REQUEST.replace("\"analyze\"", "\"fix\"")).isMutating());
    assertFalse(WorkerRequestParser.parse(VALID_ANALYZE_REQUEST).isMutating());
  }

  @Test
  void acceptsFixModeWithTitleAndFileName() throws Exception {
    String json =
        """
        {"specVersion":"1.0","jobId":"123e4567-e89b-42d3-a456-426614174000","inputPath":"/work/in/document.pdf",
         "outputDirectory":"/work/out","mode":"fix","profile":"ua1","explicitLanguage":"en",
         "explicitTitle":"Annual report 2025","sourceFileName":"annual-report-2025.pdf",
         "limits":{"maxBytes":10485760,"maxPages":20,"timeoutSeconds":60}}""";
    WorkerRequest r = WorkerRequestParser.parse(json);
    assertEquals(WorkerRequest.Mode.FIX, r.mode());
    assertTrue(r.isFix());
    assertTrue(r.isMutating());
    assertTrue(r.addsStructure());
    assertEquals("Annual report 2025", r.explicitTitle());
    assertEquals("annual-report-2025.pdf", r.sourceFileName());
  }

  @Test
  void titleAndFileNameAreOptionalAndMayBeNull() throws Exception {
    String json =
        """
        {"specVersion":"1.0","jobId":"123e4567-e89b-42d3-a456-426614174000","inputPath":"/work/in/document.pdf",
         "outputDirectory":"/work/out","mode":"fix","profile":"ua1","explicitLanguage":null,
         "explicitTitle":null,"sourceFileName":null,
         "limits":{"maxBytes":10485760,"maxPages":20,"timeoutSeconds":60}}""";
    WorkerRequest r = WorkerRequestParser.parse(json);
    assertNull(r.explicitTitle());
    assertNull(r.sourceFileName());
  }

  @Test
  void rejectsOverlongTitle() {
    String title = "x".repeat(501);
    String json =
        """
        {"specVersion":"1.0","jobId":"123e4567-e89b-42d3-a456-426614174000","inputPath":"/work/in/document.pdf",
         "outputDirectory":"/work/out","mode":"fix","profile":"ua1","explicitTitle":"%s",
         "limits":{"maxBytes":10485760,"maxPages":20,"timeoutSeconds":60}}"""
            .formatted(title);
    assertThrows(Exception.class, () -> WorkerRequestParser.parse(json));
  }

  @Test
  void parsesHtmlGeneratedOrigin() {
    WorkerRequest request = WorkerRequestParser.parse(withOrigin("\"html_generated\""));

    assertEquals(WorkerRequest.Origin.HTML_GENERATED, request.origin());
    assertTrue(request.isHtmlGenerated());
  }

  @Test
  void originDefaultsToUploadWhenAbsent() {
    WorkerRequest request = WorkerRequestParser.parse(VALID_ANALYZE_REQUEST);

    assertEquals(WorkerRequest.Origin.UPLOAD, request.origin());
    assertFalse(request.isHtmlGenerated());
  }

  @Test
  void rejectsUnsupportedOrigin() {
    String json = withOrigin("\"other\"");

    RequestValidationException ex =
        assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
    assertTrue(ex.getMessage().contains("origin"), ex.getMessage());
  }

  @Test
  void fixAddsStructureAndAnalyzeDoesNot() {
    assertTrue(ExplicitLanguageRuleTest.request(WorkerRequest.Mode.FIX, null).addsStructure());
    assertFalse(ExplicitLanguageRuleTest.request(WorkerRequest.Mode.ANALYZE, null).addsStructure());
    assertFalse(ExplicitLanguageRuleTest.request(WorkerRequest.Mode.ANALYZE, null).isFix());
  }

  @Test
  void structureRuleIdsValidateAgainstResultSchema() {
    WorkerResult base = sampleResult();
    List<WorkerResult.Action> actions =
        List.of(
            new WorkerResult.Action(WorkerResult.RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1, WorkerResult.ActionOutcome.APPLIED, "tagged 3 paragraphs", null, null),
            new WorkerResult.Action(WorkerResult.RuleId.PDF_XMP_METADATA_FROM_INFO_V1, WorkerResult.ActionOutcome.APPLIED, "wrote packet", null, null));
    WorkerResult result =
        new WorkerResult(
            base.specVersion(), base.jobId(), base.status(), base.originalSha256(), base.engineVersion(), base.baseline(), base.finalValidation(),
            WorkerResult.RemediationStatus.APPLIED, actions, base.findings(), base.artifacts(), true, null);

    Set<String> errors = ContractSchemas.validateResult(WorkerResultCodec.toJson(result));

    assertTrue(errors.isEmpty(), () -> "schema violations: " + errors);
  }

  @Test
  void everyRuleIdIncludingThePdfUaIdentifierValidatesAgainstResultSchema() {
    WorkerResult base = sampleResult();
    List<WorkerResult.Action> actions = java.util.Arrays.stream(WorkerResult.RuleId.values())
        .map(id -> new WorkerResult.Action(id, WorkerResult.ActionOutcome.SKIPPED, "reason", null, null))
        .toList();
    assertTrue(actions.stream().anyMatch(a -> a.ruleId().name().equals("PDF_UA_IDENTIFIER_V1")));
    WorkerResult result =
        new WorkerResult(
            base.specVersion(), base.jobId(), base.status(), base.originalSha256(), base.engineVersion(), base.baseline(), base.finalValidation(),
            WorkerResult.RemediationStatus.APPLIED, actions, base.findings(), base.artifacts(), true, null);

    Set<String> errors = ContractSchemas.validateResult(WorkerResultCodec.toJson(result));

    assertTrue(errors.isEmpty(), () -> "schema violations: " + errors);
  }

  @Test
  void findingResolutionIsPinnedByTheResultSchemaAndRoundTrips() {
    WorkerResult base = sampleResult();
    List<WorkerResult.Finding> findings =
        List.of(
            new WorkerResult.Finding("ISO 14289-1:2014 7.2-30", "fixed by the copy", null, false, WorkerResult.Resolution.FIXED_IN_COPY),
            new WorkerResult.Finding("ISO 14289-1:2014 7.3-1", "still failing in the copy", null, false, WorkerResult.Resolution.STILL_FAILING),
            new WorkerResult.Finding("MANUAL_REVIEW_REQUIRED", "a person must review", null, true));
    WorkerResult result =
        new WorkerResult(
            base.specVersion(), base.jobId(), base.status(), base.originalSha256(), base.engineVersion(), base.baseline(), base.finalValidation(),
            WorkerResult.RemediationStatus.APPLIED, base.actions(), findings, base.artifacts(), true, null);

    String json = WorkerResultCodec.toJson(result);

    assertTrue(json.contains("\"resolution\" : \"fixed_in_copy\""), json);
    assertTrue(json.contains("\"resolution\" : \"still_failing\""), json);
    assertTrue(json.contains("\"resolution\" : null"), "review items serialize an explicit null: " + json);
    assertTrue(ContractSchemas.validateResult(json).isEmpty(), () -> ContractSchemas.validateResult(json).toString());
    assertEquals(result, WorkerResultCodec.fromJson(json));
    assertNull(findings.get(2).resolution());
    assertFalse(ContractSchemas.validateResult(json.replace("\"fixed_in_copy\"", "\"fixed\"")).isEmpty(), "only the two documented values are allowed");
    assertFalse(ContractSchemas.validateResult(json.replace("\"resolution\" : null", "\"resolution\" : true")).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"en_US", "e", "-en", "en-", "english language", "en-US-"})
  void rejectsSyntacticallyInvalidLanguageTags(String tag) {
    String json = withExplicitLanguage("\"" + tag + "\"");

    RequestValidationException ex =
        assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
    assertTrue(ex.getMessage().contains("explicitLanguage"), ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"en", "en-US", "pl-PL", "zh-Hant-TW", "uk", "sr-Latn"})
  void acceptsWellFormedLanguageTags(String tag) {
    WorkerRequest request = WorkerRequestParser.parse(withExplicitLanguage("\"" + tag + "\""));

    assertEquals(tag, request.explicitLanguage());
  }

  @Test
  void acceptsExplicitNullLanguage() {
    WorkerRequest request = WorkerRequestParser.parse(withExplicitLanguage("null"));

    assertNull(request.explicitLanguage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/tmp/../../etc/passwd",
        "/work/in/../../etc/passwd",
        "/work/in/other.pdf",
        "C:\\\\work\\\\in\\\\document.pdf",
        "work/in/document.pdf"
      })
  void rejectsInputPathsOtherThanTheMountedDocument(String path) {
    String json = VALID_ANALYZE_REQUEST.replace("/work/in/document.pdf", path);

    RequestValidationException ex =
        assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
    assertTrue(ex.getMessage().contains("inputPath"), ex.getMessage());
  }

  @Test
  void rejectsOutputDirectoryOutsideJobRoot() {
    String json = VALID_ANALYZE_REQUEST.replace("\"/work/out\"", "\"/work/out/../../tmp\"");

    assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
  }

  @Test
  void rejectsUnknownProperties() {
    String json = VALID_ANALYZE_REQUEST.replace("\"mode\"", "\"password\": \"x\", \"mode\"");

    assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(json));
  }

  @Test
  void rejectsMalformedJson() {
    assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse("{ not json"));
  }

  @Test
  void serializedResultValidatesAgainstResultSchema(@TempDir Path tmp) throws IOException {
    WorkerResult result = sampleResult();
    Path out = tmp.resolve("result.json");

    new ReportWriter().write(result, out);

    String json = Files.readString(out, StandardCharsets.UTF_8);
    Set<String> errors = ContractSchemas.validateResult(json);
    assertTrue(errors.isEmpty(), () -> "schema violations: " + errors);
    assertTrue(json.contains("\"manualReviewRequired\" : true"), json);
  }

  @Test
  void resultSerializationIsDeterministic() {
    WorkerResult result = sampleResult();

    assertEquals(WorkerResultCodec.toJson(result), WorkerResultCodec.toJson(sampleResult()));
  }

  @Test
  void resultSchemaRejectsFalseManualReview() {
    String json = WorkerResultCodec.toJson(sampleResult()).replace("\"manualReviewRequired\" : true", "\"manualReviewRequired\" : false");

    assertTrue(json.contains("false"));
    assertTrue(!ContractSchemas.validateResult(json).isEmpty());
  }

  @Test
  void reportWriterDoesNotLeaveTemporaryFilesBehind(@TempDir Path tmp) throws IOException {
    Path out = tmp.resolve("result.json");

    new ReportWriter().write(sampleResult(), out);

    try (var files = Files.list(tmp)) {
      assertEquals(List.of(out), files.toList());
    }
  }

  private static String withExplicitLanguage(String jsonValue) {
    return VALID_ANALYZE_REQUEST.replace(
        "\"profile\": \"ua1\",", "\"profile\": \"ua1\", \"explicitLanguage\": " + jsonValue + ",");
  }

  private static String withOrigin(String jsonValue) {
    return VALID_ANALYZE_REQUEST.replace(
        "\"profile\": \"ua1\",", "\"profile\": \"ua1\", \"origin\": " + jsonValue + ",");
  }

  static WorkerResult sampleResult() {
    return new WorkerResult(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerResult.Status.COMPLETED,
        "a".repeat(64),
        "pdf-engine 0.1.0-test",
        new WorkerResult.Validation(
            "ua1",
            WorkerResult.ValidationStatus.FAILED,
            List.of(new WorkerResult.FailedRule("ISO 14289-1:2012 7.1", 3)),
            "/work/out/baseline-report.json"),
        null,
        WorkerResult.RemediationStatus.NO_APPLICABLE_FIX,
        List.of(
            new WorkerResult.Action(
                WorkerResult.RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1,
                WorkerResult.ActionOutcome.SKIPPED,
                "catalog already declares a language",
                "pl-PL",
                null)),
        List.of(new WorkerResult.Finding("ISO 14289-1:2012 7.1", "document is not tagged", null, true)),
        List.of(
            new WorkerResult.Artifact(
                WorkerResult.ArtifactKind.ORIGINAL, "/work/in/document.pdf", "a".repeat(64), 1234),
            new WorkerResult.Artifact(
                WorkerResult.ArtifactKind.BASELINE_REPORT,
                "/work/out/baseline-report.json",
                "b".repeat(64),
                99)),
        true,
        null);
  }
}
