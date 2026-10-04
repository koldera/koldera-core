package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.structure.ContentTagger;
import com.koldera.pdf.structure.ContentTagger.Plan;
import com.koldera.pdf.structure.ContentTagger.Summary;
import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.IOException;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;

/**
 * {@code PDF_BASIC_STRUCTURE_FROM_CONTENT_V1}: in modes {@code fix} and {@code structure_fix}, gives a document without a
 * {@code StructTreeRoot} basic tags derived from content-stream order (paragraphs, figures without alternative text,
 * artifacts). Tagged documents are skipped. {@link #inspect} analyses read-only, so content the tagger cannot wrap without
 * guessing is a {@code skipped} decision. Mode {@code fix} also drops painting operators that have no path (verified by rendering).
 */
public final class BasicStructureRule implements RemediationRule {

  private PDDocument analyzedDocument;
  private Plan analyzedPlan;
  /** Set by {@link #inspect}: only mode {@code fix} removes (or refuses) painting operators with no path. */
  private boolean removeStrayPaints = true;
  private final java.util.function.Consumer<PDDocument> scratchHookForTests;

  public BasicStructureRule() {
    this(null);
  }

  BasicStructureRule(java.util.function.Consumer<PDDocument> scratchHookForTests) {
    this.scratchHookForTests = scratchHookForTests;
  }

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1;
  }

  /** Structure rules exist only in {@code fix} and {@code structure_fix}; {@code safe_fix} results never list them. */
  @Override
  public boolean participates(WorkerRequest request) {
    return request.addsStructure();
  }

  /**
   * In a {@code fix} upload run the per-upload strategy ({@code PDF_STRUCTURE_INFERRED_V1}) replaces this
   * rule; it takes part only in the rollback ladder's run without the structure steps, where it tags
   * the upload exactly as before the structure steps. Every other mode and origin: as {@link #participates(WorkerRequest)}.
   */
  @Override
  public boolean participates(WorkerRequest request, Set<WorkerResult.RuleId> excluded) {
    if (request.isFix() && !request.isHtmlGenerated()) {
      return excluded.contains(WorkerResult.RuleId.PDF_STRUCTURE_INFERRED_V1) && participates(request) && !excluded.contains(id());
    }
    return participates(request) && !excluded.contains(id());
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    String before = catalog.getStructureTreeRoot() != null ? "StructTreeRoot present" : "untagged";
    if (!request.addsStructure()) {
      return RuleDecision.skip("structure tags are only added in fix and structure_fix modes", before, null);
    }
    if (catalog.getStructureTreeRoot() != null) {
      return RuleDecision.skip("document is already tagged; existing structure is never replaced (use analyze or safe_fix)", before, null);
    }
    boolean removeStray = request.isFix();
    if (removeStray != removeStrayPaints) {
      analyzedDocument = null;
      analyzedPlan = null;
      removeStrayPaints = removeStray;
    }
    Plan plan;
    try {
      plan = plan(document);
    } catch (UnsupportedContentException e) {
      return RuleDecision.skip("content is not tagged automatically: " + e.getMessage(), before, null);
    } catch (IOException | RuntimeException e) {
      return RuleDecision.skip("content streams could not be analysed (" + e.getClass().getSimpleName() + "); nothing is guessed", before, null);
    }
    Summary summary = plan.summary();
    if (summary.strayPaints() > 0) {
      String changed = strayRemovalChanges(document, plan);
      if (changed != null) {
        analyzedDocument = null;
        analyzedPlan = null;
        return RuleDecision.skip(
            "content is not tagged automatically: the content has " + summary.strayPaints() + " painting operators that have no path"
                + " (ISO 32000-2 Figure 9), and removing them would change " + changed + "; nothing is tagged",
            before,
            null);
      }
    }
    return RuleDecision.apply(
        "no StructTreeRoot; content-stream order yields " + summary.paragraphs() + " paragraphs, " + summary.figures() + " figures, " + summary.artifacts() + " artifacts",
        before,
        "Document > P/Figure in content order, artifacts marked");
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    if (document.getDocumentCatalog().getStructureTreeRoot() != null) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "document gained a StructTreeRoot between inspect and apply", decision.before(), null);
    }
    try {
      Summary summary = ContentTagger.apply(document, plan(document));
      if (document.getDocumentCatalog().getStructureTreeRoot() == null) {
        return new WorkerResult.Action(id(), ActionOutcome.FAILED, "structure tree did not persist", decision.before(), decision.after());
      }
      return new WorkerResult.Action(id(), ActionOutcome.APPLIED, summary.describe(), decision.before(), decision.after());
    } catch (UnsupportedContentException | IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not rewrite content streams (" + e.getClass().getSimpleName() + ")", decision.before(), decision.after());
    } finally {
      analyzedDocument = null;
      analyzedPlan = null;
    }
  }

  /**
   * Painting with no current path paints nothing, so dropping it must not change what a reader sees:
   * the tagging is applied to a scratch copy (written and re-read, the document itself is never
   * modified here), and every affected page's 72 dpi raster and the extracted text must be identical
   * to the document's. Returns what differs, or {@code null}.
   */
  private String strayRemovalChanges(PDDocument document, Plan plan) {
    return RenderIdentity.changes(document, plan.pagesWithStrayPaints(), scratch -> {
      ContentTagger.apply(scratch, ContentTagger.analyze(scratch));
      if (scratchHookForTests != null) {
        scratchHookForTests.accept(scratch);
      }
    });
  }

  /** Whether the structure rule would apply: the companion XMP rule keys off this. */
  boolean applicable(PDDocument document, WorkerRequest request) {
    return inspect(document, request).applicable();
  }

  private Plan plan(PDDocument document) throws IOException, UnsupportedContentException {
    if (analyzedDocument == document && analyzedPlan != null) {
      return analyzedPlan;
    }
    analyzedPlan = ContentTagger.analyze(document, removeStrayPaints);
    analyzedDocument = document;
    return analyzedPlan;
  }
}
