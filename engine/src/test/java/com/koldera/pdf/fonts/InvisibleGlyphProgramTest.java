package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;

class InvisibleGlyphProgramTest {

  @Test
  void twoGlyphsAndGlyphOneIsEmpty() throws IOException {
    try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(InvisibleGlyphProgram.bytes()))) {
      assertEquals(2, ttf.getNumberOfGlyphs(), "glyph 0 .notdef and glyph 1 the space glyph");
      GlyphData space = ttf.getGlyph().getGlyph(1);
      assertTrue(space == null || space.getDescription().getContourCount() == 0, "glyph 1 has no outline");
      assertTrue(ttf.getName().startsWith("KLDINV+"), ttf.getName());
      for (String hinting : new String[] {"fpgm", "prep", "cvt ", "gasp"}) {
        assertTrue(!ttf.getTableMap().containsKey(hinting), "no hinting table " + hinting + ": the glyphs paint nothing");
      }
    }
  }

  @Test
  void theProgramBytesArePinned() throws Exception {
    // Pinned before the source moved from PDFBox's bundled LiberationSans-Regular.ttf to the project's Liberation 2.1.5 bundle
    // (the two files are byte-identical), so the move is proven not to change a single output byte.
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(InvisibleGlyphProgram.bytes()));
    assertEquals("75c2974a9dd396a5ae0833bbed18c4f570dd77cb77e8d49eb9ae90ff1ac2e53e", digest);
  }

  @Test
  void bytesAreIdenticalOnEveryCall() {
    assertArrayEquals(InvisibleGlyphProgram.bytes(), InvisibleGlyphProgram.bytes());
    assertArrayEquals(InvisibleGlyphProgram.bytes(), InvisibleGlyphProgram.compute(), "a fresh computation gives the same bytes");
  }
}
