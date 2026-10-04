package com.koldera.pdf.repair;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.generated.UntaggedArtifacts;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import java.io.IOException;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

/**
 * {@code PDF_UNTAGGED_CONTENT_V1} (mode {@code fix}, tagged uploads): in the producer's tags, artifact and tagged content
 * are separated ({@link MarkedContentNesting}), unreferenced MCIDs showing no text become artifacts ({@link OrphanMcids}),
 * and painting outside any sequence is marked as artifact ({@link UntaggedArtifacts.Scope#PAINTING_ONLY}). Text outside the
 * tags is never hidden. All marker-only, proven by the text digest, {@code CopyIntegrity} and the gate. Skipped for untagged uploads.
 */
public final class UntaggedContentRule implements RemediationRule {

  static final String UNTAGGED = "document is not tagged; untagged content is handled by the structure rule";
  static final String REBUILT = "the tags were rebuilt from the page content in this run";

  private PDDocument inspectedDocument;
  private COSDictionary inspectedRoot;

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_UNTAGGED_CONTENT_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    inspectedDocument = null;
    inspectedRoot = null;
    if (!participates(request)) {
      return RuleDecision.skip("untagged content is only marked in fix for uploaded PDFs", null, null);
    }
    PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return RuleDecision.skip(UNTAGGED, null, null);
    }
    inspectedDocument = document;
    inspectedRoot = root.getCOSObject();
    return RuleDecision.apply("untagged content and artifact/tag nesting are checked after this run's structure steps", null, null);
  }

  /** The outcome of the three steps together. */
  private record Counts(UntaggedArtifacts.Outcome painting, MarkedContentNesting.Outcome nesting, OrphanMcids.Outcome orphans) {
    String describe() {
      return "untagged painting marked as artifact " + painting.painting() + ", untagged text left as it is " + painting.textLeft()
          + ", artifacts split out of tagged content " + nesting.artifactsSplitOutOfTags() + ", artifacts split around tagged content "
          + nesting.artifactsSplitAroundTags() + ", unreferenced MCIDs marked as artifact " + (orphans.markedArtifact() + nesting.unreferencedMarkersRemoved()) + ", streams left alone "
          + nesting.streamsLeftAlone();
    }

    boolean changed() {
      return painting.painting() + nesting.artifactsSplitOutOfTags() + nesting.artifactsSplitAroundTags() + nesting.unreferencedMarkersRemoved() + orphans.markedArtifact() > 0;
    }
  }

  private static Counts run(PDDocument document) throws IOException {
    MarkedContentNesting.Outcome nesting = MarkedContentNesting.repair(document);
    OrphanMcids.Outcome orphans = OrphanMcids.repair(document);
    UntaggedArtifacts.Outcome painting = UntaggedArtifacts.apply(document, UntaggedArtifacts.Scope.PAINTING_ONLY);
    return new Counts(painting, nesting, orphans);
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    COSDictionary root = inspectedDocument == document ? inspectedRoot : null;
    inspectedDocument = null;
    inspectedRoot = null;
    if (!decision.applicable() || root == null) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    PDStructureTreeRoot current = document.getDocumentCatalog().getStructureTreeRoot();
    if (current == null || current.getCOSObject() != root) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, REBUILT, null, null);
    }
    try {
      // Marker-only edits (BMC/BDC/EMC inserted, moved or removed): rendering-neutral by construction; the text digest, CopyIntegrity and the gate are the proof. Run once, on the document.
      // An exception part-way leaves the document half changed and returns FAILED: PdfProcessor never saves a copy when
      // any action FAILED (and every rerun reloads the original bytes), so this rule needs no rollback of its own.
      Counts counts = run(document);
      return new WorkerResult.Action(id(), counts.changed() ? ActionOutcome.APPLIED : ActionOutcome.SKIPPED, counts.describe(), null, null);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not mark the untagged content (" + e.getClass().getSimpleName() + ")", null, null);
    }
  }
}
