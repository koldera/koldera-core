package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.generated.StructureRules;
import com.koldera.pdf.inference.StructureStrategy;
import com.koldera.pdf.integrity.CopyWriter;
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
 * {@code PDF_UA_IDENTIFIER_V1}: a step, not a {@link RemediationRule}, since it depends on the final validation. In a {@code fix}
 * run, when the only failing check is {@code 5-1} and no {@link StructureRules} check fails, it adds {@code pdfuaid:part=1} to the
 * XMP packet (uploads also need tagged text, {@link #textBlocker}; every copy needs an XMP title, {@link #titleBlocker}). The
 * identified copy is re-validated and published only with zero failures. A machine-check declaration, not a conformance claim.
 */
public final class PdfUaIdentifierStep {

  public static final String NAMESPACE = "http://www.aiim.org/pdfua/ns/id/";
  public static final String PREFIX = "pdfuaid";

  /** veraPDF's PDF/UA-1 test for the missing identification schema (ISO 14289-1:2014 clause 5). */
  public static final String IDENTIFIER_CHECK = "5-1";

  private PdfUaIdentifierStep() {}

  /** Mode {@code fix} only, for uploads and generated PDFs alike; the deprecated modes never. */
  public static boolean participates(WorkerRequest request) {
    return request.isFix();
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
