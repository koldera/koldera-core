package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fontbox.afm.FontMetrics;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FontLibraryTest {

  private static final String DIR = "/fonts/liberation/";

  @Test
  void everyBundledFontMatchesItsPinnedDigest() throws IOException, NoSuchAlgorithmException {
    Map<String, String> pinned = new LinkedHashMap<>();
    for (String line : resourceText("SHA256SUMS").split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.trim().split("\\s+\\*?", 2);
      pinned.put(parts[1], parts[0]);
    }
    assertEquals(8, pinned.size(), "eight TTF files are pinned: " + pinned.keySet());
    for (FontLibrary.Style style : FontLibrary.Style.values()) {
      for (String family : List.of("Liberation Sans", "Liberation Serif")) {
        String file = FontLibrary.resourceFor(family, style).substring(DIR.length());
        assertTrue(pinned.containsKey(file), "SHA256SUMS lists " + file);
      }
    }
    for (Map.Entry<String, String> e : pinned.entrySet()) {
      byte[] bytes = resourceBytes(e.getKey());
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      assertEquals(e.getValue(), digest, e.getKey() + " is byte-identical to the release");
    }
  }

  @Test
  void theLicenceIsTheOfl() throws IOException {
    String licence = resourceText("LICENSE");
    assertTrue(licence.contains("SIL OPEN FONT LICENSE Version 1.1"), "the OFL 1.1 text ships next to the fonts");
    assertTrue(licence.contains("Reserved Font Name Liberation"), "the reserved font name is stated");
    assertTrue(resourceText("SOURCE.txt").contains("2.1.5"), "SOURCE.txt records the release");
  }

  @Test
  void namesPickFamilyAndStyle() {
    assertSubstitute("TimesNewRomanPS-BoldItalicMT", "Liberation Serif", FontLibrary.Style.BOLD_ITALIC);
    assertSubstitute("ABCDEF+Arial,Bold", "Liberation Sans", FontLibrary.Style.BOLD);
    assertSubstitute("Helvetica-Oblique", "Liberation Sans", FontLibrary.Style.ITALIC);
    assertSubstitute("Times-Roman", "Liberation Serif", FontLibrary.Style.REGULAR);
    for (String none : List.of("Calibri", "Courier", "Symbol", "ZapfDingbats", "ArialNarrow", "abcdef+Arial", "ABCDEF+", "")) {
      assertEquals(Optional.empty(), FontLibrary.substituteFor(none), none);
    }
    assertEquals(Optional.empty(), FontLibrary.substituteFor(null));
  }

  @Test
  void theNameTableIsExact() {
    Map<String, String> table = new LinkedHashMap<>();
    serif(table, "REGULAR", "Times-Roman", "TimesNewRomanPSMT", "TimesNewRoman", "TimesNewRomanPS");
    serif(table, "BOLD", "Times-Bold", "TimesNewRomanPS-BoldMT", "TimesNewRoman,Bold", "TimesNewRoman-Bold");
    serif(table, "ITALIC", "Times-Italic", "TimesNewRomanPS-ItalicMT", "TimesNewRoman,Italic", "TimesNewRoman-Italic");
    serif(table, "BOLD_ITALIC", "Times-BoldItalic", "TimesNewRomanPS-BoldItalicMT", "TimesNewRoman,BoldItalic", "TimesNewRoman-BoldItalic");
    sans(table, "REGULAR", "Helvetica", "ArialMT", "Arial");
    sans(table, "BOLD", "Helvetica-Bold", "Arial-BoldMT", "Arial,Bold", "Arial-Bold");
    sans(table, "ITALIC", "Helvetica-Oblique", "Arial-ItalicMT", "Arial,Italic", "Arial-Italic");
    sans(table, "BOLD_ITALIC", "Helvetica-BoldOblique", "Arial-BoldItalicMT", "Arial,BoldItalic", "Arial-BoldItalic");
    for (Map.Entry<String, String> e : table.entrySet()) {
      FontLibrary.Substitute s = FontLibrary.substituteFor(e.getKey()).orElseThrow(() -> new AssertionError(e.getKey()));
      assertEquals(e.getValue(), s.family() + " " + s.style(), e.getKey());
      assertEquals(s, FontLibrary.substituteFor("QWERTY+" + e.getKey()).orElseThrow(), "a subset tag is stripped");
    }
    assertEquals(31, table.size());
    for (String near : List.of("arial", "ARIALMT", "Arial-Black", "Arial,BoldItalicMT", "TimesNewRomanPS-Bold", "Times", "Times New Roman", "Helvetica-Italic", "Helvetica,Bold")) {
      assertEquals(Optional.empty(), FontLibrary.substituteFor(near), near);
    }
  }

  @Test
  void displayNameAndLoad() throws IOException {
    FontLibrary.Substitute bold = FontLibrary.substituteFor("Times-Bold").orElseThrow();
    assertEquals("Liberation Serif Bold", bold.displayName());
    assertEquals("Liberation Sans", FontLibrary.substituteFor("Arial").orElseThrow().displayName());
    assertEquals("Liberation Sans Italic", FontLibrary.substituteFor("Arial-ItalicMT").orElseThrow().displayName());
    assertEquals("Liberation Sans Bold Italic", FontLibrary.substituteFor("Arial-BoldItalic").orElseThrow().displayName());
    assertEquals("/fonts/liberation/LiberationSerif-Bold.ttf", bold.resource());
    try (TrueTypeFont a = bold.load(); TrueTypeFont b = bold.load()) {
      assertEquals("LiberationSerif-Bold", a.getName());
      assertTrue(a != b, "parsed from the classpath on each call");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique", "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic"})
  void theWidthsMatchTheStandardFourteenMetrics(String standard) throws IOException {
    FontMetrics afm = Standard14Fonts.getAFM(standard);
    assertNotNull(afm, standard);
    FontLibrary.Substitute substitute = FontLibrary.substituteFor(standard).orElseThrow();
    List<String> failures = new ArrayList<>();
    int checked = 0;
    try (TrueTypeFont ttf = substitute.load()) {
      float unitsPerEm = ttf.getUnitsPerEm();
      for (int code = 32; code <= 126; code++) {
        String name = WinAnsiEncoding.INSTANCE.getName(code);
        float afmWidth = afm.getCharacterWidth(name);
        if (afmWidth == 0) {
          continue;
        }
        String unicode = GlyphList.getAdobeGlyphList().toUnicode(name);
        int gid = ttf.getUnicodeCmapLookup().getGlyphId(unicode.codePointAt(0));
        assertTrue(gid > 0, standard + " code " + code + " (" + name + ") has a glyph in " + substitute.displayName());
        float width = ttf.getAdvanceWidth(gid) * 1000f / unitsPerEm;
        if (Math.abs(width - afmWidth) > 1.0f) {
          failures.add(code + " " + name + " afm " + afmWidth + " program " + width);
        }
        checked++;
      }
    }
    assertEquals(95, checked, "every printable ASCII code has an AFM width");
    assertEquals(List.of(), failures, standard + " vs " + substitute.displayName());
  }

  private static void assertSubstitute(String baseFont, String family, FontLibrary.Style style) {
    FontLibrary.Substitute s = FontLibrary.substituteFor(baseFont).orElseThrow(() -> new AssertionError(baseFont));
    assertEquals(family, s.family(), baseFont);
    assertEquals(style, s.style(), baseFont);
  }

  private static void serif(Map<String, String> table, String style, String... names) {
    for (String n : names) {
      table.put(n, "Liberation Serif " + style);
    }
  }

  private static void sans(Map<String, String> table, String style, String... names) {
    for (String n : names) {
      table.put(n, "Liberation Sans " + style);
    }
  }

  private static byte[] resourceBytes(String file) throws IOException {
    try (InputStream in = FontLibraryTest.class.getResourceAsStream(DIR + file)) {
      assertNotNull(in, DIR + file + " is on the classpath");
      return in.readAllBytes();
    }
  }

  private static String resourceText(String file) throws IOException {
    return new String(resourceBytes(file), StandardCharsets.US_ASCII);
  }
}
