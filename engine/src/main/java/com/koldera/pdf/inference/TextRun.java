package com.koldera.pdf.inference;

/**
 * The glyphs one text-showing operator painted, in user space; {@code (stream, ordinal)} joins it to the content walker's
 * item. An operator that painted no glyph has empty {@code text} and {@code NaN} coordinates.
 */
public record TextRun(int ordinal, String text, float x0, float baseline, float x1, float top, float fontSize, boolean bold, String fontName) {

  /** Whether the operator painted at least one glyph (its coordinates are known). */
  public boolean hasGlyphs() {
    return !Float.isNaN(x0);
  }
}
