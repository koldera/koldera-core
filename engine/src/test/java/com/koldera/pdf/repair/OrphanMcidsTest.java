package com.koldera.pdf.repair;

import static com.koldera.pdf.repair.ContentFixtures.normalize;
import static com.koldera.pdf.repair.ContentFixtures.page;
import static com.koldera.pdf.repair.ContentFixtures.paragraphKids;
import static com.koldera.pdf.repair.ContentFixtures.tokens;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/** Marked content with an MCID that no ParentTree slot references ("stray MCIDs outside the tree"). */
class OrphanMcidsTest {

  private static final String TAGGED = "/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Tagged) Tj ET EMC";

  @Test
  void anUnreferencedPaintingSequenceBecomesAnArtifact() throws Exception {
    byte[] input = page(TAGGED + " /Figure <</MCID 1>> BDC 72 690 m 300 690 l S EMC /Artifact <</MCID 2 /Type /Pagination>> BDC 72 650 m 300 650 l S EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      assertEquals(new OrphanMcids.Outcome(2, 0), OrphanMcids.repair(doc));

      assertEquals(normalize(TAGGED + " /Artifact BMC 72 690 m 300 690 l S EMC /Artifact <</Type /Pagination>> BDC 72 650 m 300 650 l S EMC"), tokens(doc.getPage(0)));
      assertEquals("0", paragraphKids(doc), "the tree is unchanged");
    }
  }

  @Test
  void anUnreferencedTextSequenceIsLeft() throws Exception {
    byte[] input = page(TAGGED + " /Span <</MCID 1>> BDC BT /F1 12 Tf 72 650 Td (Stray) Tj ET EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();

      assertEquals(new OrphanMcids.Outcome(0, 1), OrphanMcids.repair(doc));

      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes(), "no guessed attachment, nothing rewritten");
    }
  }

  @Test
  void aPageWhoseParentTreeEntryCannotBeReadIsLeftAlone() throws Exception {
    byte[] input = page(TAGGED + " /Figure <</MCID 1>> BDC 72 690 m 300 690 l S EMC", 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      doc.getPage(0).getCOSObject().setInt(org.apache.pdfbox.cos.COSName.STRUCT_PARENTS, 7); // no such ParentTree key
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new OrphanMcids.Outcome(0, 0), OrphanMcids.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes(), "fail closed: nothing is an orphan on a guess");
    }
  }

  @Test
  void referencedSequencesAreNotTouched() throws Exception {
    byte[] input = page(TAGGED, 0);
    try (PDDocument doc = Loader.loadPDF(input)) {
      byte[] before = doc.getPage(0).getContents().readAllBytes();
      assertEquals(new OrphanMcids.Outcome(0, 0), OrphanMcids.repair(doc));
      assertArrayEquals(before, doc.getPage(0).getContents().readAllBytes());
    }
  }
}
