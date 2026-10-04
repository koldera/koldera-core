package com.koldera.pdf.report;

import com.koldera.pdf.contracts.WorkerResult.Finding;
import com.koldera.pdf.contracts.WorkerResult.Resolution;
import com.koldera.pdf.fonts.FontUnicodeRule;
import com.koldera.pdf.fonts.GlyphNames;
import com.koldera.pdf.inference.LayoutGrouping;
import com.koldera.pdf.validation.ValidationOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Turns validator output and structural probes into contract findings. Machine-check findings
 * carry the validator's rule id; engine findings use stable upper-case ids and always require
 * human review. Nothing here claims conformance.
 */
public final class FindingsBuilder {

  public static final String MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";
  public static final String IMAGES_REQUIRE_HUMAN_REVIEW = "IMAGES_REQUIRE_HUMAN_REVIEW";
  public static final String DOCUMENT_SIGNED = "DOCUMENT_SIGNED";
  public static final String BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW = "BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW";

  /** The manual-review sentence for {@code structure_fix}; never "accessible", never "PDF/UA". */
  public static final String BASIC_STRUCTURE_REVIEW_MESSAGE =
      "Basic tags were added from the page content in content-stream order: paragraphs, figures without alternative text,"
          + " decorative artifacts. Headings, tables, alternative text and the reading order were not inferred and must be reviewed.";

  /** The manual-review sentence for a {@code fix} run in which figures without alternative text were marked as decoration. */
  public static final String BASIC_STRUCTURE_REVIEW_MESSAGE_FIX =
      "Basic tags were added from the page content in content-stream order: paragraphs and decorative artifacts; images without alternative"
          + " text were marked as decoration. Headings, tables and the reading order were not inferred and must be reviewed.";

  /** Validator rules that failed in the baseline, in the validator's order; always listed first. */
  private final List<Finding> machineChecks = new ArrayList<>();

  /** Review items and informational notes, in the order they were added. */
  private final List<Finding> findings = new ArrayList<>();

  public FindingsBuilder machineChecks(ValidationOutcome validation) {
    for (Map.Entry<String, Integer> rule : validation.failedRuleCounts().entrySet()) {
      machineChecks.add(machineCheck(validation, rule.getKey(), rule.getValue()));
    }
    return this;
  }

  /**
   * Called only once the remediation was applied and its gate passed: each baseline machine check says whether the final
   * validation still fails it, and a check that first fails in the copy is added, so the list matches the after-fix report.
   * Review items and notes are untouched.
   */
  public FindingsBuilder resolveMachineChecks(ValidationOutcome finalValidation) {
    Map<String, Integer> remaining = finalValidation.failedRuleCounts();
    List<String> known = new ArrayList<>();
    for (int i = 0; i < machineChecks.size(); i++) {
      Finding finding = machineChecks.get(i);
      known.add(finding.ruleId());
      machineChecks.set(i, finding.withResolution(remaining.containsKey(finding.ruleId()) ? Resolution.STILL_FAILING : Resolution.FIXED_IN_COPY));
    }
    for (Map.Entry<String, Integer> rule : remaining.entrySet()) {
      if (!known.contains(rule.getKey())) {
        machineChecks.add(machineCheck(finalValidation, rule.getKey(), rule.getValue()).withResolution(Resolution.STILL_FAILING));
      }
    }
    return this;
  }

  private static Finding machineCheck(ValidationOutcome validation, String ruleId, int count) {
    String description = validation.ruleDescriptions().get(ruleId);
    String message =
        "PDF/UA-1 machine check failed ("
            + count
            + (count == 1 ? " check)" : " checks)")
            + (description == null ? "" : ": " + description);
    return new Finding(ruleId, message, null, false);
  }

  public FindingsBuilder imagesOnPages(List<Integer> pages) {
    return imagesOnPages(pages, false);
  }

  /**
   * @param figuresTagged whether basic {@code Figure} tags (without alternative text) were added in
   *     this run, which changes what remains for a human to do but not that a human must do it
   */
  public FindingsBuilder imagesOnPages(List<Integer> pages, boolean figuresTagged) {
    return imagesOnPages(pages, figuresTagged, false);
  }

  /**
   * @param figuresMarkedArtifact whether figures without alternative text were marked as decoration in
   *     this run ({@code PDF_FIGURES_WITHOUT_ALT_V1}); takes precedence over {@code figuresTagged}
   */
  public FindingsBuilder imagesOnPages(List<Integer> pages, boolean figuresTagged, boolean figuresMarkedArtifact) {
    String message =
        figuresMarkedArtifact
            ? "page contains image content; images without alternative text were marked as decoration (see " + FIGURE_ALT_MISSING_MARKED_ARTIFACT
                + "); alternative text is never generated automatically"
            : figuresTagged
            ? "page contains image content; basic Figure tags were added without alternative text; alternative text and"
                + " decorative/artifact decisions require human review and are never generated automatically"
            : "page contains image content; alternative text, figure tagging and decorative/artifact"
                + " decisions require human review and are never generated automatically";
    for (Integer page : pages) {
      findings.add(new Finding(IMAGES_REQUIRE_HUMAN_REVIEW, message, page, true));
    }
    return this;
  }

  /** Added once when the basic structure rule applied; the text is the product copy, verbatim. */
  public FindingsBuilder basicStructureAdded() {
    return basicStructureAdded(false);
  }

  /** As {@link #basicStructureAdded()}; when figures were marked as decoration, the {@code fix} sentence that says so. */
  public FindingsBuilder basicStructureAdded(boolean figuresMarkedArtifact) {
    findings.add(new Finding(
        BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW, figuresMarkedArtifact ? BASIC_STRUCTURE_REVIEW_MESSAGE_FIX : BASIC_STRUCTURE_REVIEW_MESSAGE, null, true));
    return this;
  }

  public static final String STRUCTURE_INFERRED = "STRUCTURE_INFERRED";

  /**
   * Added once, instead of {@link #BASIC_STRUCTURE_REQUIRES_HUMAN_REVIEW}, when {@code
   * PDF_STRUCTURE_INFERRED_V1} applied with structure inferred from the page layout (a person
   * checks it). The text states what was inferred; never a conformance statement.
   */
  public FindingsBuilder structureInferred(LayoutGrouping.Counts c) {
    String message = "Structure was inferred from the page layout: " + c.paragraphs() + " paragraphs, " + c.headings() + " headings, " + c.lists()
        + " lists (" + c.listItems() + " items), " + c.tables() + " tables; " + c.runningArtifacts()
        + " running headers and footers were marked as page furniture (artifact)"
        + (c.runningSample() != null ? ", for example \"" + c.runningSample() + "\"" : "")
        + "; " + c.pagesReordered() + " pages were put into column reading order, " + c.pagesKept() + " kept their content order; " + c.fallbacks()
        + " detected structures fell back to paragraphs because the evidence was weak. Check the headings, lists, tables and reading order against"
        + " the document.";
    findings.add(new Finding(STRUCTURE_INFERRED, message, null, true));
    return this;
  }

  public static final String FIGURE_ALT_MISSING_MARKED_ARTIFACT = "FIGURE_ALT_MISSING_MARKED_ARTIFACT";

  /**
   * One finding per page (in page order) on which figures without alternative text were marked as
   * decoration: a person checks whether one carries meaning.
   *
   * @param perPage figures marked, per 1-based page number
   */
  public FindingsBuilder figuresWithoutAltMarkedArtifact(SortedMap<Integer, Integer> perPage) {
    for (Map.Entry<Integer, Integer> e : perPage.entrySet()) {
      int n = e.getValue();
      String message = n == 1
          ? "1 figure on this page had no alternative text and was marked as decoration (artifact), so assistive technology skips it. Check whether"
              + " it carries meaning; if it does, add alternative text in the source document and convert again."
          : n + " figures on this page had no alternative text and were marked as decoration (artifact), so assistive technology skips them. Check"
              + " whether any carries meaning; if one does, add alternative text in the source document and convert again.";
      findings.add(new Finding(FIGURE_ALT_MISSING_MARKED_ARTIFACT, message, e.getKey(), true));
    }
    return this;
  }

  public static final String FONT_GLYPHS_WITHOUT_UNICODE = "FONT_GLYPHS_WITHOUT_UNICODE";

  /**
   * Character codes for which no Unicode source exists in the file ({@code PDF_FONT_TOUNICODE_V1}): a
   * person has to fix the source document.
   *
   * @param names the fonts' distinct names; at most five are listed, then "and K more"
   */
  public FindingsBuilder fontGlyphsWithoutUnicode(int codes, int fonts, List<String> names) {
    boolean one = codes == 1;
    findings.add(new Finding(
        FONT_GLYPHS_WITHOUT_UNICODE,
        (one ? "1 character code" : codes + " character codes") + " in " + (fonts == 1 ? "1 font" : fonts + " fonts") + " (" + FontUnicodeRule.names(names) + ") "
            + (one ? "has" : "have") + " no Unicode mapping that could be derived from the file, so assistive technology cannot read "
            + (one ? "it" : "them") + " and copied text is wrong. Use fonts with a Unicode map in the source document and convert again.",
        null,
        true));
    return this;
  }

  public static final String FONT_UNICODE_DERIVED = "FONT_UNICODE_DERIVED";

  /**
   * Informational: codes whose extracted text {@code PDF_FONT_TOUNICODE_V1} changed (they had no Unicode
   * value; a written value equal to the text already extracted is not counted), per source, zero counts left out.
   */
  public FindingsBuilder fontUnicodeDerived(SortedMap<GlyphNames.Source, Integer> bySource) {
    List<String> parts = new ArrayList<>();
    int total = 0;
    for (GlyphNames.Source source : GlyphNames.Source.values()) {
      Integer n = bySource.get(source);
      if (n == null || n == 0 || source == GlyphNames.Source.EXISTING) {
        continue;
      }
      total += n;
      parts.add(switch (source) {
        case AGL -> "glyph names " + n;
        case TEX -> "TeX glyph names " + n;
        case PROGRAM -> "font program " + n;
        case MAC_ORDER -> "standard Macintosh glyph order " + n;
        case EXISTING -> throw new IllegalStateException();
      });
    }
    boolean one = total == 1;
    findings.add(new Finding(
        FONT_UNICODE_DERIVED,
        "Unicode mappings were added for " + total + (one ? " character code that had no Unicode value, so its" : " character codes that had no Unicode value, so their")
            + " extracted text changed (" + String.join(", ", parts) + ").",
        null,
        false));
    return this;
  }

  public static final String FONT_EMBEDDED_SUBSTITUTE = "FONT_EMBEDDED_SUBSTITUTE";

  /**
   * A non-embedded font into which {@code PDF_FONT_EMBEDDING_V1} embedded a metric-compatible substitute:
   * widths and positions are unchanged, letter shapes may differ, so a person checks the appearance.
   */
  public FindingsBuilder fontEmbeddedSubstitute(String original, String substitute) {
    findings.add(new Finding(
        FONT_EMBEDDED_SUBSTITUTE,
        "The font '" + original + "' was not embedded; the copy embeds the metric-compatible '" + substitute + "' (same character widths, so line"
            + " breaks and positions are unchanged). Letter shapes can differ slightly from the original font; check the appearance.",
        null,
        true));
    return this;
  }

  /** Informational: font checks that the validator re-counted per marked-content sequence after tagging. */
  public static final String FONT_CHECKS_RECOUNTED = "FONT_CHECKS_RECOUNTED_PER_MARKED_CONTENT";

  public FindingsBuilder fontChecksRecounted(List<String> recounted) {
    findings.add(
        new Finding(
            FONT_CHECKS_RECOUNTED,
            "font checks are counted per glyph per marked-content sequence by the validator, so their counts grew with the"
                + " number of tagged sequences while the fonts, strings and glyphs are unchanged: "
                + String.join(", ", recounted)
                + "; the underlying font problems existed before and still need attention",
            null,
            false));
    return this;
  }

  public static final String TITLE_FROM_FILE_NAME = "TITLE_FROM_FILE_NAME";

  /** A fix run that named the copy after its file: a person should check the title. */
  public FindingsBuilder titleFromFileName() {
    findings.add(new Finding(TITLE_FROM_FILE_NAME, "The title was taken from the file name. Check that it describes the document.", null, true));
    return this;
  }

  public static final String LANGUAGE_CORRECTED = "LANGUAGE_CORRECTED";

  /** A fix run replaced a declared language that the document's own text contradicted: a person should confirm it. */
  public FindingsBuilder languageCorrected(String from, String to, boolean xmpLanguageKept) {
    findings.add(new Finding(
        LANGUAGE_CORRECTED,
        "The document declared its language as '" + from + "', which its text contradicts; it was changed to '" + to + "'. Check that this is the"
            + " document's language." + (xmpLanguageKept ? " The language in the document's XMP metadata was left as it was." : ""),
        null,
        true));
    return this;
  }

  public static final String UNTAGGED_TEXT_MARKED_ARTIFACT = "UNTAGGED_TEXT_MARKED_ARTIFACT";

  /**
   * A generated copy in which text painted outside the tagged content was marked as artifact: assistive technology now skips
   * it, so a person should check none of it carries meaning. Takes the run count and a sample (at most 80 characters).
   */
  public FindingsBuilder untaggedTextMarkedArtifact(int runs, String sample) {
    String message =
        runs
            + (runs == 1 ? " run of text painted outside the tagged content was" : " runs of text painted outside the tagged content were")
            + " marked as decoration (artifact), so assistive technology skips it: running headers and footers, CSS generated"
            + " content, visible aria-hidden content or form-control text"
            + (sample == null ? "" : ", for example \"" + sample + "\"")
            + ". Check that none of it carries meaning; if some does, make it part of the HTML content and convert again.";
    findings.add(new Finding(UNTAGGED_TEXT_MARKED_ARTIFACT, message, null, true));
    return this;
  }

  public static final String HEADING_LEVELS_RENUMBERED = "HEADING_LEVELS_RENUMBERED";

  /**
   * A copy whose heading levels were renumbered so the first heading is H1 and none is skipped: a person checks the result
   * against what the document means. The message sends a generated copy's reader to the HTML and an upload's to its source.
   */
  public FindingsBuilder headingLevelsRenumbered(String mapping, boolean generated) {
    findings.add(
        new Finding(
            HEADING_LEVELS_RENUMBERED,
            "Heading levels were renumbered so that the first heading is level 1 and no level is skipped (" + mapping
                + "); the order of the headings and their text did not change. Check that the levels still match the document's"
                + " structure; if not, correct the headings in " + (generated ? "the HTML" : "the source document") + " and convert again.",
            null,
            true));
    return this;
  }

  /** As {@link #headingLevelsRenumbered(String, boolean)} for a generated copy. */
  public FindingsBuilder headingLevelsRenumbered(String mapping) {
    return headingLevelsRenumbered(mapping, true);
  }

  public static final String PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY = "PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY";

  /**
   * Added only when the copy was given the PDF/UA-1 identifier: the declaration rests on the
   * automated checks alone, so a person still has to review what they cannot see.
   */
  public FindingsBuilder pdfUaIdentifierFromMachineChecks() {
    findings.add(
        new Finding(
            PDF_UA_IDENTIFIER_MACHINE_CHECKS_ONLY,
            "The copy declares PDF/UA-1 in its metadata (pdfuaid:part=1) because the automated PDF/UA-1 machine checks"
                + " reported no failures. Those checks cover only part of PDF/UA-1: reading order, tag semantics,"
                + " alternative text, table structure, link purpose and language still require human review.",
            null,
            true));
    return this;
  }

  public FindingsBuilder signed() {
    findings.add(
        new Finding(
            DOCUMENT_SIGNED,
            "document is digitally signed; automated remediation is not available"
                + " because any change would invalidate the signature",
            null,
            true));
    return this;
  }

  public List<Finding> build() {
    List<Finding> all = new ArrayList<>(machineChecks);
    all.addAll(findings);
    all.add(
        new Finding(
            MANUAL_REVIEW_REQUIRED,
            "automated validation covers machine-verifiable PDF/UA-1 requirements only; reading order,"
                + " semantic tagging, table structure, link purpose and language correctness require"
                + " qualified human review; a passing machine check is not a conformance claim",
            null,
            true));
    return List.copyOf(all);
  }
}
