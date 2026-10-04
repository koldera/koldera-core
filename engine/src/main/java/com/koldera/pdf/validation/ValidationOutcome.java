package com.koldera.pdf.validation;

import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Normalized validator result: {@code status} is passed or failed only when the validator demonstrably ran the explicit profile,
 * else error (never defaulted to passed); failed check counts per rule id and descriptions; the persisted raw report; and,
 * on error, a typed code and content-free message; the validator version when parseable.
 */
public record ValidationOutcome(
    ValidationStatus status,
    Map<String, Integer> failedRuleCounts,
    Map<String, String> ruleDescriptions,
    Path rawReport,
    ErrorCode errorCode,
    String errorMessage,
    String validatorVersion) {

  public ValidationOutcome {
    // Sorted, unmodifiable, and order-preserving (Map.copyOf would randomise iteration order).
    failedRuleCounts = Collections.unmodifiableMap(new TreeMap<>(failedRuleCounts));
    ruleDescriptions = Collections.unmodifiableMap(new TreeMap<>(ruleDescriptions));
  }

  public boolean passed() {
    return status == ValidationStatus.PASSED;
  }

  public boolean isError() {
    return status == ValidationStatus.ERROR;
  }

  public static ValidationOutcome error(ErrorCode code, String message, Path rawReport) {
    return new ValidationOutcome(ValidationStatus.ERROR, Map.of(), Map.of(), rawReport, code, message, null);
  }

  /** Converts to the contract shape, with the raw report path expressed canonically by the caller. */
  public WorkerResult.Validation toContract(String canonicalReportPath) {
    List<WorkerResult.FailedRule> rules =
        new TreeMap<>(failedRuleCounts).entrySet().stream()
            .map(e -> new WorkerResult.FailedRule(e.getKey(), e.getValue()))
            .toList();
    return new WorkerResult.Validation("ua1", status, rules, canonicalReportPath);
  }
}
