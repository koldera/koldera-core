package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

/** One text run per text-showing operator, joined to the walker's items by (stream, ordinal). */
class TextRunsTest {

  @Test
  void runsCarryTextPositionAndOrdinal() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedArticle())) {
      List<TextRun> runs = pageRuns(TextRuns.of(doc.getPage(0)));

      assertEquals(7, runs.size(), "one run per showText call");
      TextRun first = runs.get(0);
      assertEquals(0, first.ordinal());
      assertEquals("Paragraph one ", first.text());
      assertEquals(72.0, first.x0(), 0.01);
      assertEquals(720.0, first.baseline(), 0.01);
      assertEquals(12.0, first.fontSize(), 0.001);
      assertFalse(first.bold());
      assertTrue(first.x1() > first.x0());
      assertEquals(1, runs.get(1).ordinal());
      assertEquals("line one.", runs.get(1).text());
      assertEquals(first.x1(), runs.get(1).x0(), 0.5, "the second run continues where the first ended");
      for (int k = 0; k < runs.size(); k++) {
        assertEquals(k, runs.get(k).ordinal());
      }
    }
  }

  @Test
  void boldIsDetectedFromTheFontName() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedBoldAndRegularLine())) {
      List<TextRun> runs = pageRuns(TextRuns.of(doc.getPage(0)));

      assertEquals(2, runs.size());
      assertTrue(runs.get(0).bold(), runs.get(0).fontName());
      assertTrue(runs.get(0).fontName().contains("Helvetica-Bold"), runs.get(0).fontName());
      assertFalse(runs.get(1).bold(), runs.get(1).fontName());
    }
  }

  @Test
  void aFormDrawnOnceHasItsOwnRuns() throws Exception {
    try (PDDocument doc = Loader.loadPDF(SyntheticPdfFactory.untaggedWithFormXObjects())) {
      Map<PDContentStream, List<TextRun>> runs = TextRuns.of(doc.getPage(0));

      assertEquals(2, runs.size(), "the page stream and the once-drawn text form: " + runs.keySet());
      List<TextRun> form = runs.entrySet().stream().filter(e -> !(e.getKey() instanceof PDPage)).findFirst().orElseThrow().getValue();
      assertEquals(1, form.size());
      assertEquals("Text inside a form drawn once.", form.get(0).text());
      assertEquals(72.0, form.get(0).x0(), 0.01);
      assertEquals(510.0, form.get(0).baseline(), 0.01);
    }
  }

  static List<TextRun> pageRuns(Map<PDContentStream, List<TextRun>> runs) {
    return runs.entrySet().stream().filter(e -> e.getKey() instanceof PDPage).findFirst().orElseThrow().getValue();
  }
}
