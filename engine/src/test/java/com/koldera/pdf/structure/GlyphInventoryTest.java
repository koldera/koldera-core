package com.koldera.pdf.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

/** The glyph inventory ignores tags and state but not fonts or strings. */
class GlyphInventoryTest {

  @Test
  void taggingLeavesTheInventoryUnchangedIncludingFormXObjects() throws IOException {
    for (byte[] original : new byte[][] {SyntheticPdfFactory.untaggedArticle(), SyntheticPdfFactory.untaggedWithFormXObjects(), SyntheticPdfFactory.untaggedWithRunningHeader(4)}) {
      String before;
      byte[] tagged;
      try (PDDocument doc = Loader.loadPDF(original)) {
        before = GlyphInventory.digest(doc);
        ContentTagger.Plan plan;
        try {
          plan = ContentTagger.analyze(doc);
        } catch (ContentTagger.UnsupportedContentException e) {
          throw new AssertionError(e);
        }
        ContentTagger.apply(doc, plan);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        tagged = out.toByteArray();
      }
      try (PDDocument doc = Loader.loadPDF(tagged)) {
        assertEquals(before, GlyphInventory.digest(doc));
      }
    }
  }

  @Test
  void theFontDictionaryTreeIsPartOfTheInventory() throws IOException {
    byte[] base = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "alpha")));
    String plain;
    try (PDDocument doc = Loader.loadPDF(base)) {
      plain = GlyphInventory.digest(doc);
    }
    // A Differences array and a Widths array change the inventory (a ToUnicode stream no longer
    // does: it selects and paints no glyph; see aWrittenToUnicodeDoesNotChangeTheInventory).
    String differences;
    try (PDDocument doc = Loader.loadPDF(base)) {
      COSDictionary font = firstFont(doc);
      COSDictionary encoding = new COSDictionary();
      encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
      COSArray array = new COSArray();
      array.add(COSInteger.get(65));
      array.add(COSName.getPDFName("bullet"));
      encoding.setItem(COSName.DIFFERENCES, array);
      font.setItem(COSName.ENCODING, encoding);
      differences = GlyphInventory.digest(doc);
    }
    assertNotEquals(plain, differences);
    String widths;
    try (PDDocument doc = Loader.loadPDF(base)) {
      COSDictionary font = firstFont(doc);
      COSArray array = new COSArray();
      array.add(COSInteger.get(500));
      font.setItem(COSName.WIDTHS, array);
      widths = GlyphInventory.digest(doc);
    }
    assertNotEquals(plain, widths);
  }

  @Test
  void aWrittenToUnicodeDoesNotChangeTheInventory() throws IOException {
    Map<Integer, String> differences = Map.of(66, "summationtext");
    byte[] codes = "AB".getBytes(StandardCharsets.US_ASCII);
    byte[] without = SyntheticPdfFactory.untaggedType1WithDifferences(differences, codes, null);
    byte[] with = SyntheticPdfFactory.untaggedType1WithDifferences(differences, codes,
        "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n"
            + "1 beginbfchar\n<42> <2211>\nendbfchar\nendcmap\nend\nend\n");
    try (PDDocument a = Loader.loadPDF(without); PDDocument b = Loader.loadPDF(with)) {
      assertNotNull(firstFont(b).getDictionaryObject(COSName.TO_UNICODE), "precondition");
      assertEquals(GlyphInventory.digest(a), GlyphInventory.digest(b));
    }
  }

  @Test
  void removingCidSetAndCharSetDoesNotChangeTheInventory() throws IOException {
    byte[] subset = type0Document(true);
    String before;
    try (PDDocument doc = Loader.loadPDF(subset)) {
      before = GlyphInventory.digest(doc);
    }
    try (PDDocument doc = Loader.loadPDF(subset)) {
      COSDictionary descriptor = descendant(doc).getCOSDictionary(COSName.FONT_DESC);
      assertNotNull(descriptor.getDictionaryObject(COSName.CID_SET), "precondition: the subset carries a CIDSet");
      descriptor.removeItem(COSName.CID_SET);
      descriptor.removeItem(COSName.CHAR_SET);
      assertEquals(before, GlyphInventory.digest(doc));
    }
  }

  @Test
  void anExplicitIdentityCidToGidMapEqualsAMissingOne() throws IOException {
    byte[] full = type0Document(false);
    try (PDDocument explicit = Loader.loadPDF(full); PDDocument missing = Loader.loadPDF(full)) {
      assertEquals(COSName.IDENTITY, descendant(explicit).getDictionaryObject(COSName.CID_TO_GID_MAP), "precondition");
      descendant(missing).removeItem(COSName.CID_TO_GID_MAP);
      assertEquals(GlyphInventory.digest(explicit), GlyphInventory.digest(missing));
    }
  }

  @Test
  void aStreamCidToGidMapIsStillHashed() throws IOException {
    byte[] full = type0Document(false);
    try (PDDocument identity = Loader.loadPDF(full); PDDocument stream = Loader.loadPDF(full)) {
      PDStream map = new PDStream(stream);
      try (OutputStream out = map.createOutputStream()) {
        out.write(new byte[] {0, 0, 0, 36, 0, 37});
      }
      descendant(stream).setItem(COSName.CID_TO_GID_MAP, map);
      assertNotEquals(GlyphInventory.digest(identity), GlyphInventory.digest(stream));
    }
  }

  /** One page showing "AB" in LiberationSans as a Type 0 / CIDFontType2 font, subset or fully embedded. */
  private static byte[] type0Document(boolean subset) throws IOException {
    try (PDDocument doc = new PDDocument();
        InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      PDType0Font font = PDType0Font.load(doc, ttf, subset);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(72, 700);
        cs.showText("AB");
        cs.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static COSDictionary descendant(PDDocument doc) {
    COSArray descendants = (COSArray) firstFont(doc).getDictionaryObject(COSName.DESCENDANT_FONTS);
    return (COSDictionary) descendants.getObject(0);
  }

  private static COSDictionary firstFont(PDDocument doc) {
    var resources = doc.getPage(0).getResources();
    COSName name = resources.getFontNames().iterator().next();
    return (COSDictionary) resources.getCOSObject().getCOSDictionary(COSName.FONT).getDictionaryObject(name);
  }

  @Test
  void aChangedStringOrFontChangesTheInventory() throws IOException {
    byte[] one = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "alpha"), Block.of("P", "beta")));
    byte[] two = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "alpha"), Block.of("P", "gamma")));
    byte[] reordered = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("P", "alpha"), Block.of("P", "beta")).structureOrder(1, 0));
    try (PDDocument a = Loader.loadPDF(one); PDDocument b = Loader.loadPDF(two); PDDocument c = Loader.loadPDF(reordered)) {
      assertNotEquals(GlyphInventory.digest(a), GlyphInventory.digest(b));
      assertEquals(GlyphInventory.digest(a), GlyphInventory.digest(c), "a structure-only change is invisible to the inventory");
    }
  }
}
