package com.koldera.pdf.pipeline;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.Action;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.fonts.FontEmbeddingRule;
import com.koldera.pdf.fonts.FontUnicodeRule;
import com.koldera.pdf.fonts.GlyphAudit;
import com.koldera.pdf.generated.GeneratedStructureRule;
import com.koldera.pdf.inference.LayoutGrouping;
import com.koldera.pdf.inference.StructureInferenceRule;
import com.koldera.pdf.integrity.CopyIntegrity;
import com.koldera.pdf.integrity.CopyWriter;
import com.koldera.pdf.preflight.ContentProbe;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.preflight.PreflightOutcome;
import com.koldera.pdf.repair.FiguresWithoutAltRule;
import com.koldera.pdf.repair.TaggedStructureRepairRule;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.structure.GlyphInventory;
import com.koldera.pdf.rules.LanguageCorrectionRule;
import com.koldera.pdf.rules.PdfUaIdentifierStep;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RuleDecision;
import com.koldera.pdf.rules.TitleRule;
import com.koldera.pdf.validation.PdfValidator;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Validate, preflight, baseline veraPDF, plan, apply to a copy, save ({@link CopyWriter}), reload, check invariants
 * ({@link CopyIntegrity}), final veraPDF, diff, then publish or roll back ({@code rolled_back}). The original is never
 * opened for writing. The gate depends on mode and origin ({@linkplain #structureRegression}, {@linkplain #generatedRegression});
 * a rolled-back run is retried down a ladder. {@code manualReviewRequired} stays true, also when the identifier is written.
 */
public final class PdfProcessor implements Main.JobRunner {

  static final String BASELINE_REPORT = "baseline-report.json";
  static final String FINAL_REPORT = "final-report.json";
  static final String REMEDIATED = "remediated.pdf";
  private static final String CANDIDATE = "candidate.pdf";
  private static final String IDENTIFIED = "candidate-pdfua.pdf";
  private static final String IDENTIFIED_REPORT = "final-report-pdfua.json";
  private static final String FIRST_RUN_REPORT = "final-report-first-run.json";

  /**
   * Structure gate allow-list, clause prefixes: checks that can only exist once a structure tree exists (figures/alt 7.3,
   * headings 7.4, tables 7.5, 7.17, annotations 7.18, language 7.2). A newly failing rule outside this list rolls the job
   * back. {@code 7.2} is listed whole: its table/list containment checks cannot fire on the {@code Document > P/Figure} tree
   * this mode writes, and the language checks that can fire are spread across the same clause.
   */
  static final List<String> STRUCTURE_GATE_ALLOWED_NEW_CLAUSES = List.of("7.2", "7.3", "7.4", "7.5", "7.17", "7.18");

  /**
   * Exact rule ids allowed to appear newly: {@code 5-1} (no pdfuaid identifier, never written by the
   * rules; only the {@link PdfUaIdentifierStep} of a fix run may add it, after this gate) and {@code 7.1-9} (no dc:title because the Info dictionary had none and a title is
   * never invented); both can only fail once the metadata stream the baseline lacked exists.
   */
  static final List<String> STRUCTURE_GATE_ALLOWED_NEW_RULES = List.of("5-1", "7.1-9");

  /** After structure tagging these must not fail at all, whatever the baseline said. */
  static final List<String> STRUCTURE_GATE_MUST_REACH_ZERO = List.of("7.1-3", "7.1-11", "6.2-1", "7.1-8");

  private final PdfPreflight preflight;
  private final PdfValidator validator;
  private final List<RemediationRule> rules;

  public PdfProcessor(PdfPreflight preflight, PdfValidator validator, List<RemediationRule> rules) {
    this.preflight = preflight;
    this.validator = validator;
    this.rules = List.copyOf(rules);
  }

  @Override
  public WorkerResult run(WorkerRequest request, JobWorkspace workspace) {
    return process(request, workspace);
  }

  public WorkerResult process(WorkerRequest request, JobWorkspace workspace) {
    Instant deadline = Instant.now().plusSeconds(request.limits().timeoutSeconds());
    Path candidate = workspace.scratchDirectory().resolve(CANDIDATE);
    try {
      return processWithinBudget(request, workspace, candidate, deadline);
    } finally {
      deleteQuietly(candidate);
      deleteQuietly(workspace.scratchDirectory().resolve(IDENTIFIED));
      deleteQuietly(workspace.scratchDirectory().resolve(IDENTIFIED_REPORT));
      deleteQuietly(workspace.scratchDirectory().resolve(FIRST_RUN_REPORT));
    }
  }

  private WorkerResult processWithinBudget(
      WorkerRequest request, JobWorkspace workspace, Path candidate, Instant deadline) {
    PreflightOutcome pre = preflight.inspect(workspace.inputFile(), request);
    ResultAssembler result = new ResultAssembler(request, workspace, pre);
    if (!pre.accepted()) {
      return result.build(Status.UNSUPPORTED, result.remediationWhenNotStarted(), pre.rejectCode(), pre.reason());
    }

    ValidationOutcome baseline =
        validator.validate(workspace.inputFile(), workspace.outputFile(BASELINE_REPORT), remaining(deadline));
    result.baseline(baseline, BASELINE_REPORT);
    if (baseline.isError()) {
      return result.build(Status.FAILED, result.remediationWhenNotStarted(), baseline.errorCode(), baseline.errorMessage());
    }

    byte[] originalBytes;
    try {
      originalBytes = Files.readAllBytes(workspace.inputFile());
    } catch (IOException e) {
      return result.build(Status.FAILED, result.remediationWhenNotStarted(), ErrorCode.WORKER_ERROR, "input became unreadable");
    }

    Attempt first = remediate(request, workspace, pre, baseline, originalBytes, candidate, deadline, result, Set.of());
    if (first.generated() != null) {
      return fallback(request, workspace, pre, baseline, originalBytes, candidate, deadline, first);
    }
    WorkerResult firstResult = first.result();
    if (!rolledBackUploadFix(request, firstResult)) {
      return firstResult;
    }
    Ladder ladder = new Ladder(request, workspace, pre, baseline, originalBytes, candidate, deadline, firstResult);
    // Font rungs first: a failure the font steps explain costs only the font steps.
    WorkerResult last = firstResult;
    WorkerResult programRun = null;
    if (touched(firstResult, FONT_PROGRAM_STEPS) && fontAttributable(firstResult)) {
      programRun = ladder.run(FONT_PROGRAM_STEPS);
      if (programRun.status() == Status.COMPLETED) {
        return withoutSteps(programRun, firstResult, FONT_PROGRAM_STEPS, FONT_PROGRAM_NOTE);
      }
      last = programRun;
      ladder.fontsExcluded.addAll(FONT_PROGRAM_STEPS);
    }
    if (rolledBackUploadFix(request, last) && touched(last, FONT_REPAIRS) && fontAttributable(last)) {
      WorkerResult withoutFonts = ladder.run(FONT_REPAIRS);
      if (withoutFonts.status() == Status.COMPLETED) {
        WorkerResult published = withoutSteps(withoutFonts, last, FONT_REPAIRS, FONTS_NOTE);
        return programRun == null ? published : withoutSteps(published, firstResult, FONT_PROGRAM_STEPS, FONT_PROGRAM_NOTE);
      }
      if (!fontAttributable(withoutFonts)) {
        // Failing again for font reasons without the font steps: they were not the cause, so later repeats keep them.
        ladder.fontsExcluded.addAll(FONT_REPAIRS);
      }
    }
    WorkerResult existing = existingLadder(ladder);
    if (existing != null) {
      return existing;
    }
    if (touched(firstResult, FONT_REPAIRS) && touched(firstResult, STRUCTURE_STEPS)) {
      // Without a touched structure step, leaving them out adds nothing to the repeats already made.
      Set<RuleId> all = EnumSet.copyOf(STRUCTURE_STEPS);
      all.addAll(UPLOAD_REPAIRS);
      all.addAll(FONT_REPAIRS);
      if (!ladder.ran(all)) {
        WorkerResult none = ladder.run(all);
        if (none.status() == Status.COMPLETED) {
          return withoutSteps(withoutSteps(withoutSteps(none, firstResult, STRUCTURE_STEPS, STRUCTURE_NOTE), firstResult, UPLOAD_REPAIRS, REPAIRS_NOTE),
              firstResult, FONT_REPAIRS, FONTS_NOTE);
        }
      }
    }
    return firstResult;
  }

  /**
   * The repeats of one rolled-back upload {@code fix} run: the context of {@link #rerun}, the font ids
   * the font rungs already left out (added to every later repeat's excluded set), and the excluded sets
   * already run.
   */
  private final class Ladder {
    final WorkerRequest request;
    final JobWorkspace workspace;
    final PreflightOutcome pre;
    final ValidationOutcome baseline;
    final byte[] originalBytes;
    final Path candidate;
    final Instant deadline;
    final WorkerResult first;
    final Set<RuleId> fontsExcluded = EnumSet.noneOf(RuleId.class);
    private final List<Set<RuleId>> ran = new ArrayList<>();

    Ladder(WorkerRequest request, JobWorkspace workspace, PreflightOutcome pre, ValidationOutcome baseline, byte[] originalBytes, Path candidate,
        Instant deadline, WorkerResult first) {
      this.request = request;
      this.workspace = workspace;
      this.pre = pre;
      this.baseline = baseline;
      this.originalBytes = originalBytes;
      this.candidate = candidate;
      this.deadline = deadline;
      this.first = first;
    }

    WorkerResult run(Set<RuleId> excluded) {
      Set<RuleId> set = EnumSet.noneOf(RuleId.class);
      set.addAll(excluded);
      ran.add(set);
      return rerun(request, workspace, pre, baseline, originalBytes, candidate, deadline, set);
    }

    boolean ran(Set<RuleId> excluded) {
      return ran.contains(excluded);
    }

    /** {@code group} plus the font ids the font rungs left out. */
    Set<RuleId> withFonts(Set<RuleId> group) {
      Set<RuleId> set = EnumSet.noneOf(RuleId.class);
      set.addAll(group);
      set.addAll(fontsExcluded);
      return set;
    }

    /** {@code result} with the font steps the font rungs left out put back as they were in the first run. */
    WorkerResult withFontSteps(WorkerResult result) {
      if (fontsExcluded.isEmpty()) {
        return result;
      }
      return withoutSteps(result, first, fontsExcluded, fontsExcluded.containsAll(FONT_REPAIRS) ? FONTS_NOTE : FONT_PROGRAM_NOTE);
    }
  }

  /**
   * The structure and upload-repair ladder, on the first run, with the font ids the font
   * rungs left out excluded in every repeat; {@code null} when no repeat completes.
   */
  private WorkerResult existingLadder(Ladder ladder) {
    WorkerResult firstResult = ladder.first;
    if (!touched(firstResult, STRUCTURE_STEPS)) {
      // Phase-1 behaviour: only the upload repairs are left out.
      if (!touched(firstResult, UPLOAD_REPAIRS)) {
        return null;
      }
      WorkerResult second = ladder.run(ladder.withFonts(UPLOAD_REPAIRS));
      return second.status() == Status.COMPLETED ? ladder.withFontSteps(withoutSteps(second, firstResult, UPLOAD_REPAIRS, REPAIRS_NOTE)) : null;
    }
    // The ladder: without the structure steps (the basic tagger instead, as before the structure steps), then without the upload repairs too.
    WorkerResult second = ladder.run(ladder.withFonts(STRUCTURE_STEPS));
    if (second.status() == Status.COMPLETED) {
      return ladder.withFontSteps(withoutSteps(second, firstResult, STRUCTURE_STEPS, STRUCTURE_NOTE));
    }
    if (!rolledBackUploadFix(ladder.request, second) || !touched(second, UPLOAD_REPAIRS)) {
      return null;
    }
    Set<RuleId> neither = EnumSet.copyOf(STRUCTURE_STEPS);
    neither.addAll(UPLOAD_REPAIRS);
    WorkerResult third = ladder.run(ladder.withFonts(neither));
    if (third.status() != Status.COMPLETED) {
      return null;
    }
    return ladder.withFontSteps(withoutSteps(withoutSteps(third, second, UPLOAD_REPAIRS, REPAIRS_NOTE), firstResult, STRUCTURE_STEPS, STRUCTURE_NOTE));
  }

  /**
   * The font repairs of uploads. When a rolled-back {@code fix} upload run touched one of them
   * and its failure is {@linkplain #fontAttributable attributable to fonts}, the run is repeated without
   * them before the structure ladder (see {@link #processWithinBudget}).
   */
  public static final Set<RuleId> FONT_REPAIRS =
      EnumSet.of(RuleId.PDF_FONT_DICTIONARY_V1, RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1, RuleId.PDF_FONT_TOUNICODE_V1);

  /**
   * The font steps that replace or embed a font program (a subset of {@link #FONT_REPAIRS}); a run whose failure the font
   * steps explain is repeated without them first (rung A), keeping the other font repairs.
   */
  public static final Set<RuleId> FONT_PROGRAM_STEPS = EnumSet.of(RuleId.PDF_FONT_INVISIBLE_TEXT_V1, RuleId.PDF_FONT_EMBEDDING_V1);

  public static final String FONTS_NOTE = "; the copy was produced again without the font repairs";

  public static final String FONT_PROGRAM_NOTE = "; the copy was produced again without the font program repairs";

  /**
   * A rolled-back run whose failure the font steps explain: an integrity regression that is the glyph
   * audit's text check, or whose final validation grew only font checks (clause 7.21), at least one.
   */
  static boolean fontAttributable(WorkerResult r) {
    if (r.error() == null || r.error().code() != ErrorCode.INTEGRITY_REGRESSION) {
      return false;
    }
    if (r.error().message() != null && r.error().message().startsWith(GlyphAudit.FAILURE_PREFIX)) {
      return true;
    }
    if (r.finalValidation() == null) {
      return false;
    }
    Map<String, Integer> before = new java.util.HashMap<>();
    if (r.baseline() != null) {
      r.baseline().failedRules().forEach(f -> before.put(f.ruleId(), f.count()));
    }
    boolean any = false;
    for (WorkerResult.FailedRule f : r.finalValidation().failedRules()) {
      if (f.count() > before.getOrDefault(f.ruleId(), 0)) {
        if (!isFontCheck(clauseAndTest(f.ruleId()))) {
          return false;
        }
        any = true;
      }
    }
    return any;
  }

  /**
   * The per-upload structure steps. When a {@code fix} upload run in which one of them applied, rolled back or failed is
   * rolled back, it is repeated without all of them (basic tagger, see {@link #processWithinBudget}), then without the
   * {@link #UPLOAD_REPAIRS} as well. If no repeat completes, the first run's failure is returned unchanged.
   */
  public static final Set<RuleId> STRUCTURE_STEPS =
      EnumSet.of(RuleId.PDF_STRUCTURE_INFERRED_V1, RuleId.PDF_UNTAGGED_CONTENT_V1, RuleId.PDF_TAGGED_STRUCTURE_REPAIR_V1, RuleId.PDF_FIGURES_WITHOUT_ALT_V1);

  public static final String STRUCTURE_NOTE = "; the copy was produced again without the structure inference and repair steps";

  /**
   * The upload repair rules. Each is gated like every other rule; when a run that applied
   * one of them is rolled back, the run is repeated once without all of them (see {@link
   * #processWithinBudget}), and a generated run's fallback never runs them.
   */
  public static final Set<RuleId> UPLOAD_REPAIRS =
      EnumSet.of(RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1, RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1);

  public static final String REPAIRS_NOTE = "; the copy was produced again without the link, tab-order and language repairs";

  /** An upload fix run rolled back by a gate or a rule failure. */
  private static boolean rolledBackUploadFix(WorkerRequest request, WorkerResult result) {
    return request.isFix() && !request.isHtmlGenerated() && result.status() == Status.FAILED && result.remediationStatus() == RemediationStatus.ROLLED_BACK
        && result.error() != null && (result.error().code() == ErrorCode.INTEGRITY_REGRESSION || result.error().code() == ErrorCode.WORKER_ERROR);
  }

  /** Whether a step of {@code group} took part in the rolled-back run: applied (listed as rolled back) or failed. */
  private static boolean touched(WorkerResult result, Set<RuleId> group) {
    return result.actions().stream()
        .anyMatch(a -> group.contains(a.ruleId()) && (a.outcome() == ActionOutcome.APPLIED || a.outcome() == ActionOutcome.ROLLED_BACK || a.outcome() == ActionOutcome.FAILED));
  }

  /**
   * The completed run {@code again} (without the steps of {@code group}) with those steps put back in their places with the
   * outcome of the rolled-back run {@code first}: an applied step is listed as {@code rolled_back}, a rolled-back or failed
   * one gets {@code first}'s error and {@code note} appended to its reason.
   */
  private static WorkerResult withoutSteps(WorkerResult again, WorkerResult first, Set<RuleId> group, String note) {
    List<Action> actions = new ArrayList<>(again.actions());
    List<Action> firstActions = first.actions();
    for (int i = 0; i < firstActions.size(); i++) {
      Action a = firstActions.get(i);
      if (!group.contains(a.ruleId())) {
        continue;
      }
      Action listed = a.outcome() == ActionOutcome.ROLLED_BACK || a.outcome() == ActionOutcome.FAILED
          ? new Action(a.ruleId(), a.outcome(), a.reason() + "; " + first.error().message() + note, a.before(), a.after())
          : a;
      actions.add(Math.min(i, actions.size()), listed);
    }
    return new WorkerResult(
        again.specVersion(), again.jobId(), again.status(), again.originalSha256(), again.engineVersion(), again.baseline(),
        again.finalValidation(), again.remediationStatus(), actions, again.findings(), again.artifacts(), again.manualReviewRequired(),
        again.error());
  }

  /**
   * One more remediation run with {@code excluded} rules left out, reusing the baseline. The first
   * run's final report is stashed and restored when this run does not complete; returns this run's
   * result either way (when the report cannot even be stashed, a failure that stands for a run that
   * did not complete).
   */
  private WorkerResult rerun(
      WorkerRequest request, JobWorkspace workspace, PreflightOutcome pre, ValidationOutcome baseline, byte[] originalBytes, Path candidate,
      Instant deadline, Set<RuleId> excluded) {
    Path report = workspace.outputFile(FINAL_REPORT);
    Path stash = workspace.scratchDirectory().resolve(FIRST_RUN_REPORT);
    boolean stashed = false;
    try {
      if (Files.exists(report)) {
        publish(report, stash);
        stashed = true;
      }
    } catch (IOException e) {
      return new ResultAssembler(request, workspace, pre)
          .build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.WORKER_ERROR, "the first run's report could not be set aside; the run was not repeated");
    }
    ResultAssembler again = new ResultAssembler(request, workspace, pre);
    again.baseline(baseline, BASELINE_REPORT);
    WorkerResult second = remediate(request, workspace, pre, baseline, originalBytes, candidate, deadline, again, excluded).result();
    if (second.status() != Status.COMPLETED) {
      deleteQuietly(report);
      if (stashed) {
        try {
          publish(stash, report);
        } catch (IOException ignored) {
          // best effort: the first failure is returned either way
        }
      }
      return second;
    }
    deleteQuietly(stash);
    return second;
  }

  /**
   * One remediation attempt's result; {@code generated} is set only when a generated {@code fix} run
   * failed because of {@code PDF_GENERATED_STRUCTURE_V1} (it reported {@code failed}) or the generated
   * gate rolled the copy back: it is the action to list for that rule if the {@linkplain #fallback
   * fallback} publishes, at {@code index} in the action list.
   */
  private record Attempt(WorkerResult result, Action generated, int index) {
    static Attempt done(WorkerResult result) {
      return new Attempt(result, null, -1);
    }
  }

  static final String FALLBACK_NOTE =
      "; the copy was produced again without this normalization and without the PDF/UA identifier step";

  /**
   * A generated {@code fix} run that failed only because of the generated-PDF steps is run once more as an upload (title,
   * language, display title, role map; no normalization, no identifier, no {@link #UPLOAD_REPAIRS} or {@link #STRUCTURE_STEPS}),
   * reusing the baseline, with {@code PDF_GENERATED_STRUCTURE_V1} listed as failed or rolled back. If that run does not
   * complete either, the first run's failure is returned unchanged.
   */
  private WorkerResult fallback(
      WorkerRequest request, JobWorkspace workspace, PreflightOutcome pre, ValidationOutcome baseline, byte[] originalBytes,
      Path candidate, Instant deadline, Attempt first) {
    Set<RuleId> prePhase2a = EnumSet.copyOf(STRUCTURE_STEPS);
    prePhase2a.addAll(UPLOAD_REPAIRS);
    prePhase2a.addAll(FONT_REPAIRS);
    // Uploads may get the identifier; this upload-shaped repeat of a generated run never does.
    prePhase2a.add(RuleId.PDF_UA_IDENTIFIER_V1);
    WorkerResult second = rerun(asUpload(request), workspace, pre, baseline, originalBytes, candidate, deadline, prePhase2a);
    if (second.status() != Status.COMPLETED) {
      return first.result();
    }
    List<Action> actions = new ArrayList<>(second.actions());
    // The generated rule's place is right after the basic structure rule; the upload-shaped run may list rules the
    // generated run does not (the upload repairs), so the first run's index is only the fallback.
    int basic = -1;
    for (int i = 0; i < actions.size(); i++) {
      if (actions.get(i).ruleId() == RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1) {
        basic = i;
      }
    }
    actions.add(basic >= 0 ? basic + 1 : Math.min(first.index(), actions.size()), first.generated());
    return new WorkerResult(
        second.specVersion(), second.jobId(), second.status(), second.originalSha256(), second.engineVersion(), second.baseline(),
        second.finalValidation(), second.remediationStatus(), actions, second.findings(), second.artifacts(), second.manualReviewRequired(),
        second.error());
  }

  /** The same request as if the PDF had been uploaded: exactly the pre-generated-steps behaviour. */
  private static WorkerRequest asUpload(WorkerRequest request) {
    return new WorkerRequest(
        request.specVersion(), request.jobId(), request.inputPath(), request.outputDirectory(), request.mode(), request.profile(),
        request.explicitLanguage(), request.explicitTitle(), request.sourceFileName(), WorkerRequest.Origin.UPLOAD, request.limits());
  }

  /** The action to list for the generated rule after a fallback, or null when there is no such rule in {@code actions}. */
  private static Attempt generatedFailure(WorkerResult failure, List<Action> actions, String gateRegression) {
    for (int i = 0; i < actions.size(); i++) {
      Action a = actions.get(i);
      if (a.ruleId() != RuleId.PDF_GENERATED_STRUCTURE_V1) {
        continue;
      }
      if (gateRegression == null) {
        return a.outcome() == ActionOutcome.FAILED
            ? new Attempt(failure, new Action(a.ruleId(), ActionOutcome.FAILED, a.reason() + FALLBACK_NOTE, a.before(), a.after()), i)
            : Attempt.done(failure);
      }
      Action listed = a.outcome() == ActionOutcome.APPLIED
          ? new Action(a.ruleId(), ActionOutcome.ROLLED_BACK, "rolled back: " + a.reason() + "; " + gateRegression + FALLBACK_NOTE, a.before(), a.after())
          : new Action(a.ruleId(), a.outcome(), a.reason() + "; " + gateRegression + FALLBACK_NOTE, a.before(), a.after());
      return new Attempt(failure, listed, i);
    }
    return Attempt.done(failure);
  }

  private Attempt remediate(
      WorkerRequest request, JobWorkspace workspace, PreflightOutcome pre, ValidationOutcome baseline, byte[] originalBytes,
      Path candidate, Instant deadline, ResultAssembler result, Set<RuleId> excluded) {
    boolean structureApplied = false;
    List<Action> actions;
    String glyphsBefore = null;
    CopyIntegrity.Report integrityBefore;
    Optional<FontUnicodeRule.Unmapped> fontsUnmapped = Optional.empty();
    FindingsBuilder findings;
    try (PDDocument document = Loader.loadPDF(originalBytes)) {
      findings = new FindingsBuilder().machineChecks(baseline);
      List<Integer> imagePages = ContentProbe.pagesWithImages(document);

      if (!request.isMutating()) {
        // analyze: finding order is part of the output and stays machine checks -> images -> signed.
        findings.imagesOnPages(imagePages);
        if (pre.signed()) {
          findings.signed();
        }
        result.findings(findings.build());
        return Attempt.done(result.build(pre.signed() ? Status.UNSUPPORTED : Status.COMPLETED, RemediationStatus.NOT_REQUESTED, null, null));
      }
      // A signed document never reaches this point: preflight refuses mutation before validation.

      if (request.addsStructure()) {
        glyphsBefore = glyphInventory(document);
      }
      integrityBefore = CopyIntegrity.inspect(document);
      actions = planAndApply(document, request, excluded);
      if (surveysFonts(request)) {
        // From the fonts as they will be published, whether or not the font rule ran in this repeat.
        fontsUnmapped = FontUnicodeRule.unmapped(document);
      }
      result.actions(actions);
      structureApplied = applied(actions, RuleId.PDF_BASIC_STRUCTURE_FROM_CONTENT_V1) || applied(actions, RuleId.PDF_STRUCTURE_INFERRED_V1);
      // Only when a figure was actually marked as decoration (not when the rule only retagged paragraphs).
      boolean figuresMarked = actions.stream()
          .anyMatch(a -> FiguresWithoutAltRule.artifactedPerPage(a).map(perPage -> !perPage.isEmpty()).orElse(false));
      findings.imagesOnPages(imagePages, structureApplied, figuresMarked);
      if (structureApplied) {
        // Structure inferred from the layout has its own review finding; basic content-order tags (also the inference's fallback) keep theirs.
        Optional<LayoutGrouping.Counts> inferred =
            actions.stream().map(StructureInferenceRule::inferred).flatMap(Optional::stream).findFirst();
        if (inferred.isPresent()) {
          findings.structureInferred(inferred.get());
        } else {
          findings.basicStructureAdded(figuresMarked);
        }
      }
      result.findings(findings.build());
      boolean anyApplied = actions.stream().anyMatch(a -> a.outcome() == ActionOutcome.APPLIED);
      boolean anyFailed = actions.stream().anyMatch(a -> a.outcome() == ActionOutcome.FAILED);
      if (anyFailed) {
        result.rollBackAppliedActions();
        WorkerResult failure =
            result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.WORKER_ERROR, "a remediation rule reported failure; nothing was published");
        return request.isFix() && request.isHtmlGenerated() ? generatedFailure(failure, actions, null) : Attempt.done(failure);
      }
      if (!anyApplied) {
        return Attempt.done(result.build(Status.COMPLETED, RemediationStatus.NO_APPLICABLE_FIX, null, null));
      }
      // Every run that writes a copy: the same input must give the same bytes (the identifier step keeps this /ID).
      pinDocumentId(document, pre.sha256());
      CopyWriter.save(document, candidate.toFile());
    } catch (IOException | RuntimeException e) {
      result.rollBackAppliedActions();
      return Attempt.done(result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.WORKER_ERROR, "could not apply or save the remediation copy (" + e.getClass().getSimpleName() + ")"));
    }

    // Reload the saved candidate and enforce structural invariants before spending validator time.
    PreflightOutcome post = preflight.inspect(candidate, request);
    String invariantFailure = invariantFailure(pre, post, actions, originalBytes, candidate);
    if (invariantFailure == null) {
      invariantFailure = CopyIntegrity.regression(integrityBefore, CopyIntegrity.inspect(candidate));
    }
    if (invariantFailure != null) {
      result.rollBackAppliedActions();
      return Attempt.done(result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.INTEGRITY_REGRESSION, invariantFailure));
    }

    if (!Instant.now().isBefore(deadline)) {
      result.rollBackAppliedActions();
      return Attempt.done(result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.VALIDATOR_ERROR, "job time budget exhausted before final validation"));
    }
    ValidationOutcome fin = validator.validate(candidate, workspace.outputFile(FINAL_REPORT), remaining(deadline));
    result.finalValidation(fin, FINAL_REPORT);
    if (fin.isError()) {
      result.rollBackAppliedActions();
      return Attempt.done(result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, fin.errorCode(), "final validation failed: " + fin.errorMessage()));
    }
    String regression;
    boolean generatedGate = false;
    if (structureApplied) {
      String glyphsAfter = glyphInventory(candidate);
      boolean glyphsAvailable = glyphsBefore != null && glyphsAfter != null;
      boolean glyphsUnchanged = glyphsAvailable && glyphsBefore.equals(glyphsAfter);
      regression = structureRegression(baseline.failedRuleCounts(), fin.failedRuleCounts(), glyphsUnchanged);
      if (regression == null && glyphsUnchanged) {
        List<String> recounted = fontChecksRecounted(baseline.failedRuleCounts(), fin.failedRuleCounts());
        if (!recounted.isEmpty()) {
          findings.fontChecksRecounted(recounted);
          result.findings(findings.build());
        }
      } else if (regression != null && !glyphsUnchanged && regression.contains("failures increased")) {
        regression += glyphsAvailable ? " (glyph inventory differs)" : " (glyph inventory unavailable)";
      }
    } else if (request.isFix() && request.isHtmlGenerated()) {
      generatedGate = true;
      regression = generatedRegression(baseline.failedRuleCounts(), fin.failedRuleCounts(), applied(actions, RuleId.PDF_XMP_METADATA_FROM_INFO_V1));
    } else {
      regression = regression(baseline.failedRuleCounts(), fin.failedRuleCounts(), packetWritten(actions));
    }
    if (regression != null) {
      result.rollBackAppliedActions();
      WorkerResult failure = result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.INTEGRITY_REGRESSION, regression);
      return generatedGate ? generatedFailure(failure, actions, regression) : Attempt.done(failure);
    }
    Path toPublish = candidate;
    if (PdfUaIdentifierStep.participates(request) && !excluded.contains(RuleId.PDF_UA_IDENTIFIER_V1)) {
      IdentifierRun ua = identify(request, workspace, pre, integrityBefore, fin, candidate, deadline, actions, originalBytes);
      actions.add(ua.action());
      result.actions(actions);
      if (ua.validation() != null) {
        toPublish = workspace.scratchDirectory().resolve(IDENTIFIED);
        fin = ua.validation();
        findings.pdfUaIdentifierFromMachineChecks();
      }
    }
    try {
      publish(toPublish, workspace.outputFile(REMEDIATED));
    } catch (IOException e) {
      result.rollBackAppliedActions();
      return Attempt.done(result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.WORKER_ERROR, "could not publish the remediated copy"));
    }
    if (toPublish != candidate) {
      // Only now that the identified copy is out may its report replace the pre-identifier one.
      try {
        publish(workspace.scratchDirectory().resolve(IDENTIFIED_REPORT), workspace.outputFile(FINAL_REPORT));
      } catch (IOException e) {
        deleteQuietly(workspace.outputFile(REMEDIATED));
        result.rollBackAppliedActions();
        return Attempt.done(
            result.build(Status.FAILED, RemediationStatus.ROLLED_BACK, ErrorCode.WORKER_ERROR, "could not publish the identified copy's validation report; nothing was published"));
      }
      result.finalValidation(fin, FINAL_REPORT);
    }
    result.published(REMEDIATED);
    if (titleTakenFromFileName(actions)) {
      findings.titleFromFileName();
    }
    actions.stream()
        .filter(a -> a.ruleId() == RuleId.PDF_LANG_CORRECTED_FROM_TEXT_V1 && a.outcome() == ActionOutcome.APPLIED)
        .findFirst()
        .ifPresent(a -> findings.languageCorrected(a.before(), a.after(), a.reason().endsWith(LanguageCorrectionRule.XMP_LANGUAGE_KEPT)));
    for (Action action : actions) {
      GeneratedStructureRule.untaggedText(action).ifPresent(text -> findings.untaggedTextMarkedArtifact(text.runs(), text.sample()));
      GeneratedStructureRule.headingsRenumbered(action).ifPresent(mapping -> findings.headingLevelsRenumbered(mapping, true));
      TaggedStructureRepairRule.headingsRenumbered(action).ifPresent(mapping -> findings.headingLevelsRenumbered(mapping, false));
      FiguresWithoutAltRule.artifactedPerPage(action).ifPresent(findings::figuresWithoutAltMarkedArtifact);
      FontUnicodeRule.derived(action).ifPresent(findings::fontUnicodeDerived);
      FontEmbeddingRule.embedded(action).forEach(pair -> findings.fontEmbeddedSubstitute(pair[0], pair[1]));
    }
    fontsUnmapped.ifPresent(u -> findings.fontGlyphsWithoutUnicode(u.codes(), u.fonts(), u.names()));
    // The copy is published: each machine check now says what the copy did about it (see FindingsBuilder).
    result.findings(findings.resolveMachineChecks(fin).build());
    return Attempt.done(result.build(Status.COMPLETED, RemediationStatus.APPLIED, null, null));
  }

  /** The identifier step's action; {@code validation} is the identified copy's, set only when it is to be published. */
  private record IdentifierRun(Action action, ValidationOutcome validation) {
    static IdentifierRun skipped(String reason) {
      return new IdentifierRun(new Action(RuleId.PDF_UA_IDENTIFIER_V1, ActionOutcome.SKIPPED, reason, null, null), null);
    }
  }

  /**
   * After the gate passed on a {@code fix} copy whose request asked for the declaration after human review (and whose
   * structure and figures the engine did not make up, {@link PdfUaIdentifierStep#requestBlocker}): when {@code 5-1} is the only failing check and the PAC-only structure rules
   * report nothing, writes {@code pdfuaid:part=1} into a second scratch copy, re-checks integrity and validates it again.
   * Only zero failures make that copy the one to publish; every other outcome leaves the gate-approved copy untouched and
   * records why the identifier was not written.
   */
  private IdentifierRun identify(
      WorkerRequest request, JobWorkspace workspace, PreflightOutcome pre, CopyIntegrity.Report integrityBefore, ValidationOutcome fin, Path candidate,
      Instant deadline, List<Action> actions, byte[] originalBytes) {
    String blocked = PdfUaIdentifierStep.requestBlocker(request, actions);
    if (blocked == null) {
      blocked = PdfUaIdentifierStep.blocker(fin.failedRuleCounts());
    }
    if (blocked == null) {
      blocked = PdfUaIdentifierStep.titleBlocker(candidate);
    }
    if (blocked == null && !request.isHtmlGenerated()) {
      blocked = PdfUaIdentifierStep.textBlocker(candidate);
    }
    if (blocked == null) {
      blocked = PdfUaIdentifierStep.structureBlocker(candidate);
    }
    if (blocked != null) {
      return IdentifierRun.skipped(blocked);
    }
    Path identified = workspace.scratchDirectory().resolve(IDENTIFIED);
    Path report = workspace.scratchDirectory().resolve(IDENTIFIED_REPORT);
    try {
      PdfUaIdentifierStep.writeIdentifiedCopy(candidate, identified);
    } catch (IOException e) {
      return IdentifierRun.skipped("not written: could not add the identifier to the copy (" + e.getMessage() + ")");
    }
    String invariantFailure = invariantFailure(pre, preflight.inspect(identified, request), actions, originalBytes, identified);
    if (invariantFailure == null) {
      invariantFailure = CopyIntegrity.regression(integrityBefore, CopyIntegrity.inspect(identified));
    }
    if (invariantFailure != null) {
      deleteQuietly(identified);
      return IdentifierRun.skipped("not written: the identified copy failed an integrity check: " + invariantFailure);
    }
    if (!Instant.now().isBefore(deadline)) {
      deleteQuietly(identified);
      return IdentifierRun.skipped("not written: the job time budget was exhausted before the identified copy could be validated");
    }
    ValidationOutcome identifiedValidation = validator.validate(identified, report, remaining(deadline));
    if (identifiedValidation.isError()) {
      deleteQuietly(identified);
      deleteQuietly(report);
      return IdentifierRun.skipped("not written: validating the identified copy failed (" + identifiedValidation.errorMessage() + "); the copy without the identifier is published");
    }
    if (!identifiedValidation.passed() || !identifiedValidation.failedRuleCounts().isEmpty()) {
      deleteQuietly(identified);
      deleteQuietly(report);
      List<String> failing = identifiedValidation.failedRuleCounts().keySet().stream().sorted().toList();
      return IdentifierRun.skipped("not written: the identified copy still fails " + String.join(", ", failing) + "; the copy without the identifier is published");
    }
    return new IdentifierRun(
        new Action(
            RuleId.PDF_UA_IDENTIFIER_V1,
            ActionOutcome.APPLIED,
            "added pdfuaid:part=1 to the XMP packet as requested after human review: the automated PDF/UA-1 machine checks report no"
                + " failures on this copy; they cover only part of PDF/UA-1",
            "no PDF/UA identifier",
            "pdfuaid:part=1"),
        identifiedValidation);
  }

  /**
   * Whether a published copy of this request is surveyed for codes without a Unicode source (finding
   * {@code FONT_GLYPHS_WITHOUT_UNICODE}): when the font rule belongs to the request's mode, also in a
   * repeat that left it out.
   */
  private boolean surveysFonts(WorkerRequest request) {
    return rules.stream().anyMatch(r -> r.id() == RuleId.PDF_FONT_TOUNICODE_V1 && r.participates(request));
  }

  /** The XMP rule wrote a packet where the catalog had none (not an addition to an existing packet). */
  private static boolean packetWritten(List<Action> actions) {
    return actions.stream()
        .anyMatch(a -> a.ruleId() == RuleId.PDF_XMP_METADATA_FROM_INFO_V1 && a.outcome() == ActionOutcome.APPLIED && a.before() == null);
  }

  private static boolean applied(List<Action> actions, RuleId rule) {
    return actions.stream().anyMatch(a -> a.ruleId() == rule && a.outcome() == ActionOutcome.APPLIED);
  }

  /** A fix run whose title came from the source file name: a person should check it. */
  private static boolean titleTakenFromFileName(List<Action> actions) {
    return actions.stream()
        .anyMatch(a -> a.ruleId() == RuleId.PDF_TITLE_V1 && a.outcome() == ActionOutcome.APPLIED && a.reason().startsWith(TitleRule.FROM_FILE_NAME));
  }

  /** Only the rules that participate in the request's mode are inspected, applied and listed. */
  private List<Action> planAndApply(PDDocument document, WorkerRequest request, Set<RuleId> excluded) {
    List<RemediationRule> participating = rules.stream().filter(rule -> rule.participates(request, excluded)).toList();
    List<RuleDecision> decisions = new ArrayList<>();
    for (RemediationRule rule : participating) {
      decisions.add(rule.inspect(document, request, excluded));
    }
    List<Action> actions = new ArrayList<>();
    for (int i = 0; i < participating.size(); i++) {
      actions.add(participating.get(i).apply(document, decisions.get(i)));
    }
    return actions;
  }

  /**
   * Page count and normalized text must be identical and the copy must be a plain, open PDF. Where {@code PDF_FONT_TOUNICODE_V1}
   * applied, a changed text digest is judged by the glyph audit (only glyphs without Unicode may gain it). Where
   * {@code PDF_STRUCTURE_INFERRED_V1} kept descriptions as marked-content properties, the copy's extracted text must equal
   * the original's with those descriptions kept and tags discarded ({@link StructureInferenceRule#describedBaseline}).
   */
  static String invariantFailure(PreflightOutcome before, PreflightOutcome after, List<Action> actions, byte[] originalBytes, Path copy) {
    if (!after.accepted()) {
      return "remediated copy failed preflight: " + after.reason();
    }
    if (!before.pageCount().equals(after.pageCount())) {
      return "page count changed from " + before.pageCount() + " to " + after.pageCount();
    }
    if (!before.textDigest().equals(after.textDigest())) {
      if (applied(actions, RuleId.PDF_FONT_TOUNICODE_V1)) {
        String audit = glyphAuditFailure(originalBytes, copy);
        if (audit != null) {
          return audit;
        }
        if (applied(actions, RuleId.PDF_STRUCTURE_INFERRED_V1) && !replacementTextKept(originalBytes, copy)) {
          return "extracted text changed where kept replacement text applies";
        }
      } else if (!applied(actions, RuleId.PDF_STRUCTURE_INFERRED_V1) || !followsKeptDescriptions(originalBytes, copy)) {
        return "extracted text digest changed";
      }
    }
    if (after.signed() || after.encrypted()) {
      return "remediated copy unexpectedly signed or encrypted";
    }
    return null;
  }

  /**
   * Whether descriptions of other elements were kept ({@link StructureInferenceRule#describedBaseline}
   * on a fresh load of the original reports at least one) and the copy's extracted text is exactly that
   * baseline's while its glyph text is the original's; any failure counts as no.
   */
  private static boolean followsKeptDescriptions(byte[] originalBytes, Path copy) {
    try (PDDocument original = Loader.loadPDF(originalBytes); PDDocument baseline = Loader.loadPDF(originalBytes);
        PDDocument saved = Loader.loadPDF(copy.toFile())) {
      if (StructureInferenceRule.describedBaseline(baseline) == 0) {
        return false;
      }
      String savedText = PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(saved));
      String savedGlyphs = PdfPreflight.normalizedTextDigest(PdfPreflight.extractGlyphText(saved));
      return savedText.equals(PdfPreflight.normalizedTextDigest(PdfPreflight.extractText(baseline)))
          && savedGlyphs.equals(PdfPreflight.normalizedTextDigest(PdfPreflight.extractGlyphText(original)));
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * In a run where the ToUnicode rule also applied: {@code true} when no /Alt, /ActualText or /E was
   * kept, or when {@link GlyphAudit#sameReplacementText} holds for the original, the described baseline
   * and the copy; any failure counts as {@code false}.
   */
  private static boolean replacementTextKept(byte[] originalBytes, Path copy) {
    try (PDDocument original = Loader.loadPDF(originalBytes); PDDocument baseline = Loader.loadPDF(originalBytes);
        PDDocument saved = Loader.loadPDF(copy.toFile())) {
      return StructureInferenceRule.describedBaseline(baseline) == 0 || GlyphAudit.sameReplacementText(original, baseline, saved);
    } catch (Exception e) {
      return false;
    }
  }

  /** {@link GlyphAudit#fillOnlyFailure} of the original and the saved copy; a load or audit failure is a failure too. */
  private static String glyphAuditFailure(byte[] originalBytes, Path copy) {
    try (PDDocument original = Loader.loadPDF(originalBytes); PDDocument saved = Loader.loadPDF(copy.toFile())) {
      return GlyphAudit.fillOnlyFailure(original, saved);
    } catch (IOException | RuntimeException e) {
      return GlyphAudit.FAILURE_PREFIX + "the glyph audit could not run (" + e.getClass().getSimpleName() + ")";
    }
  }

  /**
   * PDFBox derives a fresh trailer {@code /ID} from the wall clock when the input has none; seeding
   * it from the original's digest keeps two runs over the same input byte-identical. An existing
   * {@code /ID} is left alone, and Info dates are never touched.
   */
  private static void pinDocumentId(PDDocument document, String originalSha256) {
    if (document.getDocument().getTrailer().getItem(COSName.ID) != null || originalSha256 == null) {
      return;
    }
    long seed = ByteBuffer.wrap(HexFormat.of().parseHex(originalSha256.substring(0, 16))).getLong();
    document.setDocumentId(seed);
  }

  /**
   * The structure gate for a run in which basic structure was added: (a) no rule that failed in the
   * baseline may fail more often, and the four checks that structure tagging exists to satisfy must
   * be gone; (b) a rule that did not fail in the baseline may fail now only when it is in the
   * allow-list of structure-dependent checks. Anything else is an integrity regression.
   */
  static String structureRegression(Map<String, Integer> baseline, Map<String, Integer> fin) {
    return structureRegression(baseline, fin, false);
  }

  /**
   * {@code glyphInventoryUnchanged}: the independent {@link GlyphInventory} digest equals the original's. Only then may an
   * already failing font/glyph check ({@link #FONT_CHECK_CLAUSE}) fail more often (veraPDF counts glyphs per marked-content
   * sequence, so the count grows with MCIDs). Every other increase, and every new font check, is a regression.
   */
  static String structureRegression(Map<String, Integer> baseline, Map<String, Integer> fin, boolean glyphInventoryUnchanged) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Integer> e : fin.entrySet()) {
      String rule = clauseAndTest(e.getKey());
      if (STRUCTURE_GATE_MUST_REACH_ZERO.contains(rule)) {
        problems.add("rule " + e.getKey() + " still fails (" + e.getValue() + ") after structure tagging");
        continue;
      }
      Integer before = baseline.get(e.getKey());
      if (before == null) {
        if (!allowedNewFailure(rule)) {
          problems.add("newly failing rule " + e.getKey() + " (" + e.getValue() + ") is not a structure-dependent check");
        }
      } else if (e.getValue() > before && !(glyphInventoryUnchanged && isFontCheck(rule))) {
        problems.add("rule " + e.getKey() + " failures increased " + before + " -> " + e.getValue());
      }
    }
    return problems.isEmpty() ? null : "structure gate: " + String.join("; ", problems);
  }

  /** ISO 14289-1 clause 7.21 (fonts): the checks veraPDF counts per glyph per marked-content sequence. */
  static final String FONT_CHECK_CLAUSE = "7.21";

  private static boolean isFontCheck(String clauseAndTest) {
    int dash = clauseAndTest.indexOf('-');
    String clause = dash < 0 ? clauseAndTest : clauseAndTest.substring(0, dash);
    return clause.equals(FONT_CHECK_CLAUSE) || clause.startsWith(FONT_CHECK_CLAUSE + ".");
  }

  /** {@code "7.21.7-1 49 -> 176"} for every font check whose count grew although no glyph changed. */
  static List<String> fontChecksRecounted(Map<String, Integer> baseline, Map<String, Integer> fin) {
    List<String> recounted = new ArrayList<>();
    for (Map.Entry<String, Integer> e : fin.entrySet()) {
      Integer before = baseline.get(e.getKey());
      if (before != null && e.getValue() > before && isFontCheck(clauseAndTest(e.getKey()))) {
        recounted.add(clauseAndTest(e.getKey()) + " " + before + " -> " + e.getValue());
      }
    }
    recounted.sort(String::compareTo);
    return recounted;
  }

  /** Test seam: how often a glyph inventory was computed (runs without structure steps must never compute one). */
  static final AtomicInteger glyphInventoriesComputedForTests = new AtomicInteger();

  /** The candidate as saved on disk, i.e. what the validator saw; {@code null} (strict gate) when it cannot be read. */
  private static String glyphInventory(Path candidate) {
    try (PDDocument document = Loader.loadPDF(candidate.toFile())) {
      return glyphInventory(document);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** {@code null} on any failure: the gate then treats the inventory as unavailable and stays strict. */
  private static String glyphInventory(PDDocument document) {
    glyphInventoriesComputedForTests.incrementAndGet();
    try {
      return GlyphInventory.digest(document);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** {@code "ISO 14289-1:2014 7.18.5-1"} becomes {@code "7.18.5-1"}. */
  private static String clauseAndTest(String ruleId) {
    int space = ruleId.lastIndexOf(' ');
    return space < 0 ? ruleId : ruleId.substring(space + 1);
  }

  private static boolean allowedNewFailure(String clauseAndTest) {
    if (STRUCTURE_GATE_ALLOWED_NEW_RULES.contains(clauseAndTest)) {
      return true;
    }
    int dash = clauseAndTest.indexOf('-');
    String clause = dash < 0 ? clauseAndTest : clauseAndTest.substring(0, dash);
    for (String prefix : STRUCTURE_GATE_ALLOWED_NEW_CLAUSES) {
      if (clause.equals(prefix) || clause.startsWith(prefix + ".")) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code 5-1}: the PDF/UA identification schema is missing from the XMP packet. veraPDF evaluates
   * it only once a metadata stream exists, and the XMP rule never writes the identifier (only the
   * {@link PdfUaIdentifierStep} does, after the gate), so a generated run that wrote the first packet
   * necessarily surfaces it; nothing else is exempt.
   */
  static final String PDFUA_IDENTIFIER_RULE = "5-1";

  /**
   * The gate for a run over a generated PDF: no rule may fail more often than in the
   * baseline, a rule the baseline did not fail counting as zero. There is no structure allow-list;
   * the one exception is {@link #PDFUA_IDENTIFIER_RULE} when this run wrote the XMP packet.
   */
  static String generatedRegression(Map<String, Integer> baseline, Map<String, Integer> fin, boolean xmpWritten) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Integer> e : fin.entrySet()) {
      int before = baseline.getOrDefault(e.getKey(), 0);
      if (e.getValue() <= before) {
        continue;
      }
      if (before == 0 && xmpWritten && PDFUA_IDENTIFIER_RULE.equals(clauseAndTest(e.getKey()))) {
        continue;
      }
      problems.add(before == 0
          ? "newly failing rule " + e.getKey() + " (" + e.getValue() + ")"
          : "rule " + e.getKey() + " failures increased " + before + " -> " + e.getValue());
    }
    return problems.isEmpty() ? null : "generated gate: " + String.join("; ", problems);
  }

  /** No rule may newly fail and no previously failing rule may fail more often. */
  static String regression(Map<String, Integer> baseline, Map<String, Integer> fin) {
    return regression(baseline, fin, false);
  }

  /**
   * The strict gate. When {@code xmpWritten} (this run wrote the first XMP packet), a newly failing
   * {@code 5-1} (no PDF/UA identifier) or {@code 7.1-9} (no dc:title because Info has none, and a title
   * is never invented) is allowed: both exist only once a packet exists, as in the structure gate.
   */
  static String regression(Map<String, Integer> baseline, Map<String, Integer> fin, boolean xmpWritten) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Integer> e : fin.entrySet()) {
      Integer before = baseline.get(e.getKey());
      if (before == null && xmpWritten && STRUCTURE_GATE_ALLOWED_NEW_RULES.contains(clauseAndTest(e.getKey()))) {
        continue;
      }
      if (before == null) {
        problems.add("newly failing rule " + e.getKey() + " (" + e.getValue() + ")");
      } else if (e.getValue() > before) {
        problems.add("rule " + e.getKey() + " failures increased " + before + " -> " + e.getValue());
      }
    }
    return problems.isEmpty() ? null : "validation regression: " + String.join("; ", problems);
  }

  private static void publish(Path candidate, Path target) throws IOException {
    try {
      Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(candidate, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Duration remaining(Instant deadline) {
    Duration left = Duration.between(Instant.now(), deadline);
    return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best effort; scratch is per-job and discarded by the orchestrator anyway
    }
  }
}
