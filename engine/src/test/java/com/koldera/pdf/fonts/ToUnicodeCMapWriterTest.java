package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;

class ToUnicodeCMapWriterTest {

  private static SortedMap<Integer, String> oneByte(int n) {
    SortedMap<Integer, String> m = new TreeMap<>();
    for (int code = 0; code < n; code++) {
      m.put(code, String.valueOf((char) ('A' + code % 26)));
    }
    return m;
  }

  @Test
  void writesSortedBfcharBlocksOfAtMostOneHundred() {
    String text = new String(ToUnicodeCMapWriter.write(1, oneByte(150)), US_ASCII);
    List<String> blocks = text.lines().filter(l -> l.endsWith("beginbfchar")).toList();
    assertEquals(List.of("100 beginbfchar", "50 beginbfchar"), blocks, text);
    int first = text.indexOf("100 beginbfchar\n") + "100 beginbfchar\n".length();
    assertEquals("<00> <0041>", text.substring(first, text.indexOf('\n', first)));
    assertTrue(text.contains("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >>"), text);
    assertTrue(text.contains("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"), text);
    assertTrue(text.contains("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n"), text);
    assertTrue(text.indexOf("<95>") > text.indexOf("<63>"), "code order");
    assertTrue(!text.contains("\r"), "LF only");
  }

  @Test
  void writesSurrogatePairsAsUtf16() {
    SortedMap<Integer, String> m = new TreeMap<>();
    m.put(0x0001, "😀");
    String text = new String(ToUnicodeCMapWriter.write(2, m), US_ASCII);
    assertTrue(text.contains("\n<0001> <D83DDE00>\n"), text);
    assertTrue(text.contains("<0000> <FFFF>"), text);
  }

  @Test
  void isDeterministic() {
    SortedMap<Integer, String> m = oneByte(120);
    m.put(200, "ff");
    assertArrayEquals(ToUnicodeCMapWriter.write(1, m), ToUnicodeCMapWriter.write(1, m));
  }

  @Test
  void parsesBackWithPdfbox() throws IOException {
    SortedMap<Integer, String> one = oneByte(150);
    one.put(0xE9, "é");
    one.put(0xF0, "ffi");
    CMap parsed = new CMapParser().parse(new RandomAccessReadBuffer(ToUnicodeCMapWriter.write(1, one)));
    for (var e : one.entrySet()) {
      assertEquals(e.getValue(), parsed.toUnicode(e.getKey(), 1), "code " + e.getKey());
    }
    SortedMap<Integer, String> two = new TreeMap<>();
    two.put(0x0024, "A");
    two.put(0x1234, "😀");
    two.put(0xFFFF, "∑");
    CMap parsedTwo = new CMapParser().parse(new RandomAccessReadBuffer(ToUnicodeCMapWriter.write(2, two)));
    for (var e : two.entrySet()) {
      assertEquals(e.getValue(), parsedTwo.toUnicode(e.getKey(), 2), "code " + e.getKey());
    }
  }
}
