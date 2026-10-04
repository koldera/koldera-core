package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.junit.jupiter.api.Test;

/** The project TeX glyph table only adds names and never disagrees with PDFBox. */
class TexGlyphNamesTest {

  private static final String[] SIZES = {"big", "Big", "bigg", "Bigg"};

  /** The bases the brief listed for the size and text/display patterns (including the ones left out). */
  private static final String[] SIZE_BASES = {"parenleft", "parenright", "bracketleft", "bracketright", "floorleft", "floorright",
      "ceilingleft", "ceilingright", "braceleft", "braceright", "angbracketleft", "angbracketright", "slash", "backslash", "radical"};
  private static final String[] OPERATOR_BASES = {"summation", "product", "coproduct", "integral", "contintegral", "union", "intersection",
      "unionmulti", "unionsq", "logicaland", "logicalor", "circledot", "circleplus", "circlemultiply"};

  /** A PDFBox glyph list resource ({@code glyphlist.txt} = AGL, {@code additional.txt}) as name → string. */
  static Map<String, String> pdfBoxList(String resource) {
    try (InputStream in = GlyphList.class.getResourceAsStream("/org/apache/pdfbox/resources/glyphlist/" + resource)) {
      assertNotNull(in, resource);
      return parse(in).map();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record Parsed(List<String> names, Map<String, String> map) {}

  private static Parsed parse(InputStream in) throws IOException {
    List<String> names = new ArrayList<>();
    Map<String, String> map = new TreeMap<>();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split(";");
      assertEquals(2, parts.length, line);
      StringBuilder value = new StringBuilder();
      for (String hex : parts[1].trim().split(" ")) {
        assertTrue(hex.matches("[0-9A-F]{4,6}"), line);
        value.appendCodePoint(Integer.parseInt(hex, 16));
      }
      names.add(parts[0]);
      map.put(parts[0], value.toString());
    }
    return new Parsed(names, map);
  }

  private static Parsed projectTable() throws IOException {
    try (InputStream in = TexGlyphNames.class.getResourceAsStream("/fonts/tex-glyph-names.txt")) {
      assertNotNull(in);
      return parse(in);
    }
  }

  @Test
  void theTableIsSortedWithoutDuplicatesAndNoNameIsInTheAgl() throws IOException {
    Parsed table = projectTable();
    assertTrue(table.names().size() > 50);
    for (int i = 1; i < table.names().size(); i++) {
      assertTrue(table.names().get(i - 1).compareTo(table.names().get(i)) < 0, table.names().get(i));
    }
    for (String name : table.names()) {
      assertNull(GlyphList.getAdobeGlyphList().toUnicode(name), name);
      assertEquals(table.map().get(name), TexGlyphNames.toUnicode(name), name);
    }
  }

  /**
   * For every name PDFBox's extraction list (AGL + additional.txt) already maps,
   * the project value equals PDFBox's; names where they differed were left out of the table and
   * the patterns.
   */
  @Test
  void everyTableAndPatternNameThatPdfBoxKnowsHasPdfBoxsValue() throws IOException {
    Map<String, String> pdfBox = new TreeMap<>(pdfBoxList("glyphlist.txt"));
    pdfBox.putAll(pdfBoxList("additional.txt"));
    List<String> candidates = new ArrayList<>(projectTable().names());
    for (String base : SIZE_BASES) {
      for (String size : SIZES) {
        candidates.add(base + size);
      }
    }
    for (String base : OPERATOR_BASES) {
      candidates.add(base + "text");
      candidates.add(base + "display");
    }
    candidates.addAll(pdfBox.keySet());
    for (String name : candidates) {
      String ours = TexGlyphNames.toUnicode(name);
      String theirs = pdfBox.get(name);
      if (ours != null && theirs != null) {
        assertEquals(theirs, ours, name);
      }
    }
  }

  @Test
  void sizePatternsGiveTheBaseCharacter() {
    assertEquals("(", TexGlyphNames.toUnicode("parenleftbig"));
    assertEquals(")", TexGlyphNames.toUnicode("parenrightBigg"));
    assertEquals("{", TexGlyphNames.toUnicode("braceleftBig"));
    assertEquals("⌊", TexGlyphNames.toUnicode("floorleftbigg"));
    assertEquals("⌉", TexGlyphNames.toUnicode("ceilingrightbig"));
    assertEquals("/", TexGlyphNames.toUnicode("slashBig"));
    assertEquals("√", TexGlyphNames.toUnicode("radicalbigg"));
    assertEquals("∮", TexGlyphNames.toUnicode("contintegraldisplay"));
    assertEquals("∐", TexGlyphNames.toUnicode("coproducttext"));
    assertEquals("⋂", TexGlyphNames.toUnicode("intersectiondisplay"));
    assertNull(TexGlyphNames.toUnicode("parenleftBIG"));
    assertNull(TexGlyphNames.toUnicode("radicaltp"));
    assertNull(TexGlyphNames.toUnicode("summation"));
    assertNull(TexGlyphNames.toUnicode(""));
  }

  /** Left out because PDFBox's additional.txt resolves them to a different character (never disagree). */
  @Test
  void namesPdfBoxResolvesDifferentlyAreNotInTheProjectSources() {
    for (String name : new String[] {"angbracketleftbig", "angbracketrightBigg", "backslashbig", "circledottext", "circleplusdisplay",
        "circlemultiplytext", "logicalandtext", "logicalordisplay", "unionmultitext", "unionsqdisplay", "angbracketleft", "angbracketright",
        "bardbl", "circlecopyrt", "epsilon1", "radicalbt", "radicalvertex", "tildewide", "tildewider", "tildewidest"}) {
      assertNull(TexGlyphNames.toUnicode(name), name);
    }
  }
}
