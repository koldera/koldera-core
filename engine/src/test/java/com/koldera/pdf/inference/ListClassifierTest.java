package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lists from markers at the block start: bullets, strictly consecutive ordinals, nesting by indent. */
class ListClassifierTest {

  @Test
  void bulletsAtTheSameXAreOneList() {
    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(single("• Apples", 72, 700), single("• Pears", 72, 686)), InferenceSettings.DEFAULTS);

    assertEquals(1, runs.size());
    assertEquals(2, runs.get(0).items().size());
    assertEquals(1.0, runs.get(0).confidence());
  }

  @Test
  void aSingleBulletIsWeakEvidence() {
    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(single("• Alone", 72, 700)), InferenceSettings.DEFAULTS);

    assertEquals(1, runs.size());
    assertEquals(0.5, runs.get(0).confidence());
  }

  @Test
  void consecutiveOrdinalsAreStrongerThanGaps() {
    List<ListClassifier.ListRun> consecutive =
        ListClassifier.lists(List.of(single("1. One", 72, 700), single("2. Two", 72, 686), single("3. Three", 72, 672)), InferenceSettings.DEFAULTS);
    assertEquals(1, consecutive.size());
    assertEquals(3, consecutive.get(0).items().size());
    assertEquals(0.9, consecutive.get(0).confidence());

    List<ListClassifier.ListRun> gap = ListClassifier.lists(List.of(single("1. One", 72, 700), single("3. Three", 72, 686)), InferenceSettings.DEFAULTS);
    assertEquals(1, gap.size());
    assertEquals(0.5, gap.get(0).confidence());
  }

  @Test
  void aDeeperMarkerNestsInThePreviousItem() {
    LayoutBlock a = single("• A", 72, 700);
    LayoutBlock a1 = single("• A1", 90, 686);

    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(a, a1), InferenceSettings.DEFAULTS);

    assertEquals(1, runs.size());
    List<ListClassifier.Entry> items = runs.get(0).items();
    assertEquals(2, items.size());
    assertEquals(a, items.get(0).block());
    assertEquals(0, items.get(0).depth());
    assertEquals(a1, items.get(1).block());
    assertEquals(1, items.get(1).depth());
  }

  @Test
  void aLabelIsWrittenOnlyForAMarkerThatIsARunOfItsOwn() {
    LayoutBlock oneRun = single("• Apples", 72, 700);
    TextRun marker = new TextRun(0, "•", 72, 686, 76, 697, 11, false, "Helvetica");
    TextRun text = new TextRun(1, "Pears", 84, 686, 114, 697, 11, false, "Helvetica");
    LayoutBlock twoRuns = new LayoutBlock(1, List.of(10, 11), List.of(marker, text), 72, 686, 114, 697, 11, false, 1, "• Pears");

    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(oneRun, twoRuns), InferenceSettings.DEFAULTS);

    assertEquals(1, runs.size());
    assertNull(runs.get(0).items().get(0).label(), "the marker shares its run with the text");
    assertNotNull(runs.get(0).items().get(1).label());
    assertEquals(marker, runs.get(0).items().get(1).label());
  }

  @Test
  void theSymbolFontBulletIsABullet() {
    // Word and PDFsharp paint the bullet with the Symbol font, which extracts as U+F0B7 (private use).
    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(single(" Apples", 72, 700), single(" Pears", 72, 686)), InferenceSettings.DEFAULTS);

    assertEquals(1, runs.size());
    assertEquals(1.0, runs.get(0).confidence());
    assertEquals(List.of("Disc", "Disc"), runs.get(0).numbering());
  }

  @Test
  void singleLetterOrdinalsCountOnlyFromAOrIWithAConsecutiveSecond() {
    assertEquals(0.9, ListClassifier.lists(List.of(single("a. One", 72, 700), single("b. Two", 72, 686)), InferenceSettings.DEFAULTS).get(0).confidence());
    assertEquals(0.9, ListClassifier.lists(List.of(single("i. One", 72, 700), single("ii. Two", 72, 686)), InferenceSettings.DEFAULTS).get(0).confidence());
    // Prose that happens to start with a letter and a full stop: "p. 45", "v. Wade", "c. 1900" is no list (and no fallback).
    assertEquals(0.0, ListClassifier.lists(List.of(single("p. 45 of the report", 72, 700)), InferenceSettings.DEFAULTS).get(0).confidence());
    assertEquals(0.0, ListClassifier.lists(List.of(single("v. Wade held", 72, 700)), InferenceSettings.DEFAULTS).get(0).confidence());
    assertEquals(0.0, ListClassifier.lists(List.of(single("c. One", 72, 700), single("d. Two", 72, 686)), InferenceSettings.DEFAULTS).get(0).confidence());
    assertEquals(0.0, ListClassifier.lists(List.of(single("a. One", 72, 700), single("c. Two", 72, 686)), InferenceSettings.DEFAULTS).get(0).confidence());
  }

  @Test
  void aNestedLevelOfProseLettersRejoinsItsParentItem() {
    // "v. Wade" split off as a deeper "item": it is prose, so it rejoins item 1's text and the 1/2/3 list stays a list.
    LayoutBlock one = single("1. One", 72, 700);
    LayoutBlock wade = single("v. Wade held", 90, 686);
    LayoutBlock two = single("2. Two", 72, 672);
    LayoutBlock three = single("3. Three", 72, 658);

    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(one, wade, two, three), InferenceSettings.DEFAULTS);

    assertEquals(1, runs.size());
    assertEquals(0.9, runs.get(0).confidence());
    assertEquals(List.of(one, two, three), runs.get(0).items().stream().map(ListClassifier.Entry::block).toList());
    assertEquals(List.of(wade), runs.get(0).items().get(0).more());
    assertEquals(List.of(0, 0, 0), runs.get(0).items().stream().map(ListClassifier.Entry::depth).toList());
  }

  @Test
  void theNextMarkerOfASequenceContinuesIt() {
    assertEquals(true, ListClassifier.continues("1. One", "2. Two"));
    assertEquals(true, ListClassifier.continues("a) first", "b) second"));
    assertEquals(true, ListClassifier.continues("i. first", "ii. second"));
    assertEquals(true, ListClassifier.continues("7-1. First", "7-2. Second"));
    assertEquals(true, ListClassifier.continues("• one", "• two"));
    assertEquals(false, ListClassifier.continues("1. The court in Roe", "v. Wade held"));
    assertEquals(false, ListClassifier.continues("• most likely", "c. 1900"));
    assertEquals(false, ListClassifier.continues("• one", "◦ two"));
    assertEquals(false, ListClassifier.continues("1. One", "3. Three"));
  }

  @Test
  void numberedParagraphsWithAChapterPrefixAreOneList() {
    // Government style "7-1.", "7-2.": consecutive in the last number under the same prefix.
    List<ListClassifier.ListRun> runs = ListClassifier.lists(
        List.of(single("7-1. The aim of this leaflet", 72, 700), single("7-2. MOD has obligations", 72, 686), single("7-3. The importance", 72, 672)),
        InferenceSettings.DEFAULTS);
    assertEquals(1, runs.size());
    assertEquals(3, runs.get(0).items().size());
    assertEquals(0.9, runs.get(0).confidence());

    List<ListClassifier.ListRun> otherPrefix =
        ListClassifier.lists(List.of(single("7-1. One", 72, 700), single("8-2. Two", 72, 686)), InferenceSettings.DEFAULTS);
    assertEquals(0.5, otherPrefix.get(0).confidence());
    // A numbered heading without the closing full stop is no list marker.
    assertEquals(List.of(), ListClassifier.lists(List.of(single("1.2 Scope", 72, 700), single("1.3 Terms", 72, 686)), InferenceSettings.DEFAULTS));
  }

  @Test
  void aLineThatStartsWithASpaceRunBeforeItsMarkerStartsWithTheMarker() {
    // Word paints a space run left of the bullet: the line text is " ● item".
    assertEquals(true, ListClassifier.startsWithMarker(" ● item of the list"));
    assertEquals(true, ListClassifier.startsWithMarker("  2. Two"));
  }

  @Test
  void theMarkerIsTheRunThatShowsIt() {
    // A block whose highest run is not the marker (a neighbouring column's line on a slightly higher baseline): the marker x
    // is the x of the run that starts with the marker, so the next item at the same x is its sibling, not nested.
    TextRun other = new TextRun(0, "neighbouring column text", 305, 704, 400, 715, 9, false, "Helvetica");
    TextRun marker = new TextRun(1, "● first item", 71, 700, 150, 711, 9, false, "Helvetica");
    LayoutBlock first = new LayoutBlock(1, List.of(1, 2), List.of(other, marker), 71, 700, 400, 712, 9, false, 1, "● first item neighbouring column text");
    LayoutBlock second = single("● second item", 71, 686);

    List<ListClassifier.ListRun> runs = ListClassifier.lists(List.of(first, second), InferenceSettings.DEFAULTS);

    assertEquals(List.of(0, 0), runs.get(0).items().stream().map(ListClassifier.Entry::depth).toList());
  }

  @Test
  void aBareMarkerIsRecognised() {
    assertEquals(true, ListClassifier.isBareMarker("2."));
    assertEquals(true, ListClassifier.isBareMarker(" • "));
    assertEquals(false, ListClassifier.isBareMarker("2. Two"));
    assertEquals(false, ListClassifier.isBareMarker("2017"));
  }

  @Test
  void textWithoutAMarkerIsNoList() {
    assertEquals(List.of(), ListClassifier.lists(List.of(single("Plain text", 72, 700), single("1 Results", 72, 686)), InferenceSettings.DEFAULTS));
  }

  /** A one-line block holding one run of {@code text} at {@code x}. */
  static LayoutBlock single(String text, float x, float y) {
    TextRun run = new TextRun(0, text, x, y, x + text.length() * 6, y + 11, 11, false, "Helvetica");
    return new LayoutBlock(1, List.of((int) (1000 - y)), List.of(run), x, y, run.x1(), y + 11, 11, false, 1, text);
  }
}
