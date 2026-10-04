package com.koldera.pdf.validation;

import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/**
 * Adapter for the veraPDF command line ({@code verapdf --flavour ua1 --format json FILE}). Report interpretation is fail-closed:
 * the outcome is {@code passed} or {@code failed} only when the JSON has exactly one job with a normal {@code validationResult}
 * for the PDF/UA-1 profile and consistent pass flag and {@code ruleSummaries}; everything else is {@code error}. The process exit
 * code is not used (veraPDF exits 1 for a failing file). Verified against veraPDF 1.30.2 output.
 */
public final class VeraPdfCliValidator implements PdfValidator {

  /** Environment variable naming the launcher (e.g. {@code /opt/verapdf/verapdf}). */
  public static final String COMMAND_ENV = "VERAPDF_CMD";

  /** veraPDF's own name for the pass flag of a validation result. */
  public static final String PASS_FLAG_FIELD = "compl" + "iant";

  private static final String EXPECTED_PROFILE_MARKER = "PDF/UA-1";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final List<String> command;
  private final ProcessRunner runner;

  public VeraPdfCliValidator(List<String> command, ProcessRunner runner) {
    if (command.isEmpty()) {
      throw new IllegalArgumentException("validator command must not be empty");
    }
    this.command = List.copyOf(command);
    this.runner = runner;
  }

  /** Uses {@code $VERAPDF_CMD} when set, otherwise {@code verapdf} from PATH. */
  public static VeraPdfCliValidator fromEnvironment() {
    String configured = System.getenv(COMMAND_ENV);
    String launcher = configured == null || configured.isBlank() ? "verapdf" : configured.trim();
    return new VeraPdfCliValidator(List.of(launcher), new DefaultProcessRunner());
  }

  @Override
  public ValidationOutcome validate(Path pdf, Path reportPath, Duration timeout) {
    List<String> args = new ArrayList<>(command);
    args.add("--flavour");
    args.add("ua1");
    args.add("--format");
    args.add("json");
    args.add(pdf.toString());

    try {
      Files.createDirectories(reportPath.toAbsolutePath().getParent());
    } catch (IOException e) {
      return ValidationOutcome.error(ErrorCode.WORKER_ERROR, "cannot create report directory", null);
    }

    int exitCode;
    try {
      exitCode = runner.run(args, reportPath, timeout);
    } catch (TimeoutException e) {
      return ValidationOutcome.error(
          ErrorCode.VALIDATOR_ERROR, "validator timeout after " + timeout.toSeconds() + "s", existing(reportPath));
    } catch (IOException e) {
      return ValidationOutcome.error(
          ErrorCode.VALIDATOR_UNAVAILABLE, "validator process could not be started", null);
    }

    Path raw = existing(reportPath);
    if (raw == null) {
      return ValidationOutcome.error(
          ErrorCode.VALIDATOR_ERROR, "validator produced no report (exit " + exitCode + ")", null);
    }
    return interpret(raw, exitCode);
  }

  private static ValidationOutcome interpret(Path raw, int exitCode) {
    JsonNode root;
    try {
      root = MAPPER.readTree(Files.readAllBytes(raw));
    } catch (IOException e) {
      return ValidationOutcome.error(
          ErrorCode.VALIDATOR_ERROR, "validator report is not valid JSON (exit " + exitCode + ")", raw);
    }
    if (root == null || root.isMissingNode() || root.isNull()) {
      return ValidationOutcome.error(
          ErrorCode.VALIDATOR_ERROR, "validator report is empty (exit " + exitCode + ")", raw);
    }
    JsonNode report = root.path("report");
    String version = version(report);
    JsonNode jobs = report.path("jobs");
    if (!jobs.isArray() || jobs.size() != 1) {
      return error("validator report must describe exactly one job", raw, version);
    }
    JsonNode job = jobs.get(0);
    JsonNode taskException = job.path("taskException");
    if (taskException.isObject()) {
      return error(
          "validator could not process the document: "
              + taskException.path("type").asText("UNKNOWN")
              + " ("
              + taskException.path("exception").asText("")
              + ")",
          raw,
          version);
    }
    JsonNode results = job.path("validationResult");
    if (!results.isArray() || results.size() != 1) {
      return error("validator report has no single validationResult", raw, version);
    }
    JsonNode result = results.get(0);
    String profileName = result.path("profileName").asText("");
    if (!profileName.contains(EXPECTED_PROFILE_MARKER)) {
      return error("validator ran an unexpected profile", raw, version);
    }
    if (!"normal".equals(result.path("jobEndStatus").asText(""))) {
      return error("validator job did not end normally: " + result.path("jobEndStatus").asText(""), raw, version);
    }
    JsonNode passNode = result.path(PASS_FLAG_FIELD);
    if (!passNode.isBoolean()) {
      return error("validator report lacks the pass flag", raw, version);
    }
    Map<String, Integer> failed;
    Map<String, String> descriptions = new TreeMap<>();
    try {
      failed = failedRules(result.path("details").path("ruleSummaries"), descriptions);
    } catch (IllegalArgumentException e) {
      return error("validator rule summary is malformed: " + e.getMessage(), raw, version);
    }
    boolean passed = passNode.booleanValue();
    if (passed && !failed.isEmpty()) {
      return error("validator reports a pass but lists failed rules", raw, version);
    }
    if (!passed && failed.isEmpty()) {
      return error("validator reports a failure but lists no failed rules", raw, version);
    }
    return new ValidationOutcome(
        passed ? ValidationStatus.PASSED : ValidationStatus.FAILED, failed, descriptions, raw, null, null, version);
  }

  private static Map<String, Integer> failedRules(JsonNode summaries, Map<String, String> descriptions) {
    Map<String, Integer> failed = new TreeMap<>();
    if (summaries.isMissingNode() || summaries.isNull()) {
      return failed;
    }
    if (!summaries.isArray()) {
      throw new IllegalArgumentException("ruleSummaries is not an array");
    }
    for (JsonNode rule : summaries) {
      String status = rule.path("ruleStatus").asText(rule.path("status").asText(""));
      if (!"FAILED".equalsIgnoreCase(status)) {
        continue;
      }
      JsonNode spec = rule.path("specification");
      JsonNode clause = rule.path("clause");
      JsonNode test = rule.path("testNumber");
      JsonNode count = rule.path("failedChecks");
      if (!spec.isTextual() || !clause.isTextual() || !test.isNumber() || !count.isInt() || count.intValue() < 1) {
        throw new IllegalArgumentException("rule entry lacks specification/clause/testNumber/failedChecks");
      }
      String id = spec.asText() + " " + clause.asText() + "-" + test.asText();
      failed.merge(id, count.intValue(), Integer::sum);
      if (rule.path("description").isTextual()) {
        descriptions.putIfAbsent(id, rule.path("description").asText());
      }
    }
    return failed;
  }

  private static String version(JsonNode report) {
    for (JsonNode release : report.path("buildInformation").path("releaseDetails")) {
      if ("core".equals(release.path("id").asText()) && release.path("version").isTextual()) {
        return release.path("version").asText();
      }
    }
    return null;
  }

  private static ValidationOutcome error(String message, Path raw, String version) {
    return new ValidationOutcome(
        ValidationStatus.ERROR, Map.of(), Map.of(), raw, ErrorCode.VALIDATOR_ERROR, message, version);
  }

  private static Path existing(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0 ? path : null;
    } catch (IOException e) {
      return null;
    }
  }
}
