package com.koldera.pdf.integrity;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * The one way the engine writes a copy: a full save with a classic cross-reference table and without object streams.
 * PDFBox's object-stream writer shares one instance per small integer, name and boolean, so an input value stored as its own
 * object ({@code 56 0 obj 81 endobj}) would be emitted as a reference for every direct {@code 81} in the document, which the
 * PAC engine reads as inconsistent ParentTrees. The classic writer keeps direct values direct; the cost is a larger file.
 */
public final class CopyWriter {

  private CopyWriter() {}

  public static void save(PDDocument document, File target) throws IOException {
    document.save(target, CompressParameters.NO_COMPRESSION);
  }

  public static void save(PDDocument document, OutputStream target) throws IOException {
    document.save(target, CompressParameters.NO_COMPRESSION);
  }
}
