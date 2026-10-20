package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.generated.StructureRules;
import com.koldera.pdf.inference.StructureStrategy;
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.repair.FiguresWithoutAltRule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpParsingException;
import org.apache.xmpbox.xml.XmpSerializer;

/**
 * {@code PDF_UA_IDENTIFIER_V1}: {@code pdfuaid:part=1} is a PDF/UA-1 conformance declaration, so it is never written by default
 * ({@link #NOT_REQUESTED}). A {@code fix} request needs {@code declarePdfUa} and {@code humanReviewAcknowledged}, and the run must not
 * have made the structure or artifacted a figure ({@link #requestBlocker}); then {@code 5-1} must be the only failing check
 * ({@link #blocker}, {@link #textBlocker}, {@link #titleBlocker}). The identified copy is published only with zero failures.
 */
public final class PdfUaIdentifierStep {

  public static final String NAMESPACE = "http://www.aiim.org/pdfua/ns/id/";
  public static final String PREFIX = "pdfuaid";

  /** veraPDF's PDF/UA-1 test for the missing identification schema (ISO 14289-1:2014 clause 5). */
  public static final String IDENTIFIER_CHECK = "5-1";

  /** Default: the identifier is a conformance declaration, so it is only written when the caller asks for it after review. */
  public static final String NOT_REQUESTED =
      "not written: pdfuaid:part=1 declares PDF/UA-1 conformance to every reader, so it is written only on request (declarePdfUa) after a person has"
          + " reviewed the document (humanReviewAcknowledged)";

  public static final String NOT_ACKNOWLEDGED =
      "not written: declarePdfUa was requested without humanReviewAcknowledged; the declaration needs a person to have reviewed the document";

  public static final String ENGINE_MADE_STRUCTURE =
      "not written: the structure tags were made by the engine (inferred from the layout or basic content-order tags), not by the author;"
          + " such a copy is never declared PDF/UA";

  public static final String FIGURES_MARKED_ARTIFACT =
      "not written: figures without alternative text were marked as decoration (artifact) by the engine; such a copy is never declared PDF/UA";

  private PdfUaIdentifierStep() {}

  /** Mode {@code fix} only, for uploads and generated PDFs alike; the deprecated modes never. */
  public static boolean participates(WorkerRequest request) {
    return request.isFix();
  }

  /**
   * Checked before anything else: {@code null} only when the request asks for the declaration with the human-review
   * acknowledgement and this run neither made the structure tags itself nor marked a figure as artifact; otherwise why not.
   */
  public static String requestBlocker(WorkerRequest request, List<WorkerResult.Action> actions) {
    if (!Boolean.TRUE.equals(request.declarePdfUa())) {
      return NOT_REQUESTED;
    }
    if (!request.declaresPdfUaAfterReview()) {
      return NOT_ACKNOWLEDGED;
    }
    if (actions.stream().anyMatch(a -> a.outcome() == WorkerResult.ActionOutcome.APPLIED
        && (a.ruleId() == WorkerResult.RuleId.PDF_STRUCTURE_INFERRED_V1 || a.ruleId() == WorkerResult.RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1))) {
      return ENGINE_MADE_STRUCTURE;
    }
    if (actions.stream().anyMatch(a -> FiguresWithoutAltRule.artifactedPerPage(a).map(perPage -> !perPage.isEmpty()).orElse(false))) {
      return FIGURES_MARKED_ARTIFACT;
    }
    return null;
  }

  /**
   * {@code null} when the final validation fails exactly the identifier check, i.e. the identifier
   * may be written; otherwise why it is not, naming every other failing rule.
   */
  public static String blocker(Map<String, Integer> finalFailures) {
    List<String> others = finalFailures.keySet().stream().filter(rule -> !isIdentifierCheck(rule)).sorted().toList();
    if (!others.isEmpty()) {
      return "not written: the final validation still fails " + String.join(", ", others)
          + "; the PDF/UA identifier is written only when " + IDENTIFIER_CHECK + " is the only failing check";
    }
    if (finalFailures.isEmpty()) {
      return "not written: the final validation reports no missing PDF/UA identifier";
    }
    return null;
  }

  /**
   * {@code null} when the copy at {@code candidate} breaks none of the PAC-only structure rules
   * ({@link StructureRules}); otherwise why the identifier is not written, naming each rule with its
   * count. A copy that cannot be read for the check gets no identifier either.
   */
  public static String structureBlocker(Path candidate) {
    Map<String, Integer> findings;
    try (PDDocument document = Loader.loadPDF(candidate.toFile())) {
      findings = StructureRules.check(document);
    } catch (IOException | RuntimeException e) {
      return "not written: the copy could not be read for the structure checks (" + e.getClass().getSimpleName() + ")";
    }
    String summary = StructureRules.summary(findings);
    return summary == null
        ? null
        : "not written: the internal structure checks (rules the PAC engine applies and veraPDF does not) still report " + summary
            + "; the PDF/UA identifier is written only when these are clean too";
  }

  /** Why an upload's copy gets no identifier when it presents no text. */
  public static final String NO_TAGGED_TEXT = "not written: the copy has no tagged text (all content is decoration), which no machine check can judge";

  /**
   * Uploads only: {@code null} when the copy shows text inside an MCID-marked sequence ({@link StructureStrategy#hasTaggedText});
   * otherwise {@link #NO_TAGGED_TEXT}. An image-only scan passes every machine check while presenting nothing to assistive
   * technology, so it is never declared; an unreadable copy gets no identifier either.
   */
  public static String textBlocker(Path candidate) {
    try (PDDocument document = Loader.loadPDF(candidate.toFile())) {
      return StructureStrategy.hasTaggedText(document) ? null : NO_TAGGED_TEXT;
    } catch (IOException | RuntimeException e) {
      return "not written: the copy could not be read for the tagged-text check (" + e.getClass().getSimpleName() + ")";
    }
  }

  /** Why a copy gets no identifier when its XMP packet has no usable title (PAC "Title in XMP metadata"). */
  public static final String NO_XMP_TITLE = "not written: the XMP metadata has no title";

  /**
   * Uploads and generated PDFs: {@code null} when the copy at {@code candidate} has an XMP packet whose x-default
   * {@code dc:title} has non-whitespace text; otherwise {@link #NO_XMP_TITLE} (no packet, no dc:title, or an empty or
   * whitespace one). A packet that cannot be read for the check gets no identifier either.
   */
  public static String titleBlocker(Path candidate) {
    try (PDDocument document = Loader.loadPDF(candidate.toFile())) {
      PDMetadata metadata = document.getDocumentCatalog().getMetadata();
      if (metadata == null) {
        return NO_XMP_TITLE;
      }
      DomXmpParser parser = new DomXmpParser();
      parser.setStrictParsing(false);
      XMPMetadata xmp = parser.parse(metadata.toByteArray());
      var dc = xmp.getDublinCoreSchema();
      String title = dc == null ? null : dc.getTitle("x-default");
      return title == null || title.isBlank() ? NO_XMP_TITLE : null;
    } catch (IOException | XmpParsingException | RuntimeException | org.apache.xmpbox.type.BadFieldValueException e) {
      return "not written: the XMP metadata could not be read for the title check (" + e.getClass().getSimpleName() + ")";
    }
  }

  private static boolean isIdentifierCheck(String ruleId) {
    int space = ruleId.lastIndexOf(' ');
    return IDENTIFIER_CHECK.equals(space < 0 ? ruleId : ruleId.substring(space + 1));
  }

  /**
   * Reads {@code source}, adds {@code pdfuaid:part=1} to its existing XMP packet and saves the
   * result as {@code target}; {@code source} is never written. Fails when the copy has no packet or
   * the packet cannot be parsed: an identifier is never added to a packet this step cannot read.
   */
  public static void writeIdentifiedCopy(Path source, Path target) throws IOException {
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      PDMetadata metadata = document.getDocumentCatalog().getMetadata();
      if (metadata == null) {
        throw new IOException("the copy has no XMP metadata packet");
      }
      metadata.importXMPMetadata(withIdentifier(metadata.toByteArray()));
      CopyWriter.save(document, target.toFile());
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(target);
      throw e instanceof IOException io ? io : new IOException(e);
    }
  }

  public static final String WITHDRAWN =
      "removed the pdfuaid identifier the renderer wrote: it declares PDF/UA-1 conformance to every reader, so a rendered document is"
          + " declared only on request (declarePdfUa) after human review; every other byte of the XMP packet is kept";

  /**
   * {@code PDF_UA_IDENTIFIER_WITHDRAWN_V1}, documents rendered by Typst only ({@code --pdf-standard ua-1} writes the identifier
   * itself): removes the {@code pdfuaid} properties from the XMP packet in place ({@link XmpPacketEditor#withoutProperties}),
   * nothing else. Skipped when there are none; failed when the packet cannot be edited safely. The identifier step may add
   * the identifier back afterwards, on the same terms as for any other copy.
   */
  public static WorkerResult.Action withdraw(PDDocument document) {
    WorkerResult.RuleId id = WorkerResult.RuleId.PDF_UA_IDENTIFIER_WITHDRAWN_V1;
    PDMetadata metadata = document.getDocumentCatalog().getMetadata();
    try {
      byte[] edited = metadata == null ? null : XmpPacketEditor.withoutProperties(metadata.toByteArray(), NAMESPACE);
      if (edited == null) {
        return new WorkerResult.Action(id, WorkerResult.ActionOutcome.SKIPPED, "the XMP metadata has no PDF/UA identifier to withdraw", null, null);
      }
      metadata.importXMPMetadata(edited);
      return new WorkerResult.Action(id, WorkerResult.ActionOutcome.APPLIED, WITHDRAWN, "pdfuaid identifier written by the renderer", "no PDF/UA identifier");
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(id, WorkerResult.ActionOutcome.FAILED, "could not withdraw the renderer's PDF/UA identifier (" + e.getMessage() + ")", null, null);
    }
  }

  static byte[] withIdentifier(byte[] packet) throws IOException {
    try {
      DomXmpParser parser = new DomXmpParser();
      parser.setStrictParsing(false);
      XMPMetadata xmp = parser.parse(packet);
      XMPSchema schema = xmp.getSchema(NAMESPACE);
      if (schema == null) {
        schema = xmp.createAndAddDefaultSchema(PREFIX, NAMESPACE);
      }
      schema.setIntegerPropertyValueAsSimple("part", 1);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      new XmpSerializer().serialize(xmp, out, true);
      return out.toByteArray();
    } catch (XmpParsingException | javax.xml.transform.TransformerException e) {
      throw new IOException("could not rewrite the XMP packet (" + e.getClass().getSimpleName() + ")", e);
    }
  }
}
