package com.koldera.pdf.generated;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

/**
 * A hostile upload can share one structure element (or number-tree node) between many parents:
 * {@code /K [E E]} forty levels deep is 2^40 paths through a handful of objects, and a {@code /Kids}
 * array that lists its own node in a non-last slot is the same fan-out in a number tree. Every
 * walker visits each object once, so these finish at once and see each object once.
 */
class HostileFanOutTest {

  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final int LEVELS = 40;

  private static COSDictionary element(String type) {
    COSDictionary dict = new COSDictionary();
    dict.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
    dict.setItem(COSName.S, COSName.getPDFName(type));
    return dict;
  }

  /** Div chain, each /K [next next], ending in {@code bottom}; returns the top. */
  private static COSDictionary diamondChain(COSDictionary bottom) {
    COSDictionary next = bottom;
    for (int i = 0; i < LEVELS; i++) {
      COSDictionary div = element("Div");
      div.setItem(COSName.K, new COSArray(List.of(next, next)));
      next.setItem(COSName.P, div);
      next = div;
    }
    return next;
  }

  private static PDDocument docWithTree(COSDictionary top) {
    PDDocument doc = new PDDocument();
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    root.getCOSObject().setItem(COSName.K, top);
    top.setItem(COSName.P, root.getCOSObject());
    doc.getDocumentCatalog().setStructureTreeRoot(root);
    return doc;
  }

  @Test
  void elementsOfTypeVisitsASharedElementOnce() throws Exception {
    COSDictionary link = element("Link");
    try (PDDocument doc = docWithTree(diamondChain(link))) {
      List<PDStructureElement> links = assertTimeoutPreemptively(BOUND, () -> StructTree.elementsOfType(doc, "Link"));
      assertEquals(1, links.size(), "a Link repeated in /K is listed once");
      assertEquals(link, links.get(0).getCOSObject());
    }
  }

  @Test
  void linkContentsWalksASharedSubtreeOnce() throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDAnnotationLink annotation = new PDAnnotationLink();
      PDActionURI uri = new PDActionURI();
      uri.setURI("https://example.com/");
      annotation.setAction(uri);
      page.getAnnotations().add(annotation);
      COSDictionary objr = new COSDictionary();
      objr.setItem(COSName.TYPE, COSName.getPDFName("OBJR"));
      objr.setItem(COSName.OBJ, annotation.getCOSObject());
      COSDictionary span = element("Span");
      span.setItem(COSName.K, objr);
      COSDictionary link = element("Link");
      COSDictionary inner = diamondChain(span);
      link.setItem(COSName.K, inner);
      inner.setItem(COSName.P, link);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      COSDictionary shell = diamondChain(link);
      root.getCOSObject().setItem(COSName.K, shell);
      doc.getDocumentCatalog().setStructureTreeRoot(root);

      int changed = assertTimeoutPreemptively(BOUND, () -> LinkContents.apply(doc));
      assertAll(
          () -> assertEquals(1, changed, "the one annotation is described once"),
          () -> assertEquals("Link to https://example.com/", annotation.getContents()));
    }
  }

  @Test
  void headingLevelsCountsASharedHeadingOnce() throws Exception {
    COSDictionary heading = element("H2");
    try (PDDocument doc = docWithTree(diamondChain(heading))) {
      HeadingLevels.Outcome outcome = assertTimeoutPreemptively(BOUND, () -> HeadingLevels.renumber(doc));
      assertEquals(new HeadingLevels.Outcome(1, "H2→H1 ×1"), outcome);
      assertEquals("H1", heading.getNameAsString(COSName.S));
    }
  }

  @Test
  void setParentTreeValueWalksASelfListingKidsArrayOnce() throws Exception {
    COSDictionary root = new COSDictionary();
    COSDictionary leaf = new COSDictionary();
    leaf.setItem(COSName.NUMS, new COSArray(List.of(COSInteger.get(3), new COSDictionary())));
    root.setItem(COSName.KIDS, new COSArray(List.of(root, root, leaf)));

    boolean missing = assertTimeoutPreemptively(BOUND, () -> StructTree.setParentTreeValue(root, 7, new COSDictionary()));
    assertFalse(missing, "the key is not in the tree");
  }
}
