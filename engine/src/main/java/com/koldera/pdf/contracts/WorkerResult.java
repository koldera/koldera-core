package com.koldera.pdf.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * Worker output, mirroring {@code contracts/pdf-worker-result.schema.json}.
 * {@code status=completed} means processing completed, never that the document is accessible or PDF/UA
 * conformant. {@code manualReviewRequired} is always {@code true}; the result schema pins it.
 */
public record WorkerResult(
    String specVersion,
    UUID jobId,
    Status status,
    String originalSha256,
    String engineVersion,
    Validation baseline,
    Validation finalValidation,
    RemediationStatus remediationStatus,
    List<Action> actions,
    List<Finding> findings,
    List<Artifact> artifacts,
    boolean manualReviewRequired,
    WorkerError error) {

  public static final String SPEC_VERSION = "1.0";

  public WorkerResult {
    actions = List.copyOf(actions);
    findings = List.copyOf(findings);
    artifacts = List.copyOf(artifacts);
  }

  public enum Status {
    @JsonProperty("completed")
    COMPLETED,
    @JsonProperty("unsupported")
    UNSUPPORTED,
    @JsonProperty("failed")
    FAILED
  }

  public enum ValidationStatus {
    @JsonProperty("passed")
    PASSED,
    @JsonProperty("failed")
    FAILED,
    @JsonProperty("error")
    ERROR,
    @JsonProperty("not_run")
    NOT_RUN
  }

  public enum RemediationStatus {
    @JsonProperty("not_requested")
    NOT_REQUESTED,
    @JsonProperty("no_applicable_fix")
    NO_APPLICABLE_FIX,
    @JsonProperty("applied")
    APPLIED,
    @JsonProperty("skipped")
    SKIPPED,
    @JsonProperty("rolled_back")
    ROLLED_BACK
  }

  /**
   * The rule identifiers permitted to mutate a document, per mode: metadata rules ({@code safe_fix}), basic structure
   * and XMP rules ({@code structure_fix}), and in {@code fix} the title, generated-structure, upload-repair,
   * structure-strategy, figure, font and PDF/UA identifier steps.
   */
  public enum RuleId {
    PDF_LANG_FROM_EXPLICIT_INPUT_V1,
    PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1,
    PDF_BASIC_STRUCTURE_FROM_CONTENT_V1,
    PDF_XMP_METADATA_FROM_INFO_V1,
    PDF_TITLE_V1,
    PDF_GENERATED_STRUCTURE_V1,
    PDF_UA_IDENTIFIER_V1,
    PDF_ROLE_MAP_V1,
    PDF_TAB_ORDER_V1,
    PDF_LANG_CORRECTED_FROM_TEXT_V1,
    PDF_LINK_TAGS_V1,
    PDF_STRUCTURE_INFERRED_V1,
    PDF_FIGURES_WITHOUT_ALT_V1,
    PDF_UNTAGGED_CONTENT_V1,
    PDF_TAGGED_STRUCTURE_REPAIR_V1,
    PDF_FONT_TOUNICODE_V1,
    PDF_FONT_DICTIONARY_V1,
    PDF_FONT_INVISIBLE_TEXT_V1,
    PDF_FONT_EMBEDDING_V1
  }

  public enum ActionOutcome {
    @JsonProperty("applied")
    APPLIED,
    @JsonProperty("skipped")
    SKIPPED,
    @JsonProperty("failed")
    FAILED,
    @JsonProperty("rolled_back")
    ROLLED_BACK
  }

  public enum ArtifactKind {
    @JsonProperty("original")
    ORIGINAL,
    @JsonProperty("remediated")
    REMEDIATED,
    @JsonProperty("baseline_report")
    BASELINE_REPORT,
    @JsonProperty("final_report")
    FINAL_REPORT
  }

  public enum ErrorCode {
    INVALID_INPUT,
    UNSUPPORTED_ENCRYPTED,
    UNSUPPORTED_SIGNED_MUTATION,
    LIMIT_EXCEEDED,
    VALIDATOR_UNAVAILABLE,
    VALIDATOR_ERROR,
    INTEGRITY_REGRESSION,
    WORKER_ERROR
  }

  public record Validation(
      String profile, ValidationStatus status, List<FailedRule> failedRules, String rawReportPath) {
    public Validation {
      failedRules = List.copyOf(failedRules);
    }

    /** A validation that never ran; used when preflight refuses the document. */
    public static Validation notRun() {
      return new Validation("ua1", ValidationStatus.NOT_RUN, List.of(), null);
    }
  }

  public record FailedRule(String ruleId, int count) {}

  public record Action(RuleId ruleId, ActionOutcome outcome, String reason, String before, String after) {}

  /**
   * What the copy's final validation says about a machine-check finding of a job whose remediation
   * was applied. Absent ({@code null}) on analyze runs, rolled-back runs and every finding that is not
   * a validator rule (review items, informational notes).
   */
  public enum Resolution {
    @JsonProperty("fixed_in_copy")
    FIXED_IN_COPY,
    @JsonProperty("still_failing")
    STILL_FAILING
  }

  public record Finding(String ruleId, String message, Integer pageNumber, boolean needsHumanReview, Resolution resolution) {
    /** A finding without a resolution: the default for everything but a machine check of an applied fix. */
    public Finding(String ruleId, String message, Integer pageNumber, boolean needsHumanReview) {
      this(ruleId, message, pageNumber, needsHumanReview, null);
    }

    public Finding withResolution(Resolution value) {
      return new Finding(ruleId, message, pageNumber, needsHumanReview, value);
    }
  }

  public record Artifact(ArtifactKind kind, String path, String sha256, long bytes) {}

  public record WorkerError(ErrorCode code, String message) {}
}
