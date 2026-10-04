package com.koldera.pdf.generated;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * {@code PDF_GENERATED_STRUCTURE_V1}: for PDFs generated from HTML ({@code origin=html_generated}) only, mode {@code fix}:
 * normalizes Chromium's tagging deterministically, step by step (lists, tables, figures, links, forms, inline blocks, ruby,
 * heading levels and, last, painting outside any marked content as artifact). Nothing is guessed: each step regroups or
 * relabels what Chromium tagged, or marks unreachable painting as artifact. The reason lists per-step counts.
 */
public final class GeneratedStructureRule implements RemediationRule {

  static final String SAMPLE_PREFIX = "; untagged text sample: \"";
  static final String HEADINGS_PREFIX = "; headings renumbered: ";
  private static final Pattern UNTAGGED_TEXT_COUNT = Pattern.compile(", untagged text (\\d+), form fields \\d+");

  /** Untagged text marked as artifact by an applied run: how many runs, and a sample (null when none could be decoded). */
  public record UntaggedText(int runs, String sample) {}

  /**
   * The untagged text an <em>applied</em> {@code PDF_GENERATED_STRUCTURE_V1} action marked as
   * artifact, read back from its reason; empty for any other action or when there was none.
   */
  public static Optional<UntaggedText> untaggedText(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_GENERATED_STRUCTURE_V1 || action.outcome() != ActionOutcome.APPLIED || action.reason() == null) {
      return Optional.empty();
    }
    String reason = action.reason();
    Matcher count = UNTAGGED_TEXT_COUNT.matcher(reason);
    if (!count.find()) {
      return Optional.empty();
    }
    int runs = Integer.parseInt(count.group(1));
    if (runs == 0) {
      return Optional.empty();
    }
    int at = reason.indexOf(SAMPLE_PREFIX);
    String sample = at >= 0 && reason.endsWith("\"") ? reason.substring(at + SAMPLE_PREFIX.length(), reason.length() - 1) : null;
    return Optional.of(new UntaggedText(runs, sample));
  }

  /**
   * The heading-level changes an <em>applied</em> {@code PDF_GENERATED_STRUCTURE_V1} action made (for
   * example {@code "H2→H1 ×1, H4→H3 ×2"}), read back from its reason; empty for any other action or
   * when no level changed.
   */
  public static Optional<String> headingsRenumbered(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_GENERATED_STRUCTURE_V1 || action.outcome() != ActionOutcome.APPLIED || action.reason() == null) {
      return Optional.empty();
    }
    String reason = action.reason();
    int sample = reason.indexOf(SAMPLE_PREFIX);
    String head = sample >= 0 ? reason.substring(0, sample) : reason;
    int at = head.indexOf(HEADINGS_PREFIX);
    if (at < 0) {
      return Optional.empty();
    }
    String mapping = head.substring(at + HEADINGS_PREFIX.length());
    int end = mapping.indexOf("; ");
    return Optional.of(end >= 0 ? mapping.substring(0, end) : mapping);
  }

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_GENERATED_STRUCTURE_V1;
  }

  /** Only {@code fix} runs over generated PDFs; uploads and deprecated modes never list this rule. */
  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    if (!participates(request)) {
      return RuleDecision.skip("generated-PDF normalization only runs for PDFs this platform generated from HTML", null, null);
    }
    if (document.getDocumentCatalog().getStructureTreeRoot() == null) {
      return RuleDecision.skip("document is not tagged; there is no generated structure to normalize", "untagged", null);
    }
    return RuleDecision.apply(
        "generated tagged PDF; lists, tables, figures and links are normalized without guessing",
        "StructTreeRoot present",
        "LI > Lbl + LBody, orphan LI in L, table borders as Artifact, cell table attributes merged, short rows padded,"
            + " TR in THead/TBody after a header row, figure wrappers collapsed, Figure BBox, Link /Contents, Form without widget as Div,"
            + " container decoration as Artifact, Div in a paragraph as Span, loose links with their text as P, inline blocks as P, block figures placed, ruby base in RB,"
            + " heading levels without gaps, untagged painting and text as Artifact");
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    if (!decision.applicable()) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    int listItems;
    int lists;
    TableDecoration.Outcome borders;
    int tableAttributes;
    int cells;
    int tables;
    int figures;
    int boxes;
    int links;
    WidgetlessForms.Outcome forms;
    int decorations;
    int spans;
    int paragraphs;
    int linkParagraphs;
    int placements;
    int ruby;
    HeadingLevels.Outcome headings;
    UntaggedArtifacts.Outcome artifacts;
    try {
      listItems = ListNormalizer.normalize(document);
      lists = ListNormalizer.wrapOrphans(document);
      borders = TableDecoration.apply(document);
      int rowDecorations = TableDecoration.rowsAndLists(document);
      tableAttributes = TableAttributes.merge(document) + TablePadding.clampRowSpans(document);
      cells = TablePadding.apply(document);
      tables = TableNormalizer.normalize(document);
      figures = FigureNormalizer.normalize(document);
      boxes = FigureBBox.apply(document);
      links = LinkContents.apply(document);
      forms = WidgetlessForms.apply(document);
      decorations = rowDecorations + TableDecoration.containers(document);
      spans = InlineBlocks.spans(document);
      linkParagraphs = LinkParagraphs.apply(document);
      paragraphs = InlineBlocks.paragraphs(document);
      placements = InlineBlocks.figurePlacement(document);
      ruby = RubyNormalizer.normalize(document);
      headings = HeadingLevels.renumber(document);
      artifacts = UntaggedArtifacts.apply(document);
    } catch (IOException | RuntimeException e) {
      return new WorkerResult.Action(
          id(), ActionOutcome.FAILED, "could not normalize the generated structure (" + e.getClass().getSimpleName() + ")", decision.before(), decision.after());
    }
    // The order of the counts is part of the reason format (docs/04, web fix summary): new counts are appended.
    LinkedHashMap<String, Integer> counted = new LinkedHashMap<>();
    counted.put("list items", listItems);
    counted.put("tables", tables);
    counted.put("links", links);
    counted.put("artifacts", artifacts.painting());
    counted.put("table borders", borders.sequences());
    counted.put("untagged text", artifacts.text());
    counted.put("form fields", forms.fields());
    counted.put("form values", forms.values());
    counted.put("paragraphs", paragraphs);
    counted.put("figure placements", placements);
    counted.put("empty cells", cells);
    counted.put("table attributes", tableAttributes);
    counted.put("decorations", decorations);
    counted.put("lists", lists);
    counted.put("ruby", ruby);
    counted.put("spans", spans);
    counted.put("headings", headings.headings());
    counted.put("link paragraphs", linkParagraphs);
    StringBuilder counts = new StringBuilder();
    for (Map.Entry<String, Integer> entry : counted.entrySet()) {
      if (counts.length() > 0) {
        counts.append(", ");
      }
      counts.append(entry.getKey()).append(' ').append(entry.getValue());
      if (entry.getKey().equals("tables")) {
        counts.append(", figures ").append(figures).append(" (+").append(boxes).append(" boxes)");
      }
    }
    if (borders.tablesLeftUnchanged() > 0) {
      counts.append("; ").append(borders.tablesLeftUnchanged()).append(borders.tablesLeftUnchanged() == 1 ? " table" : " tables")
          .append(" left unchanged: its own marked content could not be shown to be border painting only");
    }
    if (headings.mapping() != null) {
      counts.append(HEADINGS_PREFIX).append(headings.mapping());
    }
    if (artifacts.textSample() != null) {
      counts.append(SAMPLE_PREFIX).append(artifacts.textSample()).append('"');
    }
    int total = figures + boxes + counted.values().stream().mapToInt(Integer::intValue).sum();
    if (total == 0) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, "nothing to normalize: " + counts, decision.before(), null);
    }
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, counts.toString(), decision.before(), decision.after());
  }
}
