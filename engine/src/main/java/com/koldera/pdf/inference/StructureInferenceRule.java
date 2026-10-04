package com.koldera.pdf.inference;

import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.generated.InlineBlocks;
import com.koldera.pdf.inference.StructureStrategy.Decision;
import com.koldera.pdf.inference.StructureStrategy.Kind;
import com.koldera.pdf.rules.BasicStructureRule;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.structure.ContentTagger;
import com.koldera.pdf.structure.ContentTagger.Plan;
import com.koldera.pdf.structure.ContentTagger.Summary;
import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * {@code PDF_STRUCTURE_INFERRED_V1}: in mode {@code fix}, uploads only, the per-upload strategy ({@link StructureStrategy}):
 * untagged is tagged from content, usable tags are kept, unusable ones are discarded ({@link TagStripper}) and rebuilt by the layout grouping ({@link LayoutGrouping}); above the work caps basic content-order tagging is written.
 * Descriptions survive via {@link DescribedFigures} (1:1 only) and {@link DescribedElements}; otherwise, and for documents
 * with form fields, the tags are kept. Rewrites are marker-only, proven by the text digest and {@code CopyIntegrity}.
 */
public final class StructureInferenceRule implements RemediationRule {

  static final String PRESENT = "StructTreeRoot present";

  private final InferenceSettings settings;
  private final LayoutGrouping.RunsSource runsSource;
  /** The untagged case is exactly the basic tagger's (including its stray-paint render check). */
  private final BasicStructureRule basic = new BasicStructureRule();
  private PDDocument decidedDocument;
  private Decision decided;

  public StructureInferenceRule(InferenceSettings settings) {
    this(settings, TextRuns::of);
  }

  /** Tests: a different source of text runs (e.g. one that fails). */
  StructureInferenceRule(InferenceSettings settings, LayoutGrouping.RunsSource runsSource) {
    this.settings = settings;
    this.runsSource = runsSource;
  }

  @Override
  public WorkerResult.RuleId id() {
    return WorkerResult.RuleId.PDF_STRUCTURE_INFERRED_V1;
  }

  @Override
  public boolean participates(WorkerRequest request) {
    return request.isFix() && !request.isHtmlGenerated();
  }

  @Override
  public RuleDecision inspect(PDDocument document, WorkerRequest request) {
    decidedDocument = null;
    decided = null;
    String before = document.getDocumentCatalog().getStructureTreeRoot() != null ? PRESENT : "untagged";
    if (!participates(request)) {
      return RuleDecision.skip("the structure strategy runs only in fix for uploaded PDFs", before, null);
    }
    Decision decision;
    try {
      decision = StructureStrategy.decide(document, settings);
    } catch (IOException | RuntimeException e) {
      return RuleDecision.skip("the structure strategy could not be decided (" + e.getClass().getSimpleName() + "); nothing is changed", before, null);
    }
    decidedDocument = document;
    decided = decision;
    return switch (decision.kind()) {
      case UNTAGGED -> {
        RuleDecision tagging = basic.inspect(document, request);
        yield new RuleDecision(tagging.applicable(), decision.reason() + "; " + tagging.reason(), tagging.before(), tagging.after());
      }
      case TAGGED_USABLE -> RuleDecision.skip(decision.reason(), PRESENT, null);
      case TAGGED_UNUSABLE -> {
        if (hasWidgets(document)) {
          yield RuleDecision.skip(decision.reason() + "; the tags are kept because the document has form fields, which are not re-tagged", PRESENT, null);
        }
        try {
          DescribedElements.check(document);
        } catch (DescribedElements.Refused | IOException | RuntimeException e) {
          yield RuleDecision.skip(decision.reason() + "; " + DESCRIBED_OTHERS, PRESENT, null);
        }
        boolean described;
        try {
          described = !DescribedFigures.read(document).isEmpty();
        } catch (DescribedFigures.Refused | IOException | RuntimeException e) {
          yield RuleDecision.skip(decision.reason() + "; " + DESCRIBED_FIGURES, PRESENT, null);
        }
        String cause = rebuildRefusal(document);
        if (cause != null) {
          yield RuleDecision.skip(decision.reason() + "; the tags could not be rebuilt (" + cause + "), so they are kept", PRESENT, null);
        }
        if (described && !carriesOnScratch(document)) {
          yield RuleDecision.skip(decision.reason() + "; " + DESCRIBED_FIGURES, PRESENT, null);
        }
        yield RuleDecision.apply(decision.reason(), PRESENT, "Document rebuilt from content");
      }
    };
  }

  /** Whether any page has a widget annotation (a form field): such tags are never discarded. */
  private static boolean hasWidgets(PDDocument document) {
    for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
      if (page.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.ANNOTS) instanceof org.apache.pdfbox.cos.COSArray annots) {
        for (int i = 0; i < annots.size(); i++) {
          if (annots.getObject(i) instanceof org.apache.pdfbox.cos.COSDictionary annot
              && org.apache.pdfbox.cos.COSName.WIDGET.equals(annot.getCOSName(org.apache.pdfbox.cos.COSName.SUBTYPE))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static final String DESCRIBED_FIGURES = "the tags are kept because figures in them have alternative text that could not be carried to the rebuilt tags";
  static final String DESCRIBED_OTHERS = "the tags are kept because elements other than figures in them have alternative, replacement or"
      + " expansion text that could not be kept as marked-content properties of exactly their content";
  /** How an applied reason ends when descriptions of other elements were kept ({@code "; descriptions of N element(s)" + this}). */
  public static final String KEPT_SUFFIX = " other than figures kept as marked-content properties";

  /**
   * Discards the tags ({@link TagStripper}) after moving the descriptions of elements other than
   * Figures onto their marked content ({@link DescribedElements}); returns what was kept.
   */
  private static DescribedElements.Outcome strip(PDDocument document) throws DescribedElements.Refused, IOException, UnsupportedContentException {
    DescribedElements.Outcome kept = DescribedElements.carry(document);
    TagStripper.strip(document);
    return kept;
  }

  /**
   * The oracle for a rebuilt copy's extracted text: {@code original} with the other elements' descriptions kept on marked
   * content and its tags discarded. Returns how many elements' {@code /Alt}, {@code /ActualText} or {@code /E} were kept.
   */
  public static int describedBaseline(PDDocument original) throws Exception {
    if (original.getDocumentCatalog().getStructureTreeRoot() == null) {
      return 0;
    }
    return strip(original).described();
  }

  /**
   * Whether described Figures can be carried 1:1 to the rebuilt tags: the whole rebuild runs on a scratch copy, so apply never
   * half-applies. A programming error ({@link IllegalStateException}) is not hidden as a refusal; the rebuild fails at apply.
   */
  private boolean carriesOnScratch(PDDocument document) {
    try (PDDocument scratch = Loader.loadPDF(RenderIdentity.scratchCopy(document))) {
      DescribedFigures.Snapshot snapshot = DescribedFigures.read(scratch);
      strip(scratch);
      build(scratch);
      return DescribedFigures.carry(scratch, snapshot) > 0;
    } catch (IllegalStateException e) {
      return true;
    } catch (DescribedFigures.Refused | DescribedElements.Refused | UnsupportedContentException | IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * Strips and re-tags a scratch copy (the document itself is not touched); {@code null} when both
   * succeed and, where painting operators with no path would be dropped, rendering and text stay
   * identical; otherwise what went wrong.
   */
  private static String rebuildRefusal(PDDocument document) {
    Plan plan;
    byte[] described;
    try (PDDocument scratch = Loader.loadPDF(RenderIdentity.scratchCopy(document))) {
      // The kept descriptions, tags discarded, are the baseline (describedBaseline): a kept /ActualText
      // is meant to change what a reader extracts; the re-tagging after it must change nothing.
      strip(scratch);
      described = RenderIdentity.scratchCopy(scratch);
      plan = ContentTagger.analyze(scratch, true);
    } catch (UnsupportedContentException | DescribedElements.Refused e) {
      return e.getMessage();
    } catch (IOException | RuntimeException e) {
      return "the content streams could not be analysed: " + e.getClass().getSimpleName();
    }
    String changed;
    try (PDDocument baseline = Loader.loadPDF(described)) {
      changed = RenderIdentity.changes(baseline, plan.pagesWithStrayPaints(), scratch -> ContentTagger.apply(scratch, ContentTagger.analyze(scratch, true)));
    } catch (IOException e) {
      return "the content streams could not be analysed: " + e.getClass().getSimpleName();
    }
    return changed == null ? null : "rebuilding them would change " + changed;
  }

  @Override
  public WorkerResult.Action apply(PDDocument document, RuleDecision decision) {
    Decision strategy = decidedDocument == document ? decided : null;
    decidedDocument = null;
    decided = null;
    if (!decision.applicable() || strategy == null) {
      return new WorkerResult.Action(id(), ActionOutcome.SKIPPED, decision.reason(), decision.before(), decision.after());
    }
    if (strategy.kind() == Kind.UNTAGGED) {
      if (document.getDocumentCatalog().getStructureTreeRoot() != null) {
        return new WorkerResult.Action(id(), ActionOutcome.FAILED, "document gained a StructTreeRoot between inspect and apply", decision.before(), null);
      }
      try {
        return tag(document, strategy, decision, null, DescribedElements.Outcome.NONE);
      } catch (UnsupportedContentException | IOException | RuntimeException e) {
        return new WorkerResult.Action(
            id(), ActionOutcome.FAILED, "could not rewrite content streams (" + e.getClass().getSimpleName() + ")", decision.before(), decision.after());
      }
    }
    try {
      DescribedFigures.Snapshot described = DescribedFigures.read(document);
      DescribedElements.Outcome kept = strip(document);
      return tag(document, strategy, decision, described, kept);
    } catch (DescribedFigures.Refused e) {
      return new WorkerResult.Action(
          id(), ActionOutcome.FAILED, "could not rebuild the tags from the content (the described figures changed since inspection)", decision.before(), decision.after());
    } catch (DescribedElements.Refused e) {
      return new WorkerResult.Action(
          id(), ActionOutcome.FAILED, "could not rebuild the tags from the content (the described elements changed since inspection)", decision.before(), decision.after());
    } catch (UnsupportedContentException | IOException | RuntimeException e) {
      return new WorkerResult.Action(
          id(), ActionOutcome.FAILED, "could not rebuild the tags from the content (" + e.getClass().getSimpleName() + ")", decision.before(), decision.after());
    }
  }

  /** What {@link #build} wrote: the tagger's summary, why inference was skipped (null = inferred), the grouping's counts. */
  private record Built(Summary summary, String skipped, LayoutGrouping grouping) {}

  /** Tags the (untagged or stripped) document from its layout, or with basic content-order tags when inference is refused. */
  private Built build(PDDocument document) throws IOException, UnsupportedContentException {
    LayoutGrouping grouping = new LayoutGrouping(settings, rightToLeft(document), runsSource);
    Plan plan;
    String skipped = null;
    try {
      plan = ContentTagger.analyze(document, true, grouping);
    } catch (UnsupportedContentException e) {
      skipped = e.getMessage();
      plan = ContentTagger.analyze(document, true);
    } catch (IllegalStateException e) {
      // A programming error (an inconsistent grouping), not a property of the document: fail closed, the ladder reruns.
      throw e;
    } catch (IOException | RuntimeException e) {
      skipped = "the page layout could not be read (" + e.getClass().getSimpleName() + ")";
      plan = ContentTagger.analyze(document, true);
    }
    return new Built(ContentTagger.apply(document, plan), skipped, grouping);
  }

  /**
   * {@link #build}, then (stripped documents) the described Figures' descriptions carried to the
   * rebuilt Figures; {@code kept}: elements other than Figures whose descriptions the strip kept.
   */
  private WorkerResult.Action tag(PDDocument document, Decision strategy, RuleDecision decision, DescribedFigures.Snapshot described, DescribedElements.Outcome kept)
      throws IOException, UnsupportedContentException {
    Built built = build(document);
    if (document.getDocumentCatalog().getStructureTreeRoot() == null) {
      return new WorkerResult.Action(id(), ActionOutcome.FAILED, "structure tree did not persist", decision.before(), decision.after());
    }
    // Every Figure of the upload tree sits in a grouping element (Document): it is block-level (/Placement /Block).
    InlineBlocks.figurePlacement(document);
    int carried = 0;
    if (described != null && !described.isEmpty()) {
      try {
        carried = DescribedFigures.carry(document, described);
      } catch (DescribedFigures.Refused e) {
        // Inspection proved the mapping on a scratch copy; a difference now fails closed and the ladder reruns.
        return new WorkerResult.Action(
            id(), ActionOutcome.FAILED, "alternative text could not be carried to the rebuilt tags", decision.before(), decision.after());
      }
    }
    String reason = built.skipped() != null
        ? strategy.reason() + "; inference skipped: " + built.skipped() + "; basic tags in content order; " + built.summary().describe()
        : strategy.reason() + built.summary().notes() + describe(built.grouping().counts());
    if (carried > 0) {
      reason += "; alternative text carried to " + carried + (carried == 1 ? " rebuilt figure" : " rebuilt figures");
    }
    if (kept.elements() > 0) {
      reason += "; descriptions of " + kept.elements() + (kept.elements() == 1 ? " element" : " elements") + KEPT_SUFFIX;
    }
    if (kept.linksToAnnotations() > 0) {
      reason += "; alternative text of " + kept.linksToAnnotations() + (kept.linksToAnnotations() == 1 ? " link" : " links")
          + " without marked content copied to the link annotation's missing /Contents";
    }
    if (kept.linksNotCarried() > 0) {
      reason += "; alternative text of " + kept.linksNotCarried() + (kept.linksNotCarried() == 1 ? " link" : " links")
          + " without marked content not carried (the link annotations have their own /Contents)";
    }
    return new WorkerResult.Action(id(), ActionOutcome.APPLIED, reason, decision.before(), decision.after());
  }

  private static final String INFERRED = "; inferred paragraphs ";
  private static final String SAMPLE = "; running artifact sample: \"";
  private static final Pattern COUNTS = Pattern.compile(
      "; inferred paragraphs (\\d+), headings (\\d+), lists (\\d+), list items (\\d+), tables (\\d+), running artifacts (\\d+),"
          + " pages reordered (\\d+), pages kept in content order (\\d+), fallbacks (\\d+)");
  private static final Set<String> RIGHT_TO_LEFT = Set.of("ar", "he", "fa", "ur", "yi", "ps");

  /** The inferred counts as the reason states them, the running-artifact sample last. */
  static String describe(LayoutGrouping.Counts c) {
    return INFERRED + c.paragraphs() + ", headings " + c.headings() + ", lists " + c.lists() + ", list items " + c.listItems() + ", tables " + c.tables()
        + ", running artifacts " + c.runningArtifacts() + ", pages reordered " + c.pagesReordered() + ", pages kept in content order " + c.pagesKept()
        + ", fallbacks " + c.fallbacks() + (c.runningSample() != null ? SAMPLE + c.runningSample() + "\"" : "");
  }

  /**
   * The counts of an applied action of this rule whose structure was inferred from the layout (parsed
   * from its reason); empty for any other action, including a run that fell back to basic tags.
   */
  public static Optional<LayoutGrouping.Counts> inferred(WorkerResult.Action action) {
    if (action.ruleId() != WorkerResult.RuleId.PDF_STRUCTURE_INFERRED_V1 || action.outcome() != ActionOutcome.APPLIED || action.reason() == null) {
      return Optional.empty();
    }
    Matcher m = COUNTS.matcher(action.reason());
    if (!m.find()) {
      return Optional.empty();
    }
    String sample = null;
    int at = action.reason().indexOf(SAMPLE, m.end());
    if (at == m.end()) {
      int end = action.reason().lastIndexOf('"');
      sample = end >= at + SAMPLE.length() ? action.reason().substring(at + SAMPLE.length(), end) : "";
    }
    int[] n = new int[9];
    for (int i = 0; i < 9; i++) {
      n[i] = Integer.parseInt(m.group(i + 1));
    }
    return Optional.of(new LayoutGrouping.Counts(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], sample));
  }

  /** Right-to-left when the catalog /Lang's primary subtag is Arabic, Hebrew, Persian, Urdu, Yiddish or Pashto. */
  static boolean rightToLeft(PDDocument document) {
    String lang = document.getDocumentCatalog().getLanguage();
    if (lang == null || lang.isBlank()) {
      return false;
    }
    return RIGHT_TO_LEFT.contains(lang.strip().split("[-_]", 2)[0].toLowerCase(Locale.ROOT));
  }
}
