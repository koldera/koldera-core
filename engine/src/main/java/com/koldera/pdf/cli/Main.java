package com.koldera.pdf.cli;

import com.koldera.pdf.contracts.RequestValidationException;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerRequestParser;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.fonts.FontDictionaryRule;
import com.koldera.pdf.fonts.FontEmbeddingRule;
import com.koldera.pdf.fonts.FontUnicodeRule;
import com.koldera.pdf.fonts.InvisibleTextFontRule;
import com.koldera.pdf.generated.GeneratedStructureRule;
import com.koldera.pdf.inference.InferenceSettings;
import com.koldera.pdf.inference.StructureInferenceRule;
import com.koldera.pdf.pipeline.JobWorkspace;
import com.koldera.pdf.pipeline.PdfProcessor;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.repair.FiguresWithoutAltRule;
import com.koldera.pdf.repair.TaggedStructureRepairRule;
import com.koldera.pdf.repair.UntaggedContentRule;
import com.koldera.pdf.report.ReportWriter;
import com.koldera.pdf.rules.BasicStructureRule;
import com.koldera.pdf.rules.DisplayDocumentTitleRule;
import com.koldera.pdf.rules.ExplicitLanguageRule;
import com.koldera.pdf.rules.LanguageCorrectionRule;
import com.koldera.pdf.rules.LinkTagsRule;
import com.koldera.pdf.rules.RemediationRule;
import com.koldera.pdf.rules.RoleMapRule;
import com.koldera.pdf.rules.TabOrderRule;
import com.koldera.pdf.rules.TitleRule;
import com.koldera.pdf.rules.XmpMetadataRule;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * CLI envelope: {@code process --request PATH --result PATH [--work-root DIR]}.
 * Exit {@code 0}: a contract-valid result was written (also for user errors such as an invalid request);
 * {@code 1}: the result could not be persisted; {@code 2}: usage error. Logs carry no document content or host paths.
 */
public final class Main {

  /** Seam between the CLI envelope and the processing pipeline. */
  @FunctionalInterface
  public interface JobRunner {
    WorkerResult run(WorkerRequest request, JobWorkspace workspace) throws Exception;
  }

  static final int EXIT_OK = 0;
  static final int EXIT_RESULT_NOT_PERSISTED = 1;
  static final int EXIT_USAGE = 2;

  private static final UUID NIL_JOB_ID = new UUID(0L, 0L);
  private static final String DEFAULT_WORK_ROOT = "/work";

  private final JobRunner runner;
  private final PrintStream log;
  private final ReportWriter reportWriter = new ReportWriter();

  public Main(JobRunner runner, PrintStream log) {
    this.runner = runner;
    this.log = log;
  }

  JobRunner runner() {
    return runner;
  }

  /**
   * Production wiring: real preflight, veraPDF from {@code $VERAPDF_CMD}/PATH, and
   * {@link #productionRules()} (each rule participates only in the modes it belongs to).
   */
  public static void main(String[] args) {
    quietLibraryLogging();
    System.exit(dispatch(args, System.err));
  }

  /**
   * Routes the verb: {@code inspect} (read-only probe), {@code structure} (read-only snapshot),
   * {@code reorder} (approved sibling reorder on a copy), {@code infer-benchmark} (read-only benchmark against
   * producer tags); everything else is the {@code process} envelope with the production pipeline.
   */
  public static int dispatch(String[] args, PrintStream log) {
    if (args.length > 0 && "inspect".equals(args[0])) {
      return com.koldera.pdf.inspect.InspectCommand.run(args, log);
    }
    if (args.length > 0 && "structure".equals(args[0])) {
      return com.koldera.pdf.structure.StructureCommand.run(args, log);
    }
    if (args.length > 0 && "reorder".equals(args[0])) {
      return com.koldera.pdf.order.ReorderCommand.run(args, log);
    }
    if (args.length > 0 && "infer-benchmark".equals(args[0])) {
      return com.koldera.pdf.inference.InferenceBenchmarkCommand.run(args, log);
    }
    JobRunner runner = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), productionRules());
    return new Main(runner, log).run(args);
  }

  /**
   * The production rule list in apply order; each rule decides itself whether it participates in a mode.
   * Order matters: title before the display-title and XMP rules (they read the Info title); structure rules
   * before the link, tab-order, untagged-content, repair and figure rules (those need a tagged document); font
   * rules after the structure and upload repairs and before the XMP rule, which copies the final /Lang.
   */
  public static List<RemediationRule> productionRules() {
    BasicStructureRule structure = new BasicStructureRule();
    return List.of(
        new ExplicitLanguageRule(), new LanguageCorrectionRule(), new TitleRule(), new DisplayDocumentTitleRule(), new StructureInferenceRule(InferenceSettings.DEFAULTS), structure,
        new GeneratedStructureRule(), new UntaggedContentRule(), new TaggedStructureRepairRule(), new FiguresWithoutAltRule(), new LinkTagsRule(), new TabOrderRule(), new RoleMapRule(),
        new FontDictionaryRule(), new InvisibleTextFontRule(), new FontEmbeddingRule(), new FontUnicodeRule(), new XmpMetadataRule(structure));
  }

  /** PDFBox logs through JUL; only severe problems are worth stderr and none may echo content. */
  private static void quietLibraryLogging() {
    LogManager.getLogManager().reset();
    Logger root = Logger.getLogger("");
    root.setLevel(Level.SEVERE);
  }

  public int run(String[] args) {
    Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      log.println("usage: process --request PATH --result PATH [--work-root DIR]");
      log.println("error: " + e.getMessage());
      return EXIT_USAGE;
    }

    JobWorkspace workspace;
    try {
      workspace = JobWorkspace.open(options.workRoot);
    } catch (IOException e) {
      log.println("usage: --work-root must be an existing directory containing in/document.pdf");
      return EXIT_USAGE;
    }

    WorkerResult result = execute(options.request, workspace);
    try {
      reportWriter.write(result, options.result);
    } catch (IOException | RuntimeException e) {
      logEvent("result_not_persisted", "reason", e.getClass().getSimpleName());
      return EXIT_RESULT_NOT_PERSISTED;
    }
    logEvent("result_written", "status", result.status().name(), "jobId", result.jobId().toString());
    return EXIT_OK;
  }

  private WorkerResult execute(Path requestPath, JobWorkspace workspace) {
    UUID jobId = NIL_JOB_ID;
    WorkerRequest request;
    try {
      String json = Files.readString(requestPath, StandardCharsets.UTF_8);
      jobId = peekJobId(json);
      request = WorkerRequestParser.parse(json);
    } catch (NoSuchFileException e) {
      logEvent("request_invalid", "reason", "request file missing");
      return invalidInput(jobId, "request file missing");
    } catch (IOException e) {
      logEvent("request_invalid", "reason", "request file unreadable");
      return invalidInput(jobId, "request file unreadable");
    } catch (RequestValidationException e) {
      logEvent("request_invalid", "reason", e.getMessage());
      return invalidInput(jobId, e.getMessage());
    }

    try {
      WorkerResult result = runner.run(request, workspace);
      if (result == null) {
        throw new IllegalStateException("runner returned no result");
      }
      return result;
    } catch (Exception e) {
      // Exception text may contain host paths or parser fragments; only the type is logged/reported.
      logEvent("worker_error", "exception", e.getClass().getName());
      return failed(
          request.jobId(),
          WorkerResult.ErrorCode.WORKER_ERROR,
          "unexpected worker failure (" + e.getClass().getSimpleName() + ")");
    } catch (StackOverflowError e) {
      // Backstop: a hostile document that some walker follows without a bound must still yield a result, not a dead worker.
      logEvent("worker_error", "exception", e.getClass().getName());
      return failed(
          request.jobId(),
          WorkerResult.ErrorCode.WORKER_ERROR,
          "unexpected worker failure (StackOverflowError: the document nests too deeply to be processed)");
    }
  }

  private static WorkerResult invalidInput(UUID jobId, String message) {
    return failed(jobId, WorkerResult.ErrorCode.INVALID_INPUT, message);
  }

  private static WorkerResult failed(UUID jobId, WorkerResult.ErrorCode code, String message) {
    return new WorkerResult(
        WorkerResult.SPEC_VERSION,
        jobId,
        WorkerResult.Status.FAILED,
        null,
        EngineVersion.describe(),
        WorkerResult.Validation.notRun(),
        null,
        WorkerResult.RemediationStatus.NOT_REQUESTED,
        List.of(),
        List.of(),
        List.of(),
        true,
        new WorkerResult.WorkerError(code, message));
  }

  /** Best-effort jobId extraction so an invalid request still yields a correlatable result. */
  private static UUID peekJobId(String json) {
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
      if (node != null && node.hasNonNull("jobId") && node.get("jobId").isTextual()) {
        return UUID.fromString(node.get("jobId").asText());
      }
    } catch (IOException | IllegalArgumentException ignored) {
      // fall through to the nil UUID
    }
    return NIL_JOB_ID;
  }

  private void logEvent(String event, String... keyValues) {
    StringBuilder sb = new StringBuilder("{\"event\":\"").append(event).append('"');
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      sb.append(",\"").append(keyValues[i]).append("\":\"")
          .append(keyValues[i + 1].replace("\\", "\\\\").replace("\"", "\\\""))
          .append('"');
    }
    log.println(sb.append('}'));
  }

  private record Options(Path request, Path result, Path workRoot) {
    static Options parse(String[] args) {
      if (args.length == 0 || !"process".equals(args[0])) {
        throw new IllegalArgumentException("the only supported command is 'process'");
      }
      Map<String, String> values = new HashMap<>();
      for (int i = 1; i < args.length; i += 2) {
        String key = args[i];
        if (!key.startsWith("--") || i + 1 >= args.length) {
          throw new IllegalArgumentException("malformed option " + key);
        }
        if (values.put(key.substring(2), args[i + 1]) != null) {
          throw new IllegalArgumentException("duplicate option " + key);
        }
      }
      String request = values.remove("request");
      String result = values.remove("result");
      String workRoot = values.getOrDefault("work-root", DEFAULT_WORK_ROOT);
      values.remove("work-root");
      if (request == null || result == null) {
        throw new IllegalArgumentException("--request and --result are required");
      }
      if (!values.isEmpty()) {
        throw new IllegalArgumentException("unknown option --" + values.keySet().iterator().next());
      }
      return new Options(Path.of(request), Path.of(result), Path.of(workRoot));
    }
  }
}
