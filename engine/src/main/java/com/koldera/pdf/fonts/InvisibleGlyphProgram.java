package com.koldera.pdf.fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;

/**
 * The TrueType program {@link InvisibleTextFontRule} gives an invisible OCR font: a subset of the bundled Liberation Sans
 * Regular ({@link FontLibrary}, SIL OFL 1.1) with only U+0020, name prefix {@code KLDINV+}: glyph 0 {@code .notdef} and an
 * outline-free space glyph. Deterministic: the same bytes on every call. Hinting tables are left out.
 */
public final class InvisibleGlyphProgram {

  static final String PREFIX = "KLDINV+";
  /** The tables written: no hinting ({@code fpgm}, {@code prep}, {@code cvt }, {@code gasp}), the glyphs paint nothing. */
  private static final List<String> TABLES = List.of("head", "hhea", "maxp", "hmtx", "loca", "glyf", "cmap", "post", "name", "OS/2");
  private static final byte[] PROGRAM = compute();

  private InvisibleGlyphProgram() {}

  /** The program's bytes (a copy). */
  public static byte[] bytes() {
    return PROGRAM.clone();
  }

  /** A fresh computation (test seam for determinism). */
  static byte[] compute() {
    try (TrueTypeFont ttf = FontLibrary.substituteFor("Helvetica").orElseThrow().load(); // Liberation Sans Regular
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      TTFSubsetter subsetter = new TTFSubsetter(ttf, TABLES);
      subsetter.setPrefix(PREFIX);
      subsetter.add(0x20);
      subsetter.writeToStream(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
