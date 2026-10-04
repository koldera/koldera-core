package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.junit.jupiter.api.Test;

/**
 * Fixture shape (Chromium, {@code generated-links.html}): annotation 0 a text link, 1 and 2 an image link with {@code /Alt},
 * 3 to 5 links without text or alt (settings icon, mailto icon, internal anchor to {@code #section-2}), and 6 to 9 one link
 * that wraps onto four lines (one annotation per line, same {@code Link} element and MCID).
 */
class LinkContentsTest {

  @Test
  void everyLinkAnnotationGetsADescription() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-links.pdf")) {
      assertEquals(10, elements(doc).size(), "fixture shape: ten Link annotations across six Link elements");

      assertEquals(10, LinkContents.apply(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<PDAnnotation> annotations = elements(after);
        assertEquals("product catalog", annotations.get(0).getContents(), "text link: whitespace-normalized MCID text");
        assertEquals("Home", annotations.get(1).getContents(), "image link: the inner Figure's /Alt");
        assertEquals("Home", annotations.get(2).getContents(), "the second annotation of the same Link gets the same description");
        assertEquals("Link to https://example.com/settings", annotations.get(3).getContents(), "no text, no alt: falls back to the URI");
        assertEquals("Link to mailto:support@example.com", annotations.get(4).getContents(), "a mailto URI is still a URI");
        assertEquals("Link to page 3", annotations.get(5).getContents(), "an internal destination falls back to its page number");
        for (int i = 6; i <= 9; i++) {
          assertEquals("Wrapping link text goes here", annotations.get(i).getContents(), "a space is kept between words split across wrapped lines (annotation " + i + ")");
        }
      }
    }
  }

  @Test
  void anAnnotationWithExistingNonBlankContentsIsNeverOverwritten() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-links.pdf")) {
      List<PDAnnotation> annotations = elements(doc);
      annotations.get(0).setContents("Read the product catalog (custom)");
      annotations.get(3).setContents("   ");

      int changed = LinkContents.apply(doc);

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        List<PDAnnotation> reloaded = elements(after);
        assertEquals("Read the product catalog (custom)", reloaded.get(0).getContents(), "existing non-blank /Contents kept as is");
        assertEquals("Link to https://example.com/settings", reloaded.get(3).getContents(), "a blank (whitespace-only) /Contents is not treated as already set");
      }
      assertEquals(9, changed, "the nine annotations without a real description get one");
    }
  }

  @Test
  void aDocumentAlreadyDescribedGetsNoFurtherChanges() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-links.pdf")) {
      LinkContents.apply(doc);
      assertEquals(0, LinkContents.apply(doc), "every annotation already has non-blank /Contents");
    }
  }

  /** Every Link annotation on the document, in the order Chromium wrote /StructParent (1..N). */
  private static List<PDAnnotation> elements(PDDocument doc) throws IOException {
    List<PDAnnotation> out = new ArrayList<>();
    for (PDPage page : doc.getPages()) {
      out.addAll(page.getAnnotations());
    }
    out.sort(Comparator.comparingInt(a -> a.getCOSObject().getInt(COSName.STRUCT_PARENT, -1)));
    return out;
  }
}
