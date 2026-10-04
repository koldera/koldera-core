package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;

/**
 * {@code PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1}: sets {@code /ViewerPreferences /DisplayDocTitle
 * true} when the Info dictionary already has a single non-blank {@code /Title} and the preference
 * is absent or {@code false}. Existing viewer preferences are preserved; no title is invented or
 * changed; a {@code DisplayDocTitle} value that is not a boolean is left for a human to look at.
 */
public final class DisplayDocumentTitleRule implements RemediationRule {

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_DISPLAY_TITLE_FROM_EXISTING_TITLE_V1;
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    String before = currentDisplayDocTitle(document);
    if (!request.isMutating()) {
      return RuleDecision.skip("analyze mode: no changes are made", before, null);
    }
    PDDocumentInformation info = document.getDocumentInformation();
    String title = info == null ? null : info.getTitle();
    // fix mode: TitleRule is applied before this rule, so a title it will write is enough.
    boolean titleComing = request.isFix() && TitleResolver.resolve(document, request).isPresent();
    if ((title == null || title.isBlank()) && !titleComing) {
      return RuleDecision.skip(
          "Info dictionary has no non-blank /Title; a title is never invented, so DisplayDocTitle is left alone",
          before,
          null);
    }
    COSBase raw = rawDisplayDocTitle(document);
    if (raw != null && !(raw instanceof COSBoolean)) {
      return RuleDecision.skip(
          "ViewerPreferences /DisplayDocTitle holds a non-boolean value; not replaced automatically", before, null);
    }
    if ("true".equals(before)) {
      return RuleDecision.skip("ViewerPreferences already sets DisplayDocTitle=true", before, null);
    }
    return RuleDecision.apply(
        "Info dictionary has a non-blank /Title and DisplayDocTitle is " + (before == null ? "absent" : "false"),
        before,
        "true");
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    PDViewerPreferences prefs = catalog.getViewerPreferences();
    if (prefs == null) {
      prefs = new PDViewerPreferences(new COSDictionary());
      catalog.setViewerPreferences(prefs);
    }
    prefs.setDisplayDocTitle(true);
    if (!prefs.displayDocTitle()) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "DisplayDocTitle did not persist", decision.before(), "true");
    }
    return new WorkerResult.Action(
        id(),
        ActionOutcome.APPLIED,
        "enabled ViewerPreferences /DisplayDocTitle so viewers show the existing Info /Title; other viewer"
            + " preferences preserved; whether the title is meaningful requires human review",
        decision.before(),
        "true");
  }

  private static COSBase rawDisplayDocTitle(PDDocument document) {
    PDViewerPreferences prefs = document.getDocumentCatalog().getViewerPreferences();
    if (prefs == null) {
      return null;
    }
    return prefs.getCOSObject().getDictionaryObject(COSName.DISPLAY_DOC_TITLE);
  }

  private static String currentDisplayDocTitle(PDDocument document) {
    COSBase raw = rawDisplayDocTitle(document);
    if (raw == null) {
      return null;
    }
    if (raw instanceof COSBoolean bool) {
      return Boolean.toString(bool.getValue());
    }
    return "non-boolean";
  }
}
