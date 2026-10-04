package com.koldera.pdf.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import com.koldera.pdf.pipeline.JobWorkspace;
import com.koldera.pdf.pipeline.PdfProcessor;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.PdfValidator;
import com.koldera.pdf.validation.ValidationOutcome;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code limits} is optional; an absent limit is not enforced, a present one still is. */
class OptionalLimitsTest {

  private static final String HEAD =
      """
      {"specVersion":"1.0","jobId":"123e4567-e89b-42d3-a456-426614174000","inputPath":"/work/in/document.pdf",\
      "outputDirectory":"/work/out","mode":"analyze","profile":"ua1"\
      """;

  @TempDir Path root;
  private JobWorkspace workspace;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("in"));
    workspace = JobWorkspace.open(root);
  }

  @Test
  void parserFillsEveryAbsentLimitWithUnbounded() {
    WorkerRequest none = WorkerRequestParser.parse(HEAD + "}");
    assertEquals(Long.MAX_VALUE, none.limits().maxBytes());
    assertEquals(Integer.MAX_VALUE, none.limits().maxPages());
    assertEquals(Integer.MAX_VALUE, none.limits().timeoutSeconds());

    WorkerRequest partial = WorkerRequestParser.parse(HEAD + ",\"limits\":{\"maxPages\":1}}");
    assertEquals(Long.MAX_VALUE, partial.limits().maxBytes());
    assertEquals(1, partial.limits().maxPages());
    assertEquals(Integer.MAX_VALUE, partial.limits().timeoutSeconds());
  }

  @Test
  void presentLimitsKeepTheirMinimumsAndAreNotCappedAnyMore() {
    assertThrows(RequestValidationException.class, () -> WorkerRequestParser.parse(HEAD + ",\"limits\":{\"maxPages\":0}}"));
    WorkerRequest large = WorkerRequestParser.parse(HEAD + ",\"limits\":{\"maxBytes\":999999999999,\"maxPages\":5000,\"timeoutSeconds\":3600}}");
    assertEquals(999_999_999_999L, large.limits().maxBytes());
    assertEquals(5000, large.limits().maxPages());
  }

  @Test
  void aRequestWithoutLimitsCompletesOnAOnePageFixture() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.simpleUntagged());

    var result = processor().process(WorkerRequestParser.parse(HEAD + "}"), workspace);

    assertEquals(WorkerResult.Status.COMPLETED, result.status());
  }

  @Test
  void maxPagesOfOneStillYieldsLimitExceededOnATwoPageFixture() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.build(Options.plain().pages(2)));

    var result = processor().process(WorkerRequestParser.parse(HEAD + ",\"limits\":{\"maxPages\":1}}"), workspace);

    assertEquals(WorkerResult.Status.UNSUPPORTED, result.status());
    assertEquals(WorkerResult.ErrorCode.LIMIT_EXCEEDED, result.error().code());
  }

  private static PdfProcessor processor() {
    PdfValidator passing =
        (pdf, report, timeout) -> {
          try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, "{}");
          } catch (IOException e) {
            throw new AssertionError(e);
          }
          return new ValidationOutcome(ValidationStatus.PASSED, Map.of(), Map.of(), report, null, null, "fake");
        };
    return new PdfProcessor(new PdfPreflight(), passing, List.of());
  }
}
