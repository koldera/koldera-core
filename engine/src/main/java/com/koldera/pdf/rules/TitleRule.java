package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpSerializer;

/**
 * {@code PDF_TITLE_V1} (mode {@code fix} only): writes the resolved title to {@code /Info /Title} and, when an XMP packet exists,
 * to its {@code dc:title}. A document's own title is replaced only by an explicit user title; an XMP-only title is copied to
 * Info and a kept Info title into a packet without {@code dc:title} (an all-blank one counts as none). Without a packet,
 * {@code PDF_XMP_METADATA_FROM_INFO_V1} writes {@code dc:title} from the Info title.
 */
public final class TitleRule implements RemediationRule {
  static final String FROM_USER = "title supplied by the user";
  public static final String FROM_FILE_NAME = "title taken from the file name; check it";
  static final String FROM_XMP = "the document's XMP title was copied to the Info dictionary";
  static final String KEPT = "the document already has a title; it is kept";
  static final String KEPT_COPIED_TO_XMP = "the document already has a title; it is kept and copied to the XMP metadata";
  static final String NO_TITLE = "no title was supplied, the document has none and the file name gives none";
  static final String XMP_UNREADABLE = "; the existing XMP packet could not be read and was left unchanged";
  static final String XMP_TITLE_KEPT = "; the XMP packet already has a title and was left unchanged";

  @Override public WorkerResult.RuleId id() { return WorkerResult.RuleId.PDF_TITLE_V1; }

  @Override public boolean participates(WorkerRequest request) { return request.isFix(); }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    String before = TitleResolver.currentTitle(document);
    Optional<TitleResolver.ResolvedTitle> resolved = TitleResolver.resolve(document, request);
    if (resolved.isEmpty()) return RuleDecision.skip(NO_TITLE, before, null);
    TitleResolver.ResolvedTitle r = resolved.get();
    return switch (r.source()) {
      case DOCUMENT -> TitleResolver.xmpTitle(document).state() == TitleResolver.XmpState.NO_TITLE
          ? RuleDecision.apply(KEPT_COPIED_TO_XMP, before, before)
          : RuleDecision.skip(KEPT, before, before);
      case XMP -> RuleDecision.apply(FROM_XMP, before, r.title());
      case USER -> RuleDecision.apply(FROM_USER, before, r.title());
      case FILE_NAME -> RuleDecision.apply(FROM_FILE_NAME, before, r.title());
    };
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    PDDocumentInformation info = document.getDocumentInformation();
    if (info == null) {
      info = new PDDocumentInformation();
      document.setDocumentInformation(info);
    }
    info.setTitle(decision.after());
    String reason = decision.reason();
    PDMetadata metadata = document.getDocumentCatalog().getMetadata();
    if (metadata != null && !FROM_XMP.equals(reason)) {
      reason = reason + setDcTitle(document, metadata, decision.after(), FROM_USER.equals(reason));
    }
    if (!decision.after().equals(document.getDocumentInformation().getTitle())) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "the title did not persist", decision.before(), decision.after());
    }
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason, decision.before(), decision.after());
  }

  /** Returns the reason suffix: empty when written, otherwise why the packet was left unchanged. */
  private static String setDcTitle(PDDocument document, PDMetadata metadata, String title, boolean replace) {
    try {
      XMPMetadata xmp = new DomXmpParser().parse(metadata.toByteArray());
      DublinCoreSchema dc = xmp.getDublinCoreSchema();
      if (dc == null) dc = xmp.createAndAddDublinCoreSchema();
      List<String> languages = dc.getTitleLanguages();
      boolean present = languages != null && !languages.isEmpty();
      boolean hasTitle = present && TitleResolver.hasTitleText(dc);
      if (hasTitle && !replace) return XMP_TITLE_KEPT;
      if (present) dc.removeProperty(dc.getTitleProperty());
      dc.setTitle(title);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      new XmpSerializer().serialize(xmp, out, true);
      document.getDocumentCatalog().setMetadata(new PDMetadata(document, new ByteArrayInputStream(out.toByteArray())));
      return "";
    } catch (Exception e) {
      return XMP_UNREADABLE;
    }
  }
}
