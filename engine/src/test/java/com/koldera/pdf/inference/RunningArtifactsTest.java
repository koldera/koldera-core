package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Running headers and footers: the same normalized text at the same position on most pages. */
class RunningArtifactsTest {

  @Test
  void headerAndFooterOnEveryPageAreArtifacts() throws Exception {
    List<List<LayoutBlock>> pages = LayoutFixtures.blocks(SyntheticPdfFactory.untaggedReport(5));

    Set<LayoutBlock> running = RunningArtifacts.find(pages, InferenceSettings.DEFAULTS);

    assertEquals(10, running.size(), running.toString());
    for (List<LayoutBlock> page : pages) {
      assertTrue(running.contains(page.get(0)), "header of page " + page.get(0).page());
      assertTrue(running.contains(page.get(3)), "footer of page " + page.get(3).page());
    }
  }

  @Test
  void twoPagesAreTooFewForRunningArtifacts() throws Exception {
    assertEquals(Set.of(), RunningArtifacts.find(LayoutFixtures.blocks(SyntheticPdfFactory.untaggedReport(2)), InferenceSettings.DEFAULTS));
  }

  @Test
  void pageNumbersMatchAfterDigitNormalization() {
    // "Page 9", "Page 10", "Page 11": one digit run each, so all normalize to "Page #"; x within the 2 pt tolerance.
    List<List<LayoutBlock>> pages = new ArrayList<>();
    for (int n = 9; n <= 11; n++) {
      pages.add(List.of(block(n, "Page " + n, 290 + (n - 9) * 0.8f, 30)));
    }
    assertEquals(3, RunningArtifacts.find(pages, InferenceSettings.DEFAULTS).size());

    // Moved by more than the tolerance on two of three pages: below the 60 % share.
    List<List<LayoutBlock>> moved = List.of(List.of(block(1, "Page 1", 290, 30)), List.of(block(2, "Page 2", 100, 30)), List.of(block(3, "Page 3", 500, 30)));
    assertEquals(Set.of(), RunningArtifacts.find(moved, InferenceSettings.DEFAULTS));
  }

  @Test
  void theKeyReplacesDigitRunsAndCollapsesWhitespace() {
    assertEquals("Chapter # — page # of #", RunningArtifacts.key("Chapter 12 —  page 3\tof 250 "));
  }

  private static LayoutBlock block(int page, String text, float x, float y) {
    TextRun run = new TextRun(0, text, x, y, x + 40, y + 9, 9, false, "Helvetica");
    return new LayoutBlock(page, List.of(0), List.of(run), x, y, x + 40, y + 9, 9, false, 1, text);
  }
}
