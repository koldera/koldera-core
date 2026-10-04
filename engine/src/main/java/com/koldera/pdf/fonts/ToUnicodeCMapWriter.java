package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16BE;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * A ToUnicode CMap ({@code /CMapType 2}) as deterministic ASCII bytes: the standard prologue,
 * one codespace range for {@code codeBytes}-byte codes and {@code bfchar} blocks of at most 100
 * entries in code order. No timestamp, {@code \n} line ends.
 */
public final class ToUnicodeCMapWriter {

  static final int BLOCK = 100;

  private ToUnicodeCMapWriter() {}

  /**
   * @param codeBytes 1 (codespace {@code <00> <FF>}) or 2 ({@code <0000> <FFFF>})
   * @param mappings code → Unicode text (UTF-16BE in the CMap, surrogate pairs kept)
   */
  public static byte[] write(int codeBytes, SortedMap<Integer, String> mappings) {
    if (codeBytes != 1 && codeBytes != 2) {
      throw new IllegalArgumentException("codeBytes must be 1 or 2: " + codeBytes);
    }
    StringBuilder b = new StringBuilder();
    b.append("/CIDInit /ProcSet findresource begin\n");
    b.append("12 dict begin\n");
    b.append("begincmap\n");
    b.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
    b.append("/CMapName /Adobe-Identity-UCS def\n");
    b.append("/CMapType 2 def\n");
    b.append("1 begincodespacerange\n");
    b.append(codeBytes == 1 ? "<00> <FF>\n" : "<0000> <FFFF>\n");
    b.append("endcodespacerange\n");
    List<Map.Entry<Integer, String>> entries = new ArrayList<>(mappings.entrySet());
    for (int start = 0; start < entries.size(); start += BLOCK) {
      List<Map.Entry<Integer, String>> block = entries.subList(start, Math.min(entries.size(), start + BLOCK));
      b.append(block.size()).append(" beginbfchar\n");
      for (Map.Entry<Integer, String> e : block) {
        int code = e.getKey();
        if (code < 0 || code >= (1 << (8 * codeBytes))) {
          throw new IllegalArgumentException("code " + code + " does not fit " + codeBytes + " bytes");
        }
        b.append('<').append(String.format(Locale.ROOT, codeBytes == 1 ? "%02X" : "%04X", code)).append("> <")
            .append(HexFormat.of().withUpperCase().formatHex(e.getValue().getBytes(UTF_16BE))).append(">\n");
      }
      b.append("endbfchar\n");
    }
    b.append("endcmap\n");
    b.append("CMapName currentdict /CMap defineresource pop\n");
    b.append("end\n");
    b.append("end\n");
    return b.toString().getBytes(US_ASCII);
  }
}
