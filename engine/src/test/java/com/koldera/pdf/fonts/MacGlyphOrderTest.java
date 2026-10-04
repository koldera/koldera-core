package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The standard Macintosh glyph order is used only when it agrees with WinAnsi for every code. */
class MacGlyphOrderTest {

  private static SortedMap<Integer, Integer> codes(int... codeGidPairs) {
    SortedMap<Integer, Integer> map = new TreeMap<>();
    for (int i = 0; i < codeGidPairs.length; i += 2) {
      map.put(codeGidPairs[i], codeGidPairs[i + 1]);
    }
    return map;
  }

  @Test
  void wordStyleAsciiCodesAgree() {
    assertEquals(Optional.of(Map.of(32, " ", 40, "(", 44, ",", 46, ".", 48, "0", 57, "9", 65, "A")),
        MacGlyphOrder.mapping(codes(32, 3, 40, 11, 44, 15, 46, 17, 48, 19, 57, 28, 65, 36), 51_180));
  }

  @Test
  void oneDisagreeingCodeRejectsTheWholeFont() {
    // cs224n AAAACO+CambriaMath: renumbered glyphs
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(33, 25, 34, 9), 300));
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(65, 36, 66, 40), 300));
  }

  @Test
  void aSubsetWithFewerThan258GlyphsIsRejected() {
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(65, 36), 257));
  }

  @Test
  void gidZeroIsRejected() {
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(65, 0), 300));
  }

  @Test
  void gidsOutsideTheStandardOrderAndEmptyInputAreRejected() {
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(65, 258), 300));
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(65, -1), 300));
    assertEquals(Optional.empty(), MacGlyphOrder.mapping(codes(), 300));
  }

  @Test
  void theResultIsUnmodifiable() {
    Map<Integer, String> map = MacGlyphOrder.mapping(codes(65, 36), 300).orElseThrow();
    org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> map.put(66, "B"));
  }
}
