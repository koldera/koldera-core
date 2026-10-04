package com.koldera.pdf.fonts;

import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

/**
 * Glyph name → Unicode by the Adobe Glyph List specification §2 ("Mapping glyph names to Unicode"),
 * with the project TeX table ({@link TexGlyphNames}) as an additional component source.
 */
public final class GlyphNames {

  /**
   * Where a Unicode value came from: {@code EXISTING} = the value PDFBox already extracts,
   * {@code AGL} = AGL specification rules, {@code TEX} = the project TeX table,
   * {@code PROGRAM} = the font program's Unicode cmap, {@code MAC_ORDER} = the standard Macintosh
   * glyph order ({@link MacGlyphOrder}).
   */
  public enum Source { EXISTING, AGL, TEX, PROGRAM, MAC_ORDER }

  public record Resolved(String unicode, Source source) {}

  private static final Pattern UNI = Pattern.compile("uni((?:[0-9A-F]{4})+)");
  private static final Pattern U = Pattern.compile("u([0-9A-F]{4,6})");

  private GlyphNames() {}

  /**
   * AGL rules: drop everything from the first {@code .}; split at {@code _}; every component must be {@code uniXXXX}
   * groups, {@code u} + 4-6 hex digits, an Adobe Glyph List name or a name of the project TeX table.
   * The source is {@link Source#TEX} when any component came from the TeX table.
   */
  public static Optional<Resolved> resolve(String glyphName) {
    if (glyphName == null) {
      return Optional.empty();
    }
    int dot = glyphName.indexOf('.');
    String name = dot >= 0 ? glyphName.substring(0, dot) : glyphName;
    if (name.isEmpty()) {
      return Optional.empty();
    }
    Optional<Resolved> whole = component(name);
    if (whole.isPresent()) {
      return whole;
    }
    StringBuilder unicode = new StringBuilder();
    boolean tex = false;
    for (String part : name.split("_", -1)) {
      Optional<Resolved> resolved = component(part);
      if (resolved.isEmpty()) {
        return Optional.empty();
      }
      unicode.append(resolved.get().unicode());
      tex |= resolved.get().source() == Source.TEX;
    }
    return Optional.of(new Resolved(unicode.toString(), tex ? Source.TEX : Source.AGL));
  }

  private static Optional<Resolved> component(String name) {
    if (name.isEmpty()) {
      return Optional.empty();
    }
    var uni = UNI.matcher(name);
    if (uni.matches()) {
      String hex = uni.group(1);
      StringBuilder value = new StringBuilder();
      for (int i = 0; i < hex.length(); i += 4) {
        int c = Integer.parseInt(hex.substring(i, i + 4), 16);
        if (c >= 0xD800 && c <= 0xDFFF) {
          value = null;
          break;
        }
        value.append((char) c);
      }
      if (value != null) {
        return Optional.of(new Resolved(value.toString(), Source.AGL));
      }
    }
    var u = U.matcher(name);
    if (u.matches()) {
      int c = Integer.parseInt(u.group(1), 16);
      if (c <= 0x10FFFF && (c < 0xD800 || c > 0xDFFF)) {
        return Optional.of(new Resolved(new String(Character.toChars(c)), Source.AGL));
      }
    }
    String agl = GlyphList.getAdobeGlyphList().toUnicode(name);
    if (agl != null && GlyphAudit.valid(agl)) {
      return Optional.of(new Resolved(agl, Source.AGL));
    }
    String tex = TexGlyphNames.toUnicode(name);
    if (tex != null) {
      return Optional.of(new Resolved(tex, Source.TEX));
    }
    return Optional.empty();
  }
}
