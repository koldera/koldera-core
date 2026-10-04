package com.koldera.pdf.fonts;

import java.util.OptionalInt;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.xml.DomXmpParser;

/** The PDF/A part a document declares in its catalog's XMP packet ({@code pdfaid:part}). */
public final class PdfAConformance {

  private PdfAConformance() {}

  /**
   * The declared {@code pdfaid:part}, read with xmpbox's lenient parser; empty when the document has no
   * packet, the packet has no PDF/A identification, or anything about it cannot be read.
   */
  public static OptionalInt declaredPart(PDDocument document) {
    try {
      PDMetadata metadata = document.getDocumentCatalog().getMetadata();
      if (metadata == null) {
        return OptionalInt.empty();
      }
      DomXmpParser parser = new DomXmpParser();
      parser.setStrictParsing(false);
      XMPMetadata xmp = parser.parse(metadata.toByteArray());
      PDFAIdentificationSchema id = xmp.getPDFAIdentificationSchema();
      Integer part = id == null ? null : id.getPart();
      return part == null ? OptionalInt.empty() : OptionalInt.of(part);
    } catch (Exception e) {
      // Any parse failure (malformed XML, a bad field value, an unreadable stream) means "not declared".
      return OptionalInt.empty();
    }
  }
}
