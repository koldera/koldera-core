package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * One deterministic, narrowly scoped document fix. A rule inspects first (pure, no mutation) and
 * only mutates when {@link #apply} is handed an applicable decision. Rules never touch storage,
 * validators, billing or the original file; the pipeline calls them on a scratch copy only.
 */
public interface RemediationRule {

  WorkerResult.RuleId id();

  /**
   * Whether the rule takes part in this request at all. A rule that does not participate is neither
   * inspected nor listed in the result, so a mode's action list is exactly the rules that belong to
   * it (in {@code fix}, the metadata, structure, repair, font and identifier rules). Nothing participates in {@code analyze}.
   */
  default boolean participates(WorkerRequest request) {
    return request.isMutating();
  }

  /**
   * As {@link #participates(WorkerRequest)}, for a run that leaves the rules in {@code excluded} out
   * (the pipeline's rollback ladder). A rule may take part only in such a run (the basic tagger in a
   * {@code fix} upload run without the structure steps); the pipeline calls only this form.
   */
  default boolean participates(WorkerRequest request, Set<WorkerResult.RuleId> excluded) {
    return participates(request) && !excluded.contains(id());
  }

  /** Decides whether the rule's preconditions hold; must not modify {@code document}. */
  RuleDecision inspect(PDDocument document, WorkerRequest request);

  /** As {@link #inspect(PDDocument, WorkerRequest)} in a run that leaves the rules in {@code excluded} out; the pipeline calls only this form. */
  default RuleDecision inspect(PDDocument document, WorkerRequest request, Set<WorkerResult.RuleId> excluded) {
    return inspect(document, request);
  }

  /**
   * Applies the fix described by {@code decision} to {@code document} and reports what happened.
   * A non-applicable decision yields a {@code skipped} action and no mutation.
   */
  WorkerResult.Action apply(PDDocument document, RuleDecision decision);
}
