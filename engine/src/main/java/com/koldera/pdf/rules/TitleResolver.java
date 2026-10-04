package com.koldera.pdf.rules;

import com.koldera.pdf.contracts.WorkerRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.xml.DomXmpParser;

/**
 * Which title a {@code fix} run gives the copy: the user's explicit title, else the document's own non-blank
 * {@code /Info /Title}, else the document's own x-default {@code dc:title} from a parseable XMP packet, else the
 * uploaded file name without its extension (never the site's placeholder names {@code document} / {@code page}).
 * Pure; never invents wording.
 */
public final class TitleResolver {
  public enum TitleSource { USER, DOCUMENT, XMP, FILE_NAME }

  public record ResolvedTitle(String title, TitleSource source) {}

  /** What the catalog's XMP packet says about {@code dc:title}. */
  enum XmpState { NONE, UNREADABLE, NO_TITLE, HAS_TITLE }

  record XmpTitle(XmpState state, String defaultTitle) {}

  /** Stems the web app substitutes when a real file name cannot be used; they are not titles. */
  static final Set<String> PLACEHOLDER_STEMS = Set.of("document", "page");

  private TitleResolver() {}

  public static Optional<ResolvedTitle> resolve(PDDocument document, WorkerRequest request) {
    String user = trimmed(request.explicitTitle());
    if (user != null) return Optional.of(new ResolvedTitle(user, TitleSource.USER));
    PDDocumentInformation info = document.getDocumentInformation();
    String existing = trimmed(info == null ? null : info.getTitle());
    if (existing != null) return Optional.of(new ResolvedTitle(existing, TitleSource.DOCUMENT));
    String xmp = xmpTitle(document).defaultTitle();
    if (xmp != null) return Optional.of(new ResolvedTitle(xmp, TitleSource.XMP));
    String name = request.sourceFileName();
    if (name == null) return Optional.empty();
    int dot = name.lastIndexOf('.');
    String stem = trimmed(dot > 0 ? name.substring(0, dot) : (dot == 0 ? "" : name));
    if (stem == null || PLACEHOLDER_STEMS.contains(stem.toLowerCase(Locale.ROOT))) return Optional.empty();
    return Optional.of(new ResolvedTitle(stem, TitleSource.FILE_NAME));
  }

  static String currentTitle(PDDocument document) {
    PDDocumentInformation info = document.getDocumentInformation();
    return info == null ? null : info.getTitle();
  }

  static XmpTitle xmpTitle(PDDocument document) {
    PDMetadata metadata = document.getDocumentCatalog().getMetadata();
    if (metadata == null) return new XmpTitle(XmpState.NONE, null);
    try {
      XMPMetadata xmp = new DomXmpParser().parse(metadata.toByteArray());
      DublinCoreSchema dc = xmp.getDublinCoreSchema();
      List<String> languages = dc == null ? null : dc.getTitleLanguages();
      if (languages == null || languages.isEmpty() || !hasTitleText(dc)) return new XmpTitle(XmpState.NO_TITLE, null);
      return new XmpTitle(XmpState.HAS_TITLE, trimmed(dc.getTitle("x-default")));
    } catch (Exception e) {
      return new XmpTitle(XmpState.UNREADABLE, null);
    }
  }

  /**
   * Whether any {@code dc:title} entry of the schema has non-whitespace text. A {@code dc:title} whose every
   * {@code rdf:li} is empty or whitespace counts as no title (PAC "Title in XMP metadata").
   */
  static boolean hasTitleText(DublinCoreSchema dc) throws org.apache.xmpbox.type.BadFieldValueException {
    List<String> languages = dc == null ? null : dc.getTitleLanguages();
    if (languages == null) return false;
    for (String language : languages) {
      if (trimmed(dc.getTitle(language)) != null) return true;
    }
    return false;
  }

  private static String trimmed(String s) {
    if (s == null) return null;
    String t = s.strip();
    return t.isEmpty() ? null : t;
  }
}
