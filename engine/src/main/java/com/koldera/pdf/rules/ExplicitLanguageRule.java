package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;

/**
 * {@code PDF_LANG_FROM_EXPLICIT_INPUT_V1}: sets the catalog {@code /Lang} to the caller-supplied, syntax-validated BCP 47
 * tag, only in a mutating mode and only when the catalog has no {@code /Lang}. The language is never inferred from content,
 * an existing value is never overwritten, and whether the tag matches the text remains a human review item.
 */
public final class ExplicitLanguageRule implements RemediationRule {

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_LANG_FROM_EXPLICIT_INPUT_V1;
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    String existing = document.getDocumentCatalog().getLanguage();
    String requested = request.explicitLanguage();
    if (!request.isMutating()) {
      return RuleDecision.skip("analyze mode: no changes are made", existing, null);
    }
    if (existing != null) {
      if (existing.equals(requested)) {
        return RuleDecision.skip(
            "catalog already declares language '" + existing + "'; nothing to change", existing, null);
      }
      return RuleDecision.skip(
          "catalog already declares language '"
              + existing
              + "'; requested '"
              + requested
              + "' is not applied because existing values are never overwritten",
          existing,
          null);
    }
    if (requested == null) {
      return RuleDecision.skip(
          "catalog has no /Lang and no explicit language was supplied; the language is never guessed",
          null,
          null);
    }
    return RuleDecision.apply(
        "catalog has no /Lang; explicit, syntax-validated tag supplied by the caller", null, requested);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    if (catalog.getLanguage() != null) {
      return new WorkerResult.Action(
          id(), ActionOutcome.FAILED, "catalog gained a /Lang between inspect and apply", catalog.getLanguage(), null);
    }
    catalog.setLanguage(decision.after());
    if (!decision.after().equals(catalog.getLanguage())) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "catalog /Lang did not persist", null, decision.after());
    }
    return new WorkerResult.Action(
        id(),
        ActionOutcome.APPLIED,
        "set catalog /Lang from explicit input; the tag is syntactically valid but whether it matches the"
            + " document text requires human review",
        null,
        decision.after());
  }
}
