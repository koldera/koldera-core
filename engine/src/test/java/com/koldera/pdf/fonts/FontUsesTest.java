package com.koldera.pdf.fonts;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class FontUsesTest {

  @Test
  void collectsCodesModesPagesAndAppearances() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedFontUses())) {
      List<FontUses.Use> uses = FontUses.collect(doc);
      assertEquals(2, uses.size(), uses.toString());
      FontUses.Use f1 = uses.get(0);
      assertEquals(new TreeMap<>(Map.of(65, 1, 66, 1, 67, 1, 68, 1)), f1.codes());
      assertEquals(new TreeSet<>(Set.of(1, 2)), f1.pages());
      assertTrue(f1.visible());
      assertTrue(f1.invisible());
      assertTrue(f1.inAppearance());
      FontUses.Use f2 = uses.get(1);
      assertEquals(new TreeMap<>(Map.of(90, 1)), f2.codes());
      assertEquals(new TreeSet<>(Set.of(2)), f2.pages());
      assertFalse(f2.visible());
      assertTrue(f2.invisible());
      assertFalse(f2.inAppearance());
    }
  }

  @Test
  void countsOccurrencesPerCode() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(), "ABA".getBytes(US_ASCII), null))) {
      List<FontUses.Use> uses = FontUses.collect(doc);
      assertEquals(1, uses.size());
      assertEquals(new TreeMap<>(Map.of(65, 2, 66, 1)), uses.get(0).codes());
    }
  }
}
