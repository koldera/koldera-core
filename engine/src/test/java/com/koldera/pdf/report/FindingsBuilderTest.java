package com.koldera.pdf.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.WorkerResult.Finding;
import com.koldera.pdf.contracts.WorkerResult.Resolution;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.fonts.GlyphNames;
import com.koldera.pdf.validation.ValidationOutcome;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Machine-check findings carry what the copy's final validation says about them; nothing else does. */
class FindingsBuilderTest {

  private static final String SPEC = "ISO 14289-1:2014 ";
  private static final String LANG = SPEC + "7.2-30";
  private static final String CONTENT = SPEC + "7.1-3";
  private static final String FIGURE = SPEC + "7.3-1";

  @Test
  void findingsWithoutAFinalValidationCarryNoResolution() {
    List<Finding> findings =
        new FindingsBuilder().machineChecks(failed(Map.of(LANG, 1, CONTENT, 3))).imagesOnPages(List.of(1)).build();

    assertTrue(findings.stream().allMatch(f -> f.resolution() == null), findings.toString());
    assertEquals(List.of(CONTENT, LANG, FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW, FindingsBuilder.MANUAL_REVIEW_REQUIRED), ids(findings), "validator order (sorted rule ids), then review items");
  }

  @Test
  void resolvedMachineChecksSayWhetherTheCopyStillFailsThem() {
    List<Finding> findings =
        new FindingsBuilder()
            .machineChecks(failed(Map.of(LANG, 1, CONTENT, 3)))
            .imagesOnPages(List.of(1), true)
            .basicStructureAdded()
            .resolveMachineChecks(failed(Map.of(LANG, 1)))
            .build();

    assertEquals(Resolution.STILL_FAILING, byId(findings, LANG).resolution());
    assertEquals(Resolution.FIXED_IN_COPY, byId(findings, CONTENT).resolution());
    assertNull(byId(findings, FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW).resolution(), "review items are not machine checks");
    assertNull(byId(findings, FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW).resolution());
    assertNull(byId(findings, FindingsBuilder.MANUAL_REVIEW_REQUIRED).resolution());
  }

  @Test
  void rulesThatFirstFailInTheCopyAreAddedAsStillFailingMachineChecksAfterTheBaselineOnes() {
    ValidationOutcome fin =
        new ValidationOutcome(ValidationStatus.FAILED, Map.of(FIGURE, 2), Map.of(FIGURE, "Figure tags shall include an alternative representation"), null, null, null, null);

    List<Finding> findings =
        new FindingsBuilder().machineChecks(failed(Map.of(CONTENT, 3))).imagesOnPages(List.of(1), true).resolveMachineChecks(fin).build();

    assertEquals(List.of(CONTENT, FIGURE, FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW, FindingsBuilder.MANUAL_REVIEW_REQUIRED), ids(findings));
    Finding added = byId(findings, FIGURE);
    assertEquals(Resolution.STILL_FAILING, added.resolution());
    assertFalse(added.needsHumanReview(), "a validator rule is a machine check");
    assertNull(added.pageNumber());
    assertEquals("PDF/UA-1 machine check failed (2 checks): Figure tags shall include an alternative representation", added.message());
    assertEquals(Resolution.FIXED_IN_COPY, byId(findings, CONTENT).resolution());
  }

  @Test
  void informationalNotesAndReviewItemsNeverCarryAResolution() {
    List<Finding> findings =
        new FindingsBuilder().machineChecks(failed(Map.of(CONTENT, 3))).fontChecksRecounted(List.of("7.21.7-1 1 -> 3")).resolveMachineChecks(failed(Map.of())).build();

    Finding note = byId(findings, FindingsBuilder.FONT_CHECKS_RECOUNTED);
    assertFalse(note.needsHumanReview());
    assertNull(note.resolution());
    assertEquals(Resolution.FIXED_IN_COPY, byId(findings, CONTENT).resolution());
  }

  @Test
  void figureFindingsArePerPageWithExactMessages() {
    List<Finding> findings = new FindingsBuilder().figuresWithoutAltMarkedArtifact(new java.util.TreeMap<>(Map.of(4, 2, 1, 1))).build();

    List<Finding> figures = findings.stream().filter(f -> f.ruleId().equals(FindingsBuilder.FIGURE_ALT_MISSING_MARKED_ARTIFACT)).toList();
    assertEquals("FIGURE_ALT_MISSING_MARKED_ARTIFACT", FindingsBuilder.FIGURE_ALT_MISSING_MARKED_ARTIFACT);
    assertEquals(2, figures.size());
    assertEquals(1, figures.get(0).pageNumber());
    assertEquals(4, figures.get(1).pageNumber());
    assertTrue(figures.get(0).needsHumanReview() && figures.get(1).needsHumanReview());
    assertEquals("1 figure on this page had no alternative text and was marked as decoration (artifact), so assistive technology skips it."
        + " Check whether it carries meaning; if it does, add alternative text in the source document and convert again.", figures.get(0).message());
    assertEquals("2 figures on this page had no alternative text and were marked as decoration (artifact), so assistive technology skips them."
        + " Check whether any carries meaning; if one does, add alternative text in the source document and convert again.", figures.get(1).message());
  }

  @Test
  void whenFiguresWereMarkedAsArtifactTheImageAndStructureMessagesSaySo() {
    List<Finding> findings = new FindingsBuilder().imagesOnPages(List.of(2), false, true).basicStructureAdded(true).build();

    Finding images = byId(findings, FindingsBuilder.IMAGES_REQUIRE_HUMAN_REVIEW);
    assertEquals(2, images.pageNumber());
    assertEquals("page contains image content; images without alternative text were marked as decoration (see FIGURE_ALT_MISSING_MARKED_ARTIFACT);"
        + " alternative text is never generated automatically", images.message());
    assertEquals("Basic tags were added from the page content in content-stream order: paragraphs and decorative artifacts; images without"
        + " alternative text were marked as decoration. Headings, tables and the reading order were not inferred and must be reviewed.",
        byId(findings, FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW).message());
    assertEquals(FindingsBuilder.BASIC_STRUCTURE_REVIEW_MESSAGE_FIX, byId(findings, FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW).message());
    assertEquals(FindingsBuilder.BASIC_STRUCTURE_REVIEW_MESSAGE, byId(new FindingsBuilder().basicStructureAdded().build(), FindingsBuilder.BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW).message(),
        "the structure_fix message stays verbatim");
  }

  @Test
  void renumberedHeadingsPointAGeneratedCopyAtTheHtmlAndAnUploadAtItsSourceDocument() {
    String generated = byId(new FindingsBuilder().headingLevelsRenumbered("H2→H1 ×1").build(), FindingsBuilder.HEADING_LEVELS_RENUMBERED).message();
    assertEquals(generated, byId(new FindingsBuilder().headingLevelsRenumbered("H2→H1 ×1", true).build(), FindingsBuilder.HEADING_LEVELS_RENUMBERED).message());
    assertEquals("Heading levels were renumbered so that the first heading is level 1 and no level is skipped (H2→H1 ×1); the order of the headings"
        + " and their text did not change. Check that the levels still match the document's structure; if not, correct the headings in the HTML and"
        + " convert again.", generated);

    Finding upload = byId(new FindingsBuilder().headingLevelsRenumbered("H2→H1 ×1", false).build(), FindingsBuilder.HEADING_LEVELS_RENUMBERED);
    assertEquals("Heading levels were renumbered so that the first heading is level 1 and no level is skipped (H2→H1 ×1); the order of the headings"
        + " and their text did not change. Check that the levels still match the document's structure; if not, correct the headings in the source"
        + " document and convert again.", upload.message());
    assertTrue(upload.needsHumanReview());
    assertNull(upload.pageNumber());
  }

  private static ValidationOutcome failed(Map<String, Integer> rules) {
    return new ValidationOutcome(rules.isEmpty() ? ValidationStatus.PASSED : ValidationStatus.FAILED, rules, Map.of(), null, null, null, null);
  }

  private static Finding byId(List<Finding> findings, String ruleId) {
    return findings.stream().filter(f -> f.ruleId().equals(ruleId)).findFirst().orElseThrow(() -> new AssertionError("no finding " + ruleId + " in " + findings));
  }

  @Test
  void fontFindingsHaveExactMessages() {
    Finding one = new FindingsBuilder().fontGlyphsWithoutUnicode(1, 1, List.of("Lato-Medium")).build().get(0);
    assertEquals(FindingsBuilder.FONT_GLYPHS_WITHOUT_UNICODE, one.ruleId());
    assertEquals("1 character code in 1 font (Lato-Medium) has no Unicode mapping that could be derived from the file, so assistive technology"
        + " cannot read it and copied text is wrong. Use fonts with a Unicode map in the source document and convert again.", one.message());
    assertNull(one.pageNumber());
    assertTrue(one.needsHumanReview());

    Finding many = new FindingsBuilder().fontGlyphsWithoutUnicode(47, 4, List.of("Lato-Bold", "Lato-BoldItalic", "Lato-Italic", "Lato-Medium")).build().get(0);
    assertEquals("47 character codes in 4 fonts (Lato-Bold, Lato-BoldItalic, Lato-Italic, Lato-Medium) have no Unicode mapping that could be derived"
        + " from the file, so assistive technology cannot read them and copied text is wrong. Use fonts with a Unicode map in the source document and"
        + " convert again.", many.message());

    assertTrue(new FindingsBuilder().fontGlyphsWithoutUnicode(9, 7, List.of("G", "F", "E", "D", "C", "B", "A")).build().get(0).message()
        .startsWith("9 character codes in 7 fonts (A, B, C, D, E and 2 more) have"));
    SortedMap<GlyphNames.Source, Integer> single = new TreeMap<>();
    single.put(GlyphNames.Source.AGL, 1);
    assertEquals("Unicode mappings were added for 1 character code that had no Unicode value, so its extracted text changed (glyph names 1).",
        new FindingsBuilder().fontUnicodeDerived(single).build().get(0).message());

    SortedMap<GlyphNames.Source, Integer> bySource = new TreeMap<>();
    bySource.put(GlyphNames.Source.AGL, 1);
    bySource.put(GlyphNames.Source.TEX, 2);
    Finding derived = new FindingsBuilder().fontUnicodeDerived(bySource).build().get(0);
    assertEquals(FindingsBuilder.FONT_UNICODE_DERIVED, derived.ruleId());
    assertEquals("Unicode mappings were added for 3 character codes that had no Unicode value, so their extracted text changed (glyph names 1,"
        + " TeX glyph names 2).", derived.message());
    assertNull(derived.pageNumber());
    assertFalse(derived.needsHumanReview());

    SortedMap<GlyphNames.Source, Integer> all = new TreeMap<>();
    all.put(GlyphNames.Source.MAC_ORDER, 4);
    all.put(GlyphNames.Source.PROGRAM, 1);
    assertEquals("Unicode mappings were added for 5 character codes that had no Unicode value, so their extracted text changed (font program 1,"
        + " standard Macintosh glyph order 4).", new FindingsBuilder().fontUnicodeDerived(all).build().get(0).message());
  }

  private static List<String> ids(List<Finding> findings) {
    return findings.stream().map(Finding::ruleId).toList();
  }

  @Test
  void embeddedSubstituteFindingHasTheExactMessage() {
    Finding finding = new FindingsBuilder().fontEmbeddedSubstitute("ArialMT", "Liberation Sans Regular").build().get(0);
    assertEquals(FindingsBuilder.FONT_EMBEDDED_SUBSTITUTE, finding.ruleId());
    assertEquals("The font 'ArialMT' was not embedded; the copy embeds the metric-compatible 'Liberation Sans Regular' (same character widths, so"
        + " line breaks and positions are unchanged). Letter shapes can differ slightly from the original font; check the appearance.", finding.message());
    assertNull(finding.pageNumber());
    assertTrue(finding.needsHumanReview());
  }
}
