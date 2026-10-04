package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * {@code PDF_TAB_ORDER_V1}: PDF/UA-1 (7.18.3) requires {@code /Tabs /S} on every page with annotations. In {@code fix} for
 * uploads, once the document is tagged, such pages without it get it; nothing else changes. Untagged documents are left alone
 * because structure order means nothing without a tree; generated PDFs keep Chromium's tab order.
 */
public final class TabOrderRule implements RemediationRule {

  static final COSName TABS = COSName.getPDFName("Tabs");

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_TAB_ORDER_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("the tab order is only set in fix for uploaded PDFs", null, null);
    }
    int withAnnotations = 0;
    List<PDPage> pending = pending(document);
    for (PDPage page : document.getPages()) {
      if (hasAnnotations(page)) {
        withAnnotations++;
      }
    }
    if (withAnnotations == 0) {
      return RuleDecision.skip("no page has annotations; no tab order to set", null, null);
    }
    if (pending.isEmpty()) {
      return RuleDecision.skip("every page with annotations already uses structure order (/Tabs /S)", null, null);
    }
    return RuleDecision.apply("pages with annotations without structure tab order: " + pending.size(), describe(pending.size()), "/Tabs /S");
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    if (document.getDocumentCatalog().getStructureTreeRoot() == null) {
      return new WorkerResult.Action(
          id(), ActionOutcome.SKIPPED, "document is not tagged; structure order (/Tabs /S) needs a structure tree", decision.before(), null);
    }
    List<PDPage> pending = pending(document);
    if (pending.isEmpty()) {
      return new WorkerResult.Action(
          id(), ActionOutcome.SKIPPED, "every page with annotations already uses structure order (/Tabs /S)", decision.before(), null);
    }
    for (PDPage page : pending) {
      page.getCOSObject().setItem(TABS, COSName.S);
    }
    int n = pending.size();
    return new WorkerResult.Action(
        id(), ActionOutcome.APPLIED, "set /Tabs /S (structure order) on " + n + (n == 1 ? " page" : " pages") + " with annotations", describe(n), "/Tabs /S");
  }

  private static String describe(int pages) {
    return pages + (pages == 1 ? " page" : " pages") + " with annotations without /Tabs /S";
  }

  private static List<PDPage> pending(PDDocument document) {
    List<PDPage> out = new ArrayList<>();
    for (PDPage page : document.getPages()) {
      if (hasAnnotations(page) && !COSName.S.equals(page.getCOSObject().getDictionaryObject(TABS))) {
        out.add(page);
      }
    }
    return out;
  }

  private static boolean hasAnnotations(PDPage page) {
    COSDictionary dict = page.getCOSObject();
    return dict.getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots && annots.size() > 0;
  }
}
