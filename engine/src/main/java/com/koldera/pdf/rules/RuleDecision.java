package com.koldera.pdf.rules;

/**
 * Outcome of {@link RemediationRule#inspect}: whether the fix may run ({@code applicable}), a content-free {@code reason} for
 * the result JSON, and the observed {@code before} and would-be {@code after} values (null when absent).
 */
public record RuleDecision(boolean applicable, String reason, String before, String after) {

  public static RuleDecision apply(String reason, String before, String after) {
    return new RuleDecision(true, reason, before, after);
  }

  public static RuleDecision skip(String reason, String before, String after) {
    return new RuleDecision(false, reason, before, after);
  }
}
