package com.koldera.pdf.preflight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Cheap structural probes used only to raise human-review findings. They detect the presence of
 * content classes the engine will never repair (images needing alternative text); they do not
 * judge accessibility.
 */
public final class ContentProbe {

  private ContentProbe() {}

  /** 1-based page numbers whose resources (including nested form XObjects) contain image XObjects. */
  public static List<Integer> pagesWithImages(PDDocument document) {
    List<Integer> pages = new ArrayList<>();
    int number = 0;
    for (PDPage page : document.getPages()) {
      number++;
      try {
        if (containsImage(page.getResources(), 0)) {
          pages.add(number);
        }
      } catch (IOException | RuntimeException e) {
        // A resource that cannot be read is itself a reason for a human to look; count the page.
        pages.add(number);
      }
    }
    return pages;
  }

  private static boolean containsImage(PDResources resources, int depth) throws IOException {
    if (resources == null || depth > 8) {
      return false;
    }
    for (COSName name : resources.getXObjectNames()) {
      if (resources.isImageXObject(name)) {
        return true;
      }
      PDXObject xObject = resources.getXObject(name);
      if (xObject instanceof PDImageXObject) {
        return true;
      }
      if (xObject instanceof PDFormXObject form && containsImage(form.getResources(), depth + 1)) {
        return true;
      }
    }
    return false;
  }
}
