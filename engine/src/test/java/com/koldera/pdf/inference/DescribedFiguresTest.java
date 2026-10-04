package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.DescribedImages;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.junit.jupiter.api.Test;

/**
 * The exact-mapping checks of {@link DescribedFigures#carry}, on a "rebuilt" tree simulated in place:
 * the fixture's tree is read, then its elements are changed the way a rebuild could group the images.
 */
class DescribedFiguresTest {

  @Test
  void aRebuiltFigureOverTwoDifferentlyDescribedImagesIsRefusedAndNothingIsWritten() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(DescribedImages.figure("Left chart"), DescribedImages.figure("Right chart")), null))) {
      DescribedFigures.Snapshot snapshot = DescribedFigures.read(doc);
      COSDictionary merged = mergeFigures(doc);

      assertThrows(DescribedFigures.Refused.class, () -> DescribedFigures.carry(doc, snapshot));

      assertFalse(merged.containsKey(COSName.ALT), "nothing is written on a refusal");
    }
  }

  /** Exact 1:1: two described Figures merged into one are refused even with the same text. */
  @Test
  void aRebuiltFigureOverTwoIdenticallyDescribedImagesIsRefused() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(DescribedImages.figure("Chart"), DescribedImages.figure("Chart")), null))) {
      DescribedFigures.Snapshot snapshot = DescribedFigures.read(doc);
      COSDictionary merged = mergeFigures(doc);

      assertThrows(DescribedFigures.Refused.class, () -> DescribedFigures.carry(doc, snapshot));
      assertFalse(merged.containsKey(COSName.ALT), "nothing is written on a refusal");
    }
  }

  @Test
  void aDescribedImageThatEndsUpInAParagraphIsRefused() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(List.of(DescribedImages.figure("Chart")), null))) {
      DescribedFigures.Snapshot snapshot = DescribedFigures.read(doc);
      PDStructureElement figure = element(doc, 1);
      figure.getCOSObject().removeItem(COSName.ALT);
      figure.getCOSObject().setName(COSName.S, "P");

      assertThrows(DescribedFigures.Refused.class, () -> DescribedFigures.carry(doc, snapshot));
      assertFalse(figure.getCOSObject().containsKey(COSName.ALT));
    }
  }

  @Test
  void aRebuiltFigureThatAlsoCoversAnUndescribedImageIsRefused() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(DescribedImages.figure("Chart"), new DescribedImages("Figure", null, null, null, 1, false)), null))) {
      DescribedFigures.Snapshot snapshot = DescribedFigures.read(doc);
      mergeFigures(doc);

      assertThrows(DescribedFigures.Refused.class, () -> DescribedFigures.carry(doc, snapshot));
    }
  }

  @Test
  void aDocumentWithoutDescriptionsHasAnEmptySnapshot() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.taggedWithUntaggedLines(1, 9))) {
      DescribedFigures.Snapshot snapshot = DescribedFigures.read(doc);

      assertTrue(snapshot.isEmpty());
      assertEquals(0, DescribedFigures.carry(doc, snapshot));
    }
  }

  /** The Document's kid {@code index} (0 is the P line, then the fixture's described elements). */
  private static PDStructureElement element(PDDocument doc, int index) {
    PDStructureElement document = (PDStructureElement) doc.getDocumentCatalog().getStructureTreeRoot().getKids().get(0);
    return (PDStructureElement) document.getKids().get(index);
  }

  /** Simulates a rebuild that put both images under the first Figure (MCIDs 1 and 2), descriptions removed; returns that Figure. */
  private static COSDictionary mergeFigures(PDDocument doc) {
    COSDictionary first = element(doc, 1).getCOSObject();
    COSDictionary second = element(doc, 2).getCOSObject();
    COSArray both = new COSArray();
    both.add(COSInteger.get(1));
    both.add(COSInteger.get(2));
    first.setItem(COSName.K, both);
    first.removeItem(COSName.ALT);
    second.removeItem(COSName.K);
    second.removeItem(COSName.ALT);
    return first;
  }
}
