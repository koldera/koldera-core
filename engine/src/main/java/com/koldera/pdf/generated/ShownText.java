package com.koldera.pdf.generated;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * The Unicode text one text-showing operator ({@code Tj TJ ' "}) paints, decoded with the current font's own mapping.
 * Nothing is guessed: a {@code TJ} adjustment is not turned into a space and one unmapped glyph makes the operator undecodable.
 */
public final class ShownText {

  private ShownText() {}

  /**
   * @param fontName the operand of the last {@code Tf} in effect, or null
   * @param operands the operator's operands (the string, or the {@code TJ} array, is the last one)
   * @return the painted text, or null when the font is unknown or any glyph has no Unicode mapping
   */
  public static String decode(PDResources resources, COSName fontName, List<COSBase> operands) {
    if (resources == null || fontName == null || operands.isEmpty()) {
      return null;
    }
    try {
      PDFont font = resources.getFont(fontName);
      if (font == null) {
        return null;
      }
      StringBuilder out = new StringBuilder();
      COSBase shown = operands.get(operands.size() - 1);
      if (shown instanceof COSString string) {
        return append(font, string, out) ? out.toString() : null;
      }
      if (shown instanceof COSArray array) {
        for (COSBase element : array) {
          if (element instanceof COSString string && !append(font, string, out)) {
            return null;
          }
        }
        return out.toString();
      }
      return null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static boolean append(PDFont font, COSString string, StringBuilder out) throws IOException {
    try (InputStream in = new ByteArrayInputStream(string.getBytes())) {
      while (in.available() > 0) {
        String unicode = font.toUnicode(font.readCode(in));
        if (unicode == null) {
          return false;
        }
        out.append(unicode);
      }
    }
    return true;
  }
}
