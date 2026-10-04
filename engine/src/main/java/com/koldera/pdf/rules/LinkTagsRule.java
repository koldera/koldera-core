package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.repair.LinkTagger;
import java.io.IOException;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * {@code PDF_LINK_TAGS_V1} (mode {@code fix}, uploads): puts every {@code Link} annotation outside the structure tree into a
 * {@code Link} element and describes link annotations that have no {@code /Contents} ({@link LinkTagger}). It runs after the
 * structure rules and is decided again at apply time, since a structure step may have rebuilt the tree. Untagged documents
 * are skipped; generated PDFs are handled by {@code PDF_GENERATED_STRUCTURE_V1}.
 */
public final class LinkTagsRule implements RemediationRule {

  private static final COSName LINK = COSName.getPDFName("Link");

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_LINK_TAGS_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("links are only tagged in fix for uploaded PDFs", null, null);
    }
    int untagged = LinkTagger.untaggedLinks(document);
    int undescribed = taggedWithoutDescription(document);
    if (untagged == 0 && undescribed == 0) {
      int inTree = linksInTree(document);
      if (inTree == 0) {
        return RuleDecision.skip(ALL_DONE, null, null);
      }
      // A structure step earlier in this run may discard these tags on the copy (PDF_STRUCTURE_INFERRED_V1,
      // strategy tagged_unusable): whether anything is left to do is decided again when the rule applies.
      return RuleDecision.apply(
          "link annotations are checked again after this run's structure steps", inTree + (inTree == 1 ? " link annotation" : " link annotations")
              + " in the structure tree", "every link annotation in a Link element with a description");
    }
    String before = untagged == 0 ? "" : untagged + (untagged == 1 ? " link annotation" : " link annotations") + " outside the structure tree";
    if (undescribed > 0) {
      before += (before.isEmpty() ? "" : ", ") + undescribed + (undescribed == 1 ? " tagged link" : " tagged links") + " without a description";
    }
    return RuleDecision.apply("link annotations to tag or describe", before, "every link annotation in a Link element with a description");
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    if (LinkTagger.untaggedLinks(document) == 0 && taggedWithoutDescription(document) == 0) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, ALL_DONE, decision.before(), null);
    }
    LinkTagger.Outcome outcome;
    try {
      outcome = LinkTagger.apply(document);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "could not tag the links (" + e.getClass().getSimpleName() + ")", decision.before(), null);
    }
    if (outcome.refusal() != null) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, "links are not tagged: " + outcome.refusal(), decision.before(), null);
    }
    String counts = "links tagged " + outcome.tagged() + ", joined " + outcome.joined() + ", references repaired " + outcome.repaired()
        + ", descriptions " + outcome.described() + ", left untagged " + outcome.left();
    if (outcome.tagged() + outcome.joined() + outcome.repaired() + outcome.described() == 0) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, "no link could be placed: " + counts, decision.before(), null);
    }
    return new WorkerResult.Action(
        id(),
        ActionOutcome.APPLIED,
        counts + "; each Link element holds only the annotation, next to the text under it, which stays in its paragraph; whether each"
            + " description says where the link goes requires human review",
        decision.before(),
        decision.after());
  }

  private static final String ALL_DONE = "every link annotation is already in the structure tree and described";

  /** Link annotations that are in the tree (have /StructParent). */
  private static int linksInTree(PDDocument document) {
    return countLinks(document, false);
  }

  /** Link annotations that are in the tree (have /StructParent) but have no /Contents. */
  private static int taggedWithoutDescription(PDDocument document) {
    return countLinks(document, true);
  }

  private static int countLinks(PDDocument document, boolean undescribedOnly) {
    int n = 0;
    for (PDPage page : document.getPages()) {
      if (!(page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots)) {
        continue;
      }
      for (COSBase entry : annots) {
        COSBase resolved = entry instanceof COSObject ref ? ref.getObject() : entry;
        if (resolved instanceof COSDictionary annot && LINK.equals(annot.getCOSName(COSName.SUBTYPE)) && annot.containsKey(COSName.STRUCT_PARENT)) {
          String contents = annot.getString(COSName.CONTENTS);
          if (!undescribedOnly || contents == null || contents.isBlank()) {
            n++;
          }
        }
      }
    }
    return n;
  }
}
