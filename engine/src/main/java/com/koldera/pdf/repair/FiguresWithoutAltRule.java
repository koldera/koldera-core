package com.koldera.pdf.repair;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import java.io.IOException;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * {@code PDF_FIGURES_WITHOUT_ALT_V1} (mode {@code fix}, uploads): every {@code Figure} without alternative text is marked as
 * decoration ({@link FigureArtifacts}); one whose content shows text becomes a paragraph. Alternative text is never
 * generated. It runs after the structure rules and is one of {@code PdfProcessor.STRUCTURE_STEPS}.
 */
public final class FiguresWithoutAltRule implements RemediationRule {

  static final String NOTHING = "no Figure without alternative text";
  private static final String PAGES = "; pages: ";

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_FIGURES_WITHOUT_ALT_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("figures are only marked as artifacts in fix for uploaded PDFs", null, null);
    }
    return RuleDecision.apply(
        "Figures without alternative text are checked after this run's structure steps", null, "no Figure without alternative text in the structure tree");
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    FigureArtifacts.Outcome outcome;
    try {
      outcome = FigureArtifacts.apply(document);
    } catch (FigureArtifacts.Refused e) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, "figures are not marked as artifacts: " + e.getMessage(), null, null);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not mark the figures as artifacts (" + e.getClass().getSimpleName() + ")", null, null);
    }
    if (outcome.withoutAlt() == 0) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, NOTHING, null, null);
    }
    String counts = "figures without alternative text marked as artifact " + outcome.artifacted() + ", retagged as paragraph " + outcome.retaggedAsParagraph()
        + " (they show text), kept with alternative text " + outcome.keptWithAlt() + ", kept " + (outcome.keptWithAnnotation() + outcome.keptOther())
        + " (they hold an annotation or other tagged content)";
    if (outcome.artifacted() + outcome.retaggedAsParagraph() == 0) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, counts, null, null);
    }
    String pages = outcome.artifactedPerPage().isEmpty()
        ? "none"
        : outcome.artifactedPerPage().entrySet().stream().map(e -> e.getKey() + "×" + e.getValue()).collect(Collectors.joining(", "));
    int without = outcome.withoutAlt();
    return new WorkerResult.Action(
        id(), ActionOutcome.APPLIED, counts + PAGES + pages, without + (without == 1 ? " Figure" : " Figures") + " without alternative text",
        outcome.artifacted() + " marked as artifact, " + outcome.retaggedAsParagraph() + " retagged as paragraph");
  }

  /**
   * The figures marked as artifact per page, parsed from an applied action of this rule's reason;
   * empty for any other action. The map may be empty (only paragraphs were retagged).
   */
  public static Optional<SortedMap<Integer, Integer>> artifactedPerPage(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_FIGURES_WITHOUT_ALT_V1 || action.outcome() != ActionOutcome.APPLIED || action.reason() == null) {
      return Optional.empty();
    }
    int at = action.reason().lastIndexOf(PAGES);
    if (at < 0) {
      return Optional.empty();
    }
    String list = action.reason().substring(at + PAGES.length());
    SortedMap<Integer, Integer> out = new TreeMap<>();
    if (list.equals("none")) {
      return Optional.of(out);
    }
    for (String entry : list.split(", ")) {
      String[] parts = entry.split("×");
      if (parts.length != 2) {
        return Optional.empty();
      }
      try {
        out.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    return Optional.of(out);
  }
}
