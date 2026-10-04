package com.koldera.pdf.preflight;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Inspects the immutable input before anything else runs: size cap, PDF magic, parseability,
 * encryption, page cap, signatures, and the baseline digests used later for the non-regression
 * gate. It never writes, never executes document actions and never opens attachments.
 */
public final class PdfPreflight {

  /** ISO 32000-1 permits the header anywhere in the first 1024 bytes. */
  private static final int HEADER_SEARCH_WINDOW = 1024;

  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  public PreflightOutcome inspect(Path input, WorkerRequest request) {
    long size;
    try {
      size = Files.size(input);
    } catch (NoSuchFileException e) {
      return PreflightOutcome.rejected(ErrorCode.INVALID_INPUT, "input document is missing", null, -1);
    } catch (IOException e) {
      return PreflightOutcome.rejected(ErrorCode.INVALID_INPUT, "input document is unreadable", null, -1);
    }
    if (size <= 0) {
      return PreflightOutcome.rejected(ErrorCode.INVALID_INPUT, "input document is empty", null, size);
    }
    if (size > request.limits().maxBytes()) {
      return PreflightOutcome.rejected(
          ErrorCode.LIMIT_EXCEEDED,
          "input document exceeds maxBytes=" + request.limits().maxBytes(),
          null,
          size);
    }

    byte[] bytes;
    try {
      bytes = Files.readAllBytes(input);
    } catch (IOException e) {
      return PreflightOutcome.rejected(ErrorCode.INVALID_INPUT, "input document is unreadable", null, size);
    }
    String sha256 = sha256Hex(bytes);

    if (!hasPdfHeader(bytes)) {
      return PreflightOutcome.rejected(
          ErrorCode.INVALID_INPUT, "input does not start with a %PDF- header", sha256, size);
    }

    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) {
        return new PreflightOutcome(
            ErrorCode.UNSUPPORTED_ENCRYPTED,
            "encrypted documents are not supported",
            sha256,
            size,
            null,
            null,
            true,
            false);
      }
      int pages = document.getNumberOfPages();
      if (pages <= 0) {
        return PreflightOutcome.rejected(ErrorCode.INVALID_INPUT, "document has no pages", sha256, size);
      }
      if (pages > request.limits().maxPages()) {
        return new PreflightOutcome(
            ErrorCode.LIMIT_EXCEEDED,
            "document has " + pages + " pages, maxPages=" + request.limits().maxPages(),
            sha256,
            size,
            pages,
            null,
            false,
            false);
      }
      boolean signed = isSignedOrCertified(document);
      if (signed && request.isMutating()) {
        return new PreflightOutcome(
            ErrorCode.UNSUPPORTED_SIGNED_MUTATION,
            "document is digitally signed; mutation would invalidate the signature",
            sha256,
            size,
            pages,
            null,
            false,
            true);
      }
      String textDigest = normalizedTextDigest(extractText(document));
      return new PreflightOutcome(null, "accepted", sha256, size, pages, textDigest, false, signed);
    } catch (InvalidPasswordException e) {
      return new PreflightOutcome(
          ErrorCode.UNSUPPORTED_ENCRYPTED,
          "document requires a password; encrypted documents are not supported",
          sha256,
          size,
          null,
          null,
          true,
          false);
    } catch (IOException | RuntimeException e) {
      // Parser failures on malformed input surface as IOExceptions and, for some corrupt object
      // streams, as runtime exceptions from PDFBox. Both are typed input errors, never crashes.
      return PreflightOutcome.rejected(
          ErrorCode.INVALID_INPUT,
          "document cannot be parsed (" + e.getClass().getSimpleName() + ")",
          sha256,
          size);
    }
  }

  /** Whitespace-collapsed, NFC-normalized text digest; the same for byte-identical semantics. */
  public static String normalizedTextDigest(String text) {
    String normalized =
        Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)
            .replaceAll("\\s+", " ")
            .trim();
    return sha256Hex(normalized.getBytes(StandardCharsets.UTF_8));
  }

  public static String extractText(PDDocument document) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    return stripper.getText(document);
  }

  /**
   * {@link #extractText} of the glyphs themselves: a marked-content {@code /ActualText} is not
   * substituted for the glyphs it covers. A marker-only rewrite that keeps an element's replacement
   * text on its marked content ({@code inference/DescribedElements}) changes what {@link #extractText}
   * returns there (the reader now gets the replacement text) but never this.
   */
  public static String extractGlyphText(PDDocument document) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper() {
      @Override
      public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
        super.beginMarkedContentSequence(tag, withoutActualText(properties));
      }
    };
    stripper.setSortByPosition(true);
    return stripper.getText(document);
  }

  /** {@code properties} without {@code /ActualText} (a copy when it has one; the document is not changed). */
  public static COSDictionary withoutActualText(COSDictionary properties) {
    if (properties == null || !properties.containsKey(COSName.ACTUAL_TEXT)) {
      return properties;
    }
    COSDictionary copy = new COSDictionary(properties);
    copy.removeItem(COSName.ACTUAL_TEXT);
    return copy;
  }

  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static boolean hasPdfHeader(byte[] bytes) {
    int limit = Math.min(bytes.length, HEADER_SEARCH_WINDOW) - PDF_MAGIC.length;
    for (int i = 0; i <= limit; i++) {
      if (Arrays.equals(bytes, i, i + PDF_MAGIC.length, PDF_MAGIC, 0, PDF_MAGIC.length)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A document counts as signed when it carries at least one signature dictionary with contents,
   * or when the catalog has a {@code /Perms} dictionary (certification / DocMDP / UR rights).
   */
  private static boolean isSignedOrCertified(PDDocument document) throws IOException {
    if (!document.getSignatureDictionaries().isEmpty()) {
      return true;
    }
    COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
    COSDictionary perms = catalog.getCOSDictionary(COSName.PERMS);
    return perms != null && perms.size() > 0;
  }
}
