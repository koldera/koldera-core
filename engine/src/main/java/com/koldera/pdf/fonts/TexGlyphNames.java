package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unicode values for glyph names of the Computer Modern and AMS Type 1 fonts that the Adobe Glyph List lacks: the table
 * {@code /fonts/tex-glyph-names.txt} plus size patterns for TeX's big delimiters and operators. Values agree with
 * PDFBox's own lists wherever both know a name; conflicting names are left out (pinned by {@code TexGlyphNamesTest}).
 */
public final class TexGlyphNames {

  static final String RESOURCE = "/fonts/tex-glyph-names.txt";

  private static final SortedMap<String, String> TABLE = load();

  /** Delimiters in four TeX sizes → the base character. */
  private static final Pattern SIZED = Pattern.compile(
      "^(parenleft|parenright|bracketleft|bracketright|floorleft|floorright|ceilingleft|ceilingright|braceleft|braceright|slash|radical)"
          + "(big|Big|bigg|Bigg)$");

  private static final Map<String, String> SIZED_BASE = Map.ofEntries(
      Map.entry("parenleft", "("), Map.entry("parenright", ")"), Map.entry("bracketleft", "["), Map.entry("bracketright", "]"),
      Map.entry("floorleft", "⌊"), Map.entry("floorright", "⌋"), Map.entry("ceilingleft", "⌈"),
      Map.entry("ceilingright", "⌉"), Map.entry("braceleft", "{"), Map.entry("braceright", "}"), Map.entry("slash", "/"),
      Map.entry("radical", "√"));

  /** Large operators in text and display size → the n-ary operator. */
  private static final Pattern OPERATOR = Pattern.compile(
      "^(summation|product|coproduct|integral|contintegral|union|intersection)(text|display)$");

  private static final Map<String, String> OPERATOR_BASE = Map.of(
      "summation", "∑", "product", "∏", "coproduct", "∐", "integral", "∫", "contintegral", "∮",
      "union", "⋃", "intersection", "⋂");

  private TexGlyphNames() {}

  /** The table value for {@code name} (exact name), else a size pattern's base character, else {@code null}. */
  public static String toUnicode(String name) {
    if (name == null) {
      return null;
    }
    String value = TABLE.get(name);
    if (value != null) {
      return value;
    }
    Matcher sized = SIZED.matcher(name);
    if (sized.matches()) {
      return SIZED_BASE.get(sized.group(1));
    }
    Matcher operator = OPERATOR.matcher(name);
    if (operator.matches()) {
      return OPERATOR_BASE.get(operator.group(1));
    }
    return null;
  }

  private static SortedMap<String, String> load() {
    try (InputStream in = TexGlyphNames.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      TreeMap<String, String> table = new TreeMap<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split(";", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isBlank()) {
          throw new IllegalStateException("invalid line in " + RESOURCE + ": " + line);
        }
        StringBuilder value = new StringBuilder();
        for (String hex : parts[1].trim().split(" ")) {
          if (!hex.matches("[0-9A-F]{4,6}")) {
            throw new IllegalStateException("invalid code point in " + RESOURCE + ": " + line);
          }
          value.appendCodePoint(Integer.parseInt(hex, 16));
        }
        if (table.put(parts[0], value.toString()) != null) {
          throw new IllegalStateException("duplicate name in " + RESOURCE + ": " + parts[0]);
        }
      }
      return Collections.unmodifiableSortedMap(table);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
