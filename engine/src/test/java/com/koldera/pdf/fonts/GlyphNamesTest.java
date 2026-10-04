package com.koldera.pdf.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fonts.GlyphNames.Resolved;
import com.koldera.pdf.fonts.GlyphNames.Source;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** AGL specification §2 name resolution plus the project TeX table. */
class GlyphNamesTest {

  static Stream<Arguments> corpusNames() {
    return Stream.of(
        Arguments.of("f_f", "ff", Source.AGL),
        Arguments.of("f_f_i", "ffi", Source.AGL),
        Arguments.of("grave.ts1", "`", Source.AGL),
        Arguments.of("uni00410042", "AB", Source.AGL),
        Arguments.of("u1F600", "😀", Source.AGL),
        Arguments.of("summationtext", "∑", Source.TEX),
        Arguments.of("summationdisplay", "∑", Source.TEX),
        Arguments.of("bracketleftbigg", "[", Source.TEX),
        Arguments.of("bracketrightbigg", "]", Source.TEX),
        Arguments.of("parenleftbig", "(", Source.TEX),
        Arguments.of("radicalBig", "√", Source.TEX),
        Arguments.of("mapsto", "↦", Source.TEX),
        Arguments.of("trianglerightsld", "▸", Source.TEX),
        Arguments.of("A", "A", Source.AGL),
        Arguments.of("f_summationtext", "f∑", Source.TEX));
  }

  @ParameterizedTest
  @MethodSource("corpusNames")
  void resolvesTheCorpusNames(String name, String unicode, Source source) {
    assertEquals(Optional.of(new Resolved(unicode, source)), GlyphNames.resolve(name), name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"radicaltp", "a0", "g503", "uniD800", "uni004", "uni0041x", "u110000", "f_a0", ".notdef", "", "f_", "_f"})
  void rejectsWhatTheSpecificationRejects(String name) {
    assertEquals(Optional.empty(), GlyphNames.resolve(name), name);
  }

  /**
   * These names are resolved by PDFBox's own extraction list ({@code additional.txt}) to a different character than the TeX values
   * (epsilon1 U+03B5, angbracketleft U+3008, radicalbt/radicalvertex U+221A), so the project table leaves them out and the
   * resolver has no value for them. Their glyphs are already mapped for the stripper and keep PDFBox's text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"epsilon1", "angbracketleft", "radicalbt", "radicalvertex"})
  void namesPdfBoxResolvesDifferentlyAreLeftToPdfBox(String name) {
    assertEquals(Optional.empty(), GlyphNames.resolve(name), name);
  }

  /**
   * Every AGL name (PDFBox's glyphlist.txt) resolves to exactly the AGL value, source AGL, unless
   * that value is not a valid Unicode value ({@code zerowidthjoiner;FEFF} in the AGL): then none.
   */
  @Test
  void everyAglNameResolvesToItsAglValue() {
    var agl = TexGlyphNamesTest.pdfBoxList("glyphlist.txt");
    assertTrue(agl.size() > 4000);
    agl.forEach((name, unicode) -> assertEquals(
        GlyphAudit.valid(unicode) ? Optional.of(new Resolved(unicode, Source.AGL)) : Optional.empty(), GlyphNames.resolve(name), name));
    assertEquals(Optional.empty(), GlyphNames.resolve("zerowidthjoiner"));
  }

  /** For every name PDFBox's extraction list knows, the resolver has no value or exactly PDFBox's. */
  @Test
  void neverDisagreesWithPdfBoxsAdditionalList() {
    TexGlyphNamesTest.pdfBoxList("additional.txt").forEach((name, unicode) -> {
      Optional<Resolved> resolved = GlyphNames.resolve(name);
      assertTrue(resolved.isEmpty() || resolved.get().unicode().equals(unicode), name + " -> " + resolved);
    });
  }
}
