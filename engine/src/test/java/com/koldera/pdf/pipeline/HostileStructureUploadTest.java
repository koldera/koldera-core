package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.PdfValidator;
import com.koldera.pdf.validation.ValidationOutcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hostile structure trees through the whole CLI envelope with the production rules: a {@code /K} array
 * that contains itself must end in a written, contract-valid result, never a worker that dies without one.
 */
class HostileStructureUploadTest {

  private static final String REQUEST =
      """
      {
        "specVersion": "1.0",
        "jobId": "123e4567-e89b-42d3-a456-426614174000",
        "inputPath": "/work/in/document.pdf",
        "outputDirectory": "/work/out",
        "mode": "%s",
        "profile": "ua1",
        "origin": "upload",
        "limits": { "maxBytes": 10485760, "maxPages": 20, "timeoutSeconds": 60 }
      }
      """;

  @TempDir Path work;

  @Test
  void aSelfReferencingKArrayIsRebuiltAndAResultIsWritten() throws IOException {
    WorkerResult result = run(SyntheticPdfFactory.taggedWithSelfReferencingKArray(false), "fix");

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action strategy = action(result, RuleId.PDF_STRUCTURE_INFERRED_V1);
    assertEquals(ActionOutcome.APPLIED, strategy.outcome(), strategy.reason());
    assertTrue(strategy.reason().startsWith("strategy tagged_unusable: ") && strategy.reason().contains("the structure tree cannot be walked"), strategy.reason());
  }

  @Test
  void aSelfReferencingKArrayWhoseTagsAreKeptIsWalkedByEveryLaterRule() throws IOException {
    WorkerResult result = run(SyntheticPdfFactory.taggedWithSelfReferencingKArray(true), "fix");

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    WorkerResult.Action strategy = action(result, RuleId.PDF_STRUCTURE_INFERRED_V1);
    assertEquals(ActionOutcome.SKIPPED, strategy.outcome(), strategy.reason());
    assertTrue(strategy.reason().contains("the tags are kept because the document has form fields"), strategy.reason());
  }

  @Test
  void theOtherModesAlsoWriteAResult() throws IOException {
    for (String mode : new String[] {"analyze", "safe_fix", "structure_fix"}) {
      WorkerResult result = run(SyntheticPdfFactory.taggedWithSelfReferencingKArray(true), mode);
      assertEquals(Status.COMPLETED, result.status(), mode + ": " + result.error());
    }
  }

  private WorkerResult run(byte[] pdf, String mode) throws IOException {
    Files.createDirectories(work.resolve("in"));
    Files.createDirectories(work.resolve("out"));
    Files.write(work.resolve("in").resolve("document.pdf"), pdf);
    Path request = work.resolve("request.json");
    Path resultPath = work.resolve("result.json");
    Files.deleteIfExists(resultPath);
    Files.writeString(request, REQUEST.formatted(mode));
    Main main = new Main(new PdfProcessor(new PdfPreflight(), new InputFailsCopiesPass(), Main.productionRules()), new PrintStream(new ByteArrayOutputStream()));

    int code = main.run(new String[] {"process", "--request", request.toString(), "--result", resultPath.toString(), "--work-root", work.toString()});

    assertEquals(0, code);
    assertTrue(Files.exists(resultPath), "result.json written");
    String json = Files.readString(resultPath, StandardCharsets.UTF_8);
    var errors = ContractSchemas.validateResult(json);
    assertTrue(errors.isEmpty(), errors::toString);
    return WorkerResultCodec.fromJson(json);
  }

  private static WorkerResult.Action action(WorkerResult result, RuleId id) {
    return result.actions().stream().filter(a -> a.ruleId() == id).findFirst().orElseThrow(() -> new AssertionError(id + " in " + result.actions()));
  }

  /** The input fails one check and every copy passes: nothing new ever fails, and no identifier is written. */
  private static final class InputFailsCopiesPass implements PdfValidator {
    private int calls;

    @Override
    public ValidationOutcome validate(Path pdf, Path reportPath, Duration timeout) {
      try {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, "{\"fake\":true}", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
      return calls++ == 0
          ? new ValidationOutcome(ValidationStatus.FAILED, Map.of("ISO 14289-1:2014 7.1-3", 1), Map.of(), reportPath, null, null, "fake")
          : new ValidationOutcome(ValidationStatus.PASSED, Map.of(), Map.of(), reportPath, null, null, "fake");
    }
  }
}
