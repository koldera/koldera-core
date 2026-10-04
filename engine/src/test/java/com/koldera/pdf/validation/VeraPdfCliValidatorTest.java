package com.koldera.pdf.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VeraPdfCliValidatorTest {

  private static final List<String> COMMAND = List.of("verapdf");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @TempDir Path tmp;

  /** Shape observed from veraPDF 1.30.2 `--flavour ua1 --format json` on an untagged fixture. */
  private static String report(boolean passed, String ruleSummaries) {
    return """
        {"report":{"buildInformation":{"releaseDetails":[{"id":"core","version":"1.30.2","buildDate":1}]},
        "jobs":[{"itemDetails":{"name":"x.pdf","size":941},
        "validationResult":[{"details":{"passedRules":99,"failedRules":2,"passedChecks":119,"failedChecks":4,
        "ruleSummaries":[%s]},
        "jobEndStatus":"normal","profileName":"PDF/UA-1 validation profile",
        "statement":"irrelevant","%s":%s}],"processingTime":{"start":1,"finish":2}}],
        "batchSummary":{"totalJobs":1,"outOfMemory":0,"veraExceptions":0,"failedParsingJobs":0,
        "validationSummary":{"totalJobCount":1,"failedJobCount":0,"successfulJobCount":1}}}}
        """
        .formatted(ruleSummaries, VeraPdfCliValidator.PASS_FLAG_FIELD, passed);
  }

  private static final String TWO_FAILED_RULES =
      """
      {"ruleStatus":"FAILED","specification":"ISO 14289-1:2014","clause":"7.1","testNumber":8,"status":"failed","failedChecks":1,"description":"Metadata stream required","checks":[]},
      {"ruleStatus":"FAILED","specification":"ISO 14289-1:2014","clause":"7.21.4.1","testNumber":1,"status":"failed","failedChecks":3,"checks":[]}
      """;

  @Test
  void invokesExplicitUa1JsonWithoutShellAndParsesFailedRules() throws IOException {
    FakeRunner runner = FakeRunner.writing(1, report(false, TWO_FAILED_RULES));
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, runner);
    Path pdf = tmp.resolve("candidate.pdf");
    Files.write(pdf, new byte[] {1});
    Path reportPath = tmp.resolve("report.json");

    ValidationOutcome outcome = validator.validate(pdf, reportPath, TIMEOUT);

    assertEquals(
        List.of("verapdf", "--flavour", "ua1", "--format", "json", pdf.toString()), runner.command);
    assertEquals(TIMEOUT, runner.timeout);
    assertEquals(ValidationStatus.FAILED, outcome.status());
    assertEquals(
        Map.of("ISO 14289-1:2014 7.1-8", 1, "ISO 14289-1:2014 7.21.4.1-1", 3),
        outcome.failedRuleCounts());
    assertEquals(Map.of("ISO 14289-1:2014 7.1-8", "Metadata stream required"), outcome.ruleDescriptions());
    assertEquals(reportPath, outcome.rawReport());
    assertTrue(Files.exists(reportPath), "raw report persisted");
    assertNull(outcome.errorCode());
    assertEquals("1.30.2", outcome.validatorVersion());
  }

  @Test
  void compliantReportWithNoFailedRulesIsPassed() throws IOException {
    VeraPdfCliValidator validator =
        new VeraPdfCliValidator(COMMAND, FakeRunner.writing(0, report(true, "")));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.PASSED, outcome.status());
    assertTrue(outcome.failedRuleCounts().isEmpty());
  }

  @Test
  void compliantFlagWithFailedRulesIsTreatedAsValidatorErrorNotPass() throws IOException {
    VeraPdfCliValidator validator =
        new VeraPdfCliValidator(COMMAND, FakeRunner.writing(0, report(true, TWO_FAILED_RULES)));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, outcome.errorCode());
  }

  @Test
  void missingBinaryIsValidatorUnavailable() throws IOException {
    VeraPdfCliValidator validator =
        new VeraPdfCliValidator(COMMAND, FakeRunner.throwing(new IOException("not found")));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_UNAVAILABLE, outcome.errorCode());
    assertTrue(outcome.failedRuleCounts().isEmpty());
    assertNull(outcome.rawReport());
  }

  @Test
  void timeoutIsValidatorError() throws IOException {
    VeraPdfCliValidator validator =
        new VeraPdfCliValidator(COMMAND, FakeRunner.throwing(new TimeoutException("slow")));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, outcome.errorCode());
    assertTrue(outcome.errorMessage().toLowerCase().contains("timeout"), outcome.errorMessage());
  }

  @Test
  void malformedJsonIsValidatorError() throws IOException {
    VeraPdfCliValidator validator =
        new VeraPdfCliValidator(COMMAND, FakeRunner.writing(0, "{\"report\": {\"jobs\": [ oops"));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, outcome.errorCode());
    assertNotNull(outcome.rawReport(), "even a malformed report is kept for diagnosis");
  }

  @Test
  void emptyOutputWithNonZeroExitIsValidatorError() throws IOException {
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, FakeRunner.writing(4, ""));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, outcome.errorCode());
  }

  @Test
  void parseFailureTaskExceptionIsValidatorErrorNotFailedValidation() throws IOException {
    String json =
        """
        {"report":{"buildInformation":{"releaseDetails":[{"id":"core","version":"1.30.2"}]},
        "jobs":[{"itemDetails":{"name":"bad.pdf","size":16},
        "taskException":{"exception":"Couldn't parse stream","type":"PARSE","executed":true,"success":false}}],
        "batchSummary":{"totalJobs":1,"failedParsingJobs":1}}}
        """;
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, FakeRunner.writing(7, json));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertEquals(ErrorCode.VALIDATOR_ERROR, outcome.errorCode());
    assertTrue(outcome.errorMessage().contains("PARSE"), outcome.errorMessage());
  }

  @Test
  void wrongProfileInReportIsValidatorError() throws IOException {
    String json = report(false, TWO_FAILED_RULES).replace("PDF/UA-1 validation profile", "PDF/A-1B validation profile");
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, FakeRunner.writing(1, json));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertTrue(outcome.errorMessage().contains("profile"), outcome.errorMessage());
  }

  @Test
  void abnormalJobEndStatusIsValidatorError() throws IOException {
    String json = report(false, TWO_FAILED_RULES).replace("\"jobEndStatus\":\"normal\"", "\"jobEndStatus\":\"timeout\"");
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, FakeRunner.writing(1, json));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
  }

  @Test
  void multipleOrZeroJobsIsValidatorError() throws IOException {
    String zero = report(false, "").replace("\"jobs\":[{", "\"jobs\":[],\"ignored\":[{");
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, FakeRunner.writing(0, zero));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
    assertFalse(outcome.passed());
  }

  @Test
  void ruleWithoutFailedChecksCountIsRejected() throws IOException {
    String rule = "{\"ruleStatus\":\"FAILED\",\"specification\":\"ISO 14289-1:2014\",\"clause\":\"7.1\",\"testNumber\":8,\"status\":\"failed\"}";
    VeraPdfCliValidator validator = new VeraPdfCliValidator(COMMAND, FakeRunner.writing(1, report(false, rule)));

    ValidationOutcome outcome = validator.validate(pdf(), tmp.resolve("r.json"), TIMEOUT);

    assertEquals(ValidationStatus.ERROR, outcome.status());
  }

  private Path pdf() throws IOException {
    Path pdf = tmp.resolve("candidate.pdf");
    Files.write(pdf, new byte[] {1});
    return pdf;
  }

  /** Records the command and writes canned stdout, or throws, without starting a process. */
  private static final class FakeRunner implements ProcessRunner {
    List<String> command;
    Duration timeout;
    private final int exitCode;
    private final String stdout;
    private final Exception failure;

    private FakeRunner(int exitCode, String stdout, Exception failure) {
      this.exitCode = exitCode;
      this.stdout = stdout;
      this.failure = failure;
    }

    static FakeRunner writing(int exitCode, String stdout) {
      return new FakeRunner(exitCode, stdout, null);
    }

    static FakeRunner throwing(Exception failure) {
      return new FakeRunner(-1, null, failure);
    }

    @Override
    public int run(List<String> command, Path stdoutFile, Duration timeout)
        throws IOException, TimeoutException {
      this.command = List.copyOf(command);
      this.timeout = timeout;
      if (failure instanceof IOException io) {
        throw io;
      }
      if (failure instanceof TimeoutException te) {
        throw te;
      }
      Files.writeString(stdoutFile, stdout, StandardCharsets.UTF_8);
      return exitCode;
    }
  }
}
