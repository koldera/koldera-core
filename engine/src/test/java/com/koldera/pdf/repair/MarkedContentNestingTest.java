package com.koldera.pdf.repair;

import static com.koldera.pdf.repair.ContentFixtures.normalize;
import static com.koldera.pdf.repair.ContentFixtures.page;
import static com.koldera.pdf.repair.ContentFixtures.paragraphKids;
import static com.koldera.pdf.repair.ContentFixtures.parentTreeTypes;
import static com.koldera.pdf.repair.ContentFixtures.tokens;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** veraPDF 7.1-1 / 7.1-2: an artifact inside tagged content, tagged content inside an artifact. */
class MarkedContentNestingTest {

  private static final String BEFORE = "BT /F1 12 Tf 72 700 Td (Before) Tj ET";
  private static final String AFTER = "BT /F1 12 Tf 72 670 Td (After) Tj ET";
  private static final String RULE = "72 690 m 300 690 l S";
  private static final String RULE2 = "72 650 m 300 650 l S";

  @Test
  void anArtifactInsideTaggedContentIsSplitOut() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " /Artifact BMC " + RULE + " EMC " + AFTER + " EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      MarkedContentNesting.Outcome outcome = MarkedContentNesting.repair(doc);

      assertEquals(new MarkedContentNesting.Outcome(1, 0, 0, 0), outcome);
      assertEquals(normalize("/P <</MCID 0>> BDC " + BEFORE + " EMC /Artifact BMC " + RULE + " EMC /P <</MCID 1>> BDC " + AFTER + " EMC"), tokens(doc.getPage(0)));
      assertEquals("[0 1]", paragraphKids(doc));
      assertEquals(Arrays.asList("P", "P"), parentTreeTypes(doc));
    }
  }

  @Test
  void anArtifactAtTheStartOfTaggedContentLeavesNoEmptySequence() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC /Artifact BMC " + RULE + " EMC " + AFTER + " EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      assertEquals(new MarkedContentNesting.Outcome(1, 0, 0, 0), MarkedContentNesting.repair(doc));
      assertEquals(normalize("/Artifact BMC " + RULE + " EMC /P <</MCID 0>> BDC " + AFTER + " EMC"), tokens(doc.getPage(0)));
      assertEquals("0", paragraphKids(doc), "no new MCID");
      assertEquals(List.of("P"), parentTreeTypes(doc));
    }
  }

  @Test
  void taggedContentInsideAnArtifactIsSplitOut() throws Exception {
    byte[] input = page("/Artifact <</Type /Pagination>> BDC " + RULE + " /P <</MCID 0>> BDC " + BEFORE + " EMC " + RULE2 + " EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      assertEquals(new MarkedContentNesting.Outcome(0, 1, 0, 0), MarkedContentNesting.repair(doc));
      assertEquals(normalize("/Artifact <</Type /Pagination>> BDC " + RULE + " EMC /P <</MCID 0>> BDC " + BEFORE
          + " EMC /Artifact <</Type /Pagination>> BDC " + RULE2 + " EMC"), tokens(doc.getPage(0)));
      assertEquals("0", paragraphKids(doc), "the tree is unchanged");
      assertEquals(List.of("P"), parentTreeTypes(doc));
    }
  }

  @Test
  void anUnreferencedMcidInsideAnArtifactLosesItsMarker() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " EMC /Artifact BMC " + RULE + " /Span <</MCID 1>> BDC " + RULE2 + " EMC EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      assertEquals(new MarkedContentNesting.Outcome(0, 0, 0, 1), MarkedContentNesting.repair(doc));
      assertEquals(normalize("/P <</MCID 0>> BDC " + BEFORE + " EMC /Artifact BMC " + RULE + " " + RULE2 + " EMC"), tokens(doc.getPage(0)));
      assertEquals("0", paragraphKids(doc));
    }
  }

  @Test
  void aSplitThatWouldCrossATextObjectIsNotMade() throws Exception {
    String content = "/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Before) Tj /Artifact BMC (Mark) Tj EMC (After) Tj ET EMC";
    byte[] input = page(content, 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();

      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));

      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes(), "stream bytes unchanged");
      assertEquals("0", paragraphKids(doc));
    }
  }

  @Test
  void aHostHoldingMoreThanTheArtifactIsLeftAlone() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " /Artifact BMC " + RULE + " EMC /Artifact BMC " + RULE2 + " EMC " + AFTER + " EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }

  @Test
  void twoSplitsInOneElementKeepTheReadingOrder() throws Exception {
    String second = " BT /F1 12 Tf 72 640 Td (Before 2) Tj ET /Artifact BMC " + RULE2 + " EMC BT /F1 12 Tf 72 620 Td (After 2) Tj ET";
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " /Artifact BMC " + RULE + " EMC " + AFTER + " EMC /P <</MCID 1>> BDC" + second + " EMC", 0, 1);
    try (PDDocument doc = Loader.loadPDF(input)) {
      assertEquals(new MarkedContentNesting.Outcome(2, 0, 0, 0), MarkedContentNesting.repair(doc));
      assertEquals("[0 2 1 3]", paragraphKids(doc), "each new MCID right after its own host");
      assertEquals(Arrays.asList("P", "P", "P", "P"), parentTreeTypes(doc));
      assertEquals(normalize("/P <</MCID 0>> BDC " + BEFORE + " EMC /Artifact BMC " + RULE + " EMC /P <</MCID 2>> BDC " + AFTER
          + " EMC /P <</MCID 1>> BDC BT /F1 12 Tf 72 640 Td (Before 2) Tj ET EMC /Artifact BMC " + RULE2
          + " EMC /P <</MCID 3>> BDC BT /F1 12 Tf 72 620 Td (After 2) Tj ET EMC"), tokens(doc.getPage(0)));
    }
  }

  @Test
  void aSplitThatWouldCrossAGraphicsStateSaveIsNotMade() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC q " + BEFORE + " /Artifact BMC " + RULE + " EMC Q " + AFTER + " EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }

  @Test
  void anArtifactHoldingOnlyTaggedContentLosesItsMarkers() throws Exception {
    byte[] input = page("/Artifact BMC /P <</MCID 0>> BDC " + BEFORE + " EMC EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      assertEquals(new MarkedContentNesting.Outcome(0, 1, 0, 0), MarkedContentNesting.repair(doc));
      assertEquals(normalize("/P <</MCID 0>> BDC " + BEFORE + " EMC"), tokens(doc.getPage(0)));
      assertEquals("0", paragraphKids(doc));
    }
  }

  @Test
  void anUnbalancedStreamWithNestingIsCountedAndLeftAlone() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " /Artifact BMC " + RULE + " EMC " + AFTER, 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }

  @Test
  void aPageWhoseParentTreeEntryCannotBeReadIsLeftAlone() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " EMC /Artifact BMC " + RULE + " /Span <</MCID 1>> BDC " + RULE2 + " EMC EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      doc.getPage(0).getCOSObject().setInt(org.apache.pdfbox.cos.COSName.STRUCT_PARENTS, 7); // no such ParentTree key
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }

  private static final String MIDDLE_SPLIT = "/P <</MCID 0>> BDC " + BEFORE + " /Artifact BMC " + RULE + " EMC " + AFTER + " EMC";

  /** The fixture's P with its /K replaced by {@code k} (built against the loaded document). */
  private static void setKids(PDDocument doc, org.apache.pdfbox.cos.COSBase k) {
    ContentFixtures.paragraph(doc).getCOSObject().setItem(org.apache.pdfbox.cos.COSName.K, k);
  }

  private static org.apache.pdfbox.cos.COSDictionary mcr(int mcid, org.apache.pdfbox.cos.COSDictionary page) {
    org.apache.pdfbox.cos.COSDictionary mcr = new org.apache.pdfbox.cos.COSDictionary();
    mcr.setName(org.apache.pdfbox.cos.COSName.TYPE, "MCR");
    mcr.setInt(org.apache.pdfbox.cos.COSName.MCID, mcid);
    if (page != null) {
      mcr.setItem(org.apache.pdfbox.cos.COSName.PG, page);
    }
    return mcr;
  }

  @Test
  void aMiddleSplitOfAnMcrListedMcidAddsAnMcrForTheSamePage() throws Exception {
    try (PDDocument doc = Loader.loadPDF(page(MIDDLE_SPLIT, 0))) {
      setKids(doc, mcr(0, doc.getPage(0).getCOSObject()));

      assertEquals(new MarkedContentNesting.Outcome(1, 0, 0, 0), MarkedContentNesting.repair(doc));

      org.apache.pdfbox.cos.COSArray k = (org.apache.pdfbox.cos.COSArray) ContentFixtures.paragraph(doc).getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.K);
      assertEquals(2, k.size());
      org.apache.pdfbox.cos.COSDictionary added = (org.apache.pdfbox.cos.COSDictionary) k.getObject(1);
      assertEquals("MCR", added.getNameAsString(org.apache.pdfbox.cos.COSName.TYPE));
      assertEquals(1, added.getInt(org.apache.pdfbox.cos.COSName.MCID));
      assertEquals(doc.getPage(0).getCOSObject(), added.getDictionaryObject(org.apache.pdfbox.cos.COSName.PG), "the same /Pg");
      assertEquals(Arrays.asList("P", "P"), parentTreeTypes(doc));
    }
  }

  @Test
  void anMcrOfAnotherPageIsNotMatchedAndTheStreamIsLeftAlone() throws Exception {
    try (PDDocument doc = Loader.loadPDF(page(MIDDLE_SPLIT, 0))) {
      org.apache.pdfbox.pdmodel.PDPage other = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(other);
      setKids(doc, mcr(0, other.getCOSObject()));
      byte[] before = doc.getPage(0).getContents().readAllBytes();

      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }

  @Test
  void anMcrIntoAFormStreamIsNotMatchedAndTheStreamIsLeftAlone() throws Exception {
    try (PDDocument doc = Loader.loadPDF(page(MIDDLE_SPLIT, 0))) {
      org.apache.pdfbox.cos.COSDictionary withStream = mcr(0, doc.getPage(0).getCOSObject());
      withStream.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Stm"), new org.apache.pdfbox.cos.COSStream());
      setKids(doc, withStream);
      byte[] before = doc.getPage(0).getContents().readAllBytes();

      assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }

  @Test
  void anElementListingTheHostMcidTwiceIsNotSplit() throws Exception {
    for (boolean asMcr : new boolean[] {false, true}) {
      try (PDDocument doc = Loader.loadPDF(page(MIDDLE_SPLIT, 0))) {
        org.apache.pdfbox.cos.COSArray k = new org.apache.pdfbox.cos.COSArray();
        k.add(org.apache.pdfbox.cos.COSInteger.ZERO);
        k.add(asMcr ? mcr(0, doc.getPage(0).getCOSObject()) : org.apache.pdfbox.cos.COSInteger.ZERO);
        setKids(doc, k);
        byte[] before = doc.getPage(0).getContents().readAllBytes();

        assertEquals(new MarkedContentNesting.Outcome(0, 0, 1, 0), MarkedContentNesting.repair(doc), "duplicate as MCR: " + asMcr);
        assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
        assertEquals(2, k.size(), "the tree is unchanged");
      }
    }
  }

  @Test
  void aStreamWithoutNestingIsNotRewritten() throws Exception {
    byte[] input = page("/P <</MCID 0>> BDC " + BEFORE + " EMC /Artifact BMC " + RULE + " EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new MarkedContentNesting.Outcome(0, 0, 0, 0), MarkedContentNesting.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }
}
