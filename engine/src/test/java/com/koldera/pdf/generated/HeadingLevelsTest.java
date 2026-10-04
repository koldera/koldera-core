package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.assertParentTreeMatches;
import static com.koldera.pdf.generated.GeneratedStructure.assertSameContent;
import static com.koldera.pdf.generated.GeneratedStructure.contentKeys;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class HeadingLevelsTest {

  @Test
  void levelsFollowOutlineDepthSoTheFirstIsOneAndNoneIsSkipped() {
    assertArrayEquals(new int[] {1, 2, 3, 2, 3, 1}, HeadingLevels.levels(new int[] {1, 2, 3, 2, 3, 1}), "a valid sequence is unchanged");
    assertArrayEquals(new int[] {1, 1, 2, 3, 2, 3}, HeadingLevels.levels(new int[] {2, 1, 3, 4, 2, 4}));
    assertArrayEquals(new int[] {1, 2, 2}, HeadingLevels.levels(new int[] {2, 3, 3}), "no H1 at all: everything moves up");
    assertArrayEquals(new int[] {1, 1, 1}, HeadingLevels.levels(new int[] {3, 2, 1}));
    assertArrayEquals(new int[] {1, 2, 3, 3}, HeadingLevels.levels(new int[] {1, 2, 4, 3}), "an H3 after a skipped H4 is its sibling");
    assertArrayEquals(new int[] {1, 2, 2, 2}, HeadingLevels.levels(new int[] {1, 3, 3, 2}));
    assertArrayEquals(new int[0], HeadingLevels.levels(new int[0]));
  }

  @Test
  void theFixtureHeadingsAreRenumberedAndTheChangesSummarised() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-headings.pdf")) {
      List<String> before = contentKeys(doc);
      assertEquals(List.of("H2", "H1", "H3", "H4", "H2", "H4"), headings(doc), "fixture shape");

      HeadingLevels.Outcome outcome = HeadingLevels.renumber(doc);

      assertEquals(new HeadingLevels.Outcome(4, "H2→H1 ×1, H3→H2 ×1, H4→H3 ×2"), outcome);
      assertEquals(List.of("H1", "H1", "H2", "H3", "H2", "H3"), headings(doc));
      assertEquals(new HeadingLevels.Outcome(0, null), HeadingLevels.renumber(doc), "renumbered levels are left alone");
      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        assertSameContent(before, after);
        assertParentTreeMatches(after);
      }
    }
  }

  @Test
  void aValidOutlineAndATreeUsingHAreLeftAlone() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-lists.pdf")) {
      assertEquals(new HeadingLevels.Outcome(0, null), HeadingLevels.renumber(doc));
    }
    try (PDDocument doc = GeneratedStructure.fixture("generated-headings.pdf")) {
      COSDictionary last = GeneratedStructure.elements(doc, "H4").get(1);
      last.setItem(COSName.S, COSName.getPDFName("H"));
      assertEquals(new HeadingLevels.Outcome(0, null), HeadingLevels.renumber(doc));
      assertEquals(List.of("H2", "H1", "H3", "H4", "H2"), headings(doc));
    }
  }

  private static List<String> headings(PDDocument doc) {
    List<String> out = new ArrayList<>();
    walk(GeneratedStructure.root(doc), out);
    return out;
  }

  private static void walk(COSDictionary node, List<String> out) {
    for (COSBase kid : GeneratedStructure.kids(node)) {
      if (GeneratedStructure.isElement(kid)) {
        String s = GeneratedStructure.type(kid);
        if (s.matches("H[1-6]")) {
          out.add(s);
        }
        walk((COSDictionary) kid, out);
      }
    }
  }
}
