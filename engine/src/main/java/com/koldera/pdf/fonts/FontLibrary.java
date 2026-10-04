package com.koldera.pdf.fonts;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/**
 * The bundled Liberation Fonts 2.1.5 (SIL OFL 1.1, Reserved Font Name "Liberation"; files, licence, source and digests in
 * {@code src/main/resources/fonts/liberation/}) and the exact table of font names they stand in for. Liberation Sans is
 * metric-compatible with Arial/Helvetica, Liberation Serif with Times New Roman/Times. Only the names in the table match; no
 * pattern, no case folding, no family guessing (a condensed or black cut of Arial has other widths).
 */
public final class FontLibrary {

  /** The four styles of each bundled family. */
  public enum Style {
    REGULAR,
    BOLD,
    ITALIC,
    BOLD_ITALIC
  }

  /** One bundled substitute: Liberation family, style and the classpath resource of its TTF file. */
  public record Substitute(String family, Style style, String resource) {

    /** For example {@code "Liberation Serif Bold"}; the regular style is the family name alone. */
    public String displayName() {
      return switch (style) {
        case REGULAR -> family;
        case BOLD -> family + " Bold";
        case ITALIC -> family + " Italic";
        case BOLD_ITALIC -> family + " Bold Italic";
      };
    }

    /** The font parsed from the classpath resource, a new instance on every call; the caller closes it. */
    public TrueTypeFont load() throws IOException {
      try (InputStream in = FontLibrary.class.getResourceAsStream(resource)) {
        if (in == null) {
          throw new IOException("bundled font missing from the classpath: " + resource);
        }
        return new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
      }
    }
  }

  static final String SANS = "Liberation Sans";
  static final String SERIF = "Liberation Serif";
  private static final String DIR = "/fonts/liberation/";
  private static final Pattern SUBSET_TAG = Pattern.compile("^[A-Z]{6}\\+");
  private static final Map<String, Substitute> TABLE = table();

  private FontLibrary() {}

  /**
   * The substitute for a {@code /BaseFont} name, after stripping a leading subset tag ({@code [A-Z]{6}+}); empty for every name
   * not in the table.
   */
  public static Optional<Substitute> substituteFor(String baseFont) {
    if (baseFont == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(TABLE.get(SUBSET_TAG.matcher(baseFont).replaceFirst("")));
  }

  /** The classpath resource of a bundled family and style. */
  static String resourceFor(String family, Style style) {
    String file = family.replace(" ", "") + "-" + switch (style) {
      case REGULAR -> "Regular";
      case BOLD -> "Bold";
      case ITALIC -> "Italic";
      case BOLD_ITALIC -> "BoldItalic";
    };
    return DIR + file + ".ttf";
  }

  private static Map<String, Substitute> table() {
    Map<String, Substitute> t = new LinkedHashMap<>();
    put(t, SERIF, Style.REGULAR, "Times-Roman", "TimesNewRomanPSMT", "TimesNewRoman", "TimesNewRomanPS");
    put(t, SERIF, Style.BOLD, "Times-Bold", "TimesNewRomanPS-BoldMT", "TimesNewRoman,Bold", "TimesNewRoman-Bold");
    put(t, SERIF, Style.ITALIC, "Times-Italic", "TimesNewRomanPS-ItalicMT", "TimesNewRoman,Italic", "TimesNewRoman-Italic");
    put(t, SERIF, Style.BOLD_ITALIC, "Times-BoldItalic", "TimesNewRomanPS-BoldItalicMT", "TimesNewRoman,BoldItalic", "TimesNewRoman-BoldItalic");
    put(t, SANS, Style.REGULAR, "Helvetica", "ArialMT", "Arial");
    put(t, SANS, Style.BOLD, "Helvetica-Bold", "Arial-BoldMT", "Arial,Bold", "Arial-Bold");
    put(t, SANS, Style.ITALIC, "Helvetica-Oblique", "Arial-ItalicMT", "Arial,Italic", "Arial-Italic");
    put(t, SANS, Style.BOLD_ITALIC, "Helvetica-BoldOblique", "Arial-BoldItalicMT", "Arial,BoldItalic", "Arial-BoldItalic");
    return Collections.unmodifiableMap(t);
  }

  private static void put(Map<String, Substitute> t, String family, Style style, String... names) {
    Substitute s = new Substitute(family, style, resourceFor(family, style));
    for (String name : names) {
      t.put(name, s);
    }
  }
}
