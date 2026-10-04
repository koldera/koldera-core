package com.koldera.pdf.fonts;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.fontbox.ttf.WGL4Names;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

/**
 * The standard Macintosh glyph order (the 258 names of TrueType {@code post} format 1) as a Unicode
 * source for symbolic TrueType fonts without names. Used only when two independent
 * facts agree for <em>every</em> used code of a font: the code's WinAnsi name equals the standard
 * Macintosh name at the code's glyph id, in a font that keeps at least 258 glyphs.
 */
public final class MacGlyphOrder {

  private MacGlyphOrder() {}

  /**
   * code → Unicode when every entry of {@code codeToGid} agrees ({@code 1 <= gid < 258},
   * {@link WGL4Names#getGlyphName} of the gid equals {@link WinAnsiEncoding}'s name of the code, and
   * the AGL value of that name is valid) and {@code numberOfGlyphs >= 258}; empty otherwise, also
   * for an empty {@code codeToGid} (no evidence).
   */
  public static Optional<Map<Integer, String>> mapping(SortedMap<Integer, Integer> codeToGid, int numberOfGlyphs) {
    if (numberOfGlyphs < WGL4Names.NUMBER_OF_MAC_GLYPHS || codeToGid.isEmpty()) {
      return Optional.empty();
    }
    TreeMap<Integer, String> result = new TreeMap<>();
    for (Map.Entry<Integer, Integer> entry : codeToGid.entrySet()) {
      int code = entry.getKey();
      Integer gid = entry.getValue();
      if (gid == null || gid < 1 || gid >= WGL4Names.NUMBER_OF_MAC_GLYPHS) {
        return Optional.empty();
      }
      String macName = WGL4Names.getGlyphName(gid);
      String winAnsiName = WinAnsiEncoding.INSTANCE.getName(code);
      if (macName == null || !macName.equals(winAnsiName)) {
        return Optional.empty();
      }
      String unicode = GlyphList.getAdobeGlyphList().toUnicode(macName);
      if (!GlyphAudit.valid(unicode)) {
        return Optional.empty();
      }
      result.put(code, unicode);
    }
    return Optional.of(Collections.unmodifiableMap(result));
  }
}
