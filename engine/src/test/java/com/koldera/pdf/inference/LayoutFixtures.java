package com.koldera.pdf.inference;

import com.koldera.pdf.structure.ContentTagger;
import com.koldera.pdf.structure.ContentTagger.PageAssignment;
import com.koldera.pdf.structure.ContentTagger.PageItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Test helpers: the walked items and layout blocks of a synthetic PDF, as the inference sees them. */
final class LayoutFixtures {

  private LayoutFixtures() {}

  /** The layout blocks of every page, in page order, each page's blocks in content order. */
  static List<List<LayoutBlock>> blocks(byte[] pdf) throws Exception {
    List<List<LayoutBlock>> out = new ArrayList<>();
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      for (PageItems page : pages(doc)) {
        Map<PDContentStream, List<TextRun>> runs = TextRuns.of(page.page());
        out.add(PageLayout.blocks(page, runs, InferenceSettings.DEFAULTS));
      }
    }
    return out;
  }

  /** The items the content tagger walks (mode fix), captured through a grouping that assigns nothing. */
  static List<PageItems> pages(PDDocument doc) throws Exception {
    List<PageItems> captured = new ArrayList<>();
    ContentTagger.analyze(doc, true, (document, pages) -> {
      captured.addAll(pages);
      List<PageAssignment> none = new ArrayList<>();
      for (int i = 0; i < pages.size(); i++) {
        none.add(new PageAssignment(Map.of(), Map.of(), List.of()));
      }
      return none;
    });
    return captured;
  }
}
