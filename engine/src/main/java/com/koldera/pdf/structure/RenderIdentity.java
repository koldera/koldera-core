package com.koldera.pdf.structure;

import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.preflight.PdfPreflight;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Proof that a content-stream rewrite changes nothing a reader sees: the mutation is applied to a
 * scratch copy (written with {@link CopyWriter} and loaded separately; the document itself is never
 * modified), and each named page's 72 dpi raster and the extracted text of the whole document must
 * be identical to the document's.
 */
public final class RenderIdentity {

  /** The rewrite to check, applied to the scratch copy only. */
  public interface Mutation {
    void apply(PDDocument scratch) throws Exception;
  }

  private RenderIdentity() {}

  /**
   * What differs after {@code mutation} ({@code "the rendering of page N"}, {@code "the extracted text"}, or {@code "something that
   * could not be checked (X)"}), or {@code null} when nothing does. Compares the given 1-based pages.
   */
  public static String changes(PDDocument document, List<Integer> pages, Mutation mutation) {
    try {
      String textBefore = PdfPreflight.extractText(document);
      PDFRenderer renderer = new PDFRenderer(document);
      try (PDDocument scratch = Loader.loadPDF(scratchCopy(document))) {
        mutation.apply(scratch);
        PDFRenderer after = new PDFRenderer(scratch);
        // One page pair at a time: memory stays at two rasters whatever the page count.
        for (int page : pages) {
          if (!Arrays.equals(raster(renderer, page - 1), raster(after, page - 1))) {
            return "the rendering of page " + page;
          }
        }
        if (!textBefore.equals(PdfPreflight.extractText(scratch))) {
          return "the extracted text";
        }
      }
      return null;
    } catch (Exception e) {
      return "something that could not be checked (" + e.getClass().getSimpleName() + ")";
    }
  }

  /** The document as bytes; the trailer is restored afterwards, so the save leaves no /ID or other trace behind. */
  public static byte[] scratchCopy(PDDocument document) throws IOException {
    COSDictionary trailer = document.getDocument().getTrailer();
    Map<COSName, COSBase> saved = new LinkedHashMap<>();
    for (Map.Entry<COSName, COSBase> e : trailer.entrySet()) {
      saved.put(e.getKey(), e.getValue());
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      CopyWriter.save(document, out);
      return out.toByteArray();
    } finally {
      trailer.clear();
      for (Map.Entry<COSName, COSBase> e : saved.entrySet()) {
        trailer.setItem(e.getKey(), e.getValue());
      }
    }
  }

  private static int[] raster(PDFRenderer renderer, int pageIndex) throws IOException {
    BufferedImage image = renderer.renderImageWithDPI(pageIndex, 72);
    BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    rgb.getGraphics().drawImage(image, 0, 0, null);
    return ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData().clone();
  }
}
