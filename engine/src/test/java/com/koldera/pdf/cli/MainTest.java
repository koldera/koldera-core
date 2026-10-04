package com.koldera.pdf.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.pipeline.JobWorkspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

  private static final String VALID_REQUEST =
      """
      {
        "specVersion": "1.0",
        "jobId": "123e4567-e89b-42d3-a456-426614174000",
        "inputPath": "/work/in/document.pdf",
        "outputDirectory": "/work/out",
        "mode": "analyze",
        "profile": "ua1",
        "limits": { "maxBytes": 10485760, "maxPages": 20, "timeoutSeconds": 60 }
      }
      """;

  @TempDir Path work;
  private Path requestPath;
  private Path resultPath;
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

  @BeforeEach
  void layout() throws IOException {
    Files.createDirectories(work.resolve("in"));
    Files.createDirectories(work.resolve("out"));
    Files.write(work.resolve("in").resolve("document.pdf"), "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
    requestPath = work.resolve("request.json");
    resultPath = work.resolve("result.json");
  }

  @Test
  void usageErrorExitsWithTwoAndWritesNoResult() {
    Main main = new Main((request, workspace) -> null, new PrintStream(stderr));

    int code = main.run(new String[] {"process", "--request", requestPath.toString()});

    assertEquals(2, code);
    assertFalse(Files.exists(resultPath));
    assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("usage"), stderr.toString());
  }

  @Test
  void unknownCommandIsUsageError() {
    Main main = new Main((request, workspace) -> null, new PrintStream(stderr));

    int code = main.run(args("serve"));

    assertEquals(2, code);
  }

  @Test
  void invalidRequestProducesFailedResultWithInvalidInputAndExitZero() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST.replace("\"ua1\"", "\"ua2\""));
    AtomicReference<WorkerRequest> seen = new AtomicReference<>();
    Main main = new Main((request, workspace) -> { seen.set(request); return null; }, new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(0, code);
    assertNull(seen.get(), "pipeline must not run for an invalid request");
    WorkerResult result = readResult();
    assertEquals(WorkerResult.Status.FAILED, result.status());
    assertEquals(WorkerResult.ErrorCode.INVALID_INPUT, result.error().code());
    assertEquals(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), result.jobId());
    assertEquals(WorkerResult.ValidationStatus.NOT_RUN, result.baseline().status());
    assertTrue(result.manualReviewRequired());
  }

  @Test
  void unparseableRequestUsesNilJobIdSoResultStillValidatesAgainstSchema() throws IOException {
    Files.writeString(requestPath, "{ \"jobId\": 42, \"nope\": true");
    Main main = new Main((request, workspace) -> null, new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(0, code);
    WorkerResult result = readResult();
    assertEquals(WorkerResult.Status.FAILED, result.status());
    assertEquals(new UUID(0L, 0L), result.jobId());
  }

  @Test
  void missingRequestFileIsReportedAsInvalidInput() {
    Main main = new Main((request, workspace) -> null, new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(0, code);
    assertEquals(WorkerResult.ErrorCode.INVALID_INPUT, readResult().error().code());
  }

  @Test
  void validRequestIsHandedToRunnerWithWorkspaceRootedAtWorkRoot() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST);
    AtomicReference<JobWorkspace> seen = new AtomicReference<>();
    WorkerResult produced = failedResult("worker produced this");
    Main main =
        new Main(
            (request, workspace) -> {
              seen.set(workspace);
              return produced;
            },
            new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(0, code);
    assertEquals(work.toRealPath().resolve("in").resolve("document.pdf"), seen.get().inputFile());
    assertEquals(work.toRealPath().resolve("out"), seen.get().outputDirectory());
    assertEquals(produced, readResult());
  }

  @Test
  void runnerExceptionBecomesWorkerErrorResultNotCrash() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST);
    Main main =
        new Main(
            (request, workspace) -> {
              throw new IllegalStateException("boom /work/in/secret-name.pdf");
            },
            new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(0, code);
    WorkerResult result = readResult();
    assertEquals(WorkerResult.Status.FAILED, result.status());
    assertEquals(WorkerResult.ErrorCode.WORKER_ERROR, result.error().code());
    assertFalse(result.error().message().contains("secret-name"), "exception text must not leak into result");
  }

  @Test
  void runnerStackOverflowBecomesWorkerErrorResultNotCrash() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST);
    Main main =
        new Main(
            (request, workspace) -> {
              throw new StackOverflowError();
            },
            new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(0, code);
    WorkerResult result = readResult();
    assertEquals(WorkerResult.Status.FAILED, result.status());
    assertEquals(WorkerResult.ErrorCode.WORKER_ERROR, result.error().code());
    assertEquals("unexpected worker failure (StackOverflowError: the document nests too deeply to be processed)", result.error().message());
    assertTrue(result.manualReviewRequired());
  }

  @Test
  void unwritableResultPathExitsNonZero() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST);
    Files.createDirectories(resultPath); // a directory cannot be replaced by the result file
    Main main = new Main((request, workspace) -> failedResult("x"), new PrintStream(stderr));

    int code = main.run(args("process"));

    assertEquals(1, code);
  }

  @Test
  void missingWorkRootIsUsageError() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST);
    Main main = new Main((request, workspace) -> failedResult("x"), new PrintStream(stderr));

    int code =
        main.run(
            new String[] {
              "process",
              "--request", requestPath.toString(),
              "--result", resultPath.toString(),
              "--work-root", work.resolve("does-not-exist").toString()
            });

    assertEquals(2, code);
  }

  @Test
  void writtenResultAlwaysValidatesAgainstResultSchema() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST);
    Main main = new Main((request, workspace) -> failedResult("x"), new PrintStream(stderr));
    main.run(args("process"));

    String json = Files.readString(resultPath);

    assertTrue(ContractSchemas.validateResult(json).isEmpty());
  }

  @Test
  void stderrDoesNotContainDocumentPathOrJobRoot() throws IOException {
    Files.writeString(requestPath, VALID_REQUEST.replace("\"ua1\"", "\"ua2\""));
    Main main = new Main((request, workspace) -> null, new PrintStream(stderr));

    main.run(args("process"));

    String log = stderr.toString(StandardCharsets.UTF_8);
    assertFalse(log.contains(work.toString()), log);
  }

  @Test
  void runnerInterfaceIsTheOnlyProductionSeam() {
    Main.JobRunner runner = (request, workspace) -> null;
    assertSame(runner, new Main(runner, new PrintStream(stderr)).runner());
  }

  private String[] args(String command) {
    return new String[] {
      command,
      "--request", requestPath.toString(),
      "--result", resultPath.toString(),
      "--work-root", work.toString()
    };
  }

  private WorkerResult readResult() {
    try {
      return WorkerResultCodec.fromJson(Files.readString(resultPath, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new AssertionError("result.json missing", e);
    }
  }

  private static WorkerResult failedResult(String message) {
    return new WorkerResult(
        "1.0",
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        WorkerResult.Status.FAILED,
        null,
        "test",
        WorkerResult.Validation.notRun(),
        null,
        WorkerResult.RemediationStatus.NOT_REQUESTED,
        List.of(),
        List.of(),
        List.of(),
        true,
        new WorkerResult.WorkerError(WorkerResult.ErrorCode.WORKER_ERROR, message));
  }
}
