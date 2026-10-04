package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.FailedRule;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.report.FindingsBuilder;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Non-embedded Helvetica (standard 14 Type 1 without {@code /Widths}) and Word-style ArialMT/TimesNewRomanPSMT (TrueType with
 * {@code /Widths}) through the production rules and the real veraPDF: 7.21.4.1-1 fails in the baseline; after the metric-compatible
 * Liberation substitutes are embedded none of the font embedding, width, encoding or Unicode checks fails.
 */
class FontEmbeddingUploadIT {

  @TempDir Path root;

  private static final List<String> FONT_CHECKS = List.of("7.21.4.1-1", "7.21.4.1-2", "7.21.5-1", "7.21.6-1", "7.21.6-2", "7.21.6-3", "7.21.7-1");

  @Test
  void embeddedSubstitutesPassTheFontChecks() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedNonEmbeddedMix());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Fonts", "fonts.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.21.4.1-1")),
        "the fixture reproduces the non-embedded class: " + context);
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action font = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FONT_EMBEDDING_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, font.outcome(), context);
    for (FailedRule failed : result.finalValidation().failedRules()) {
      assertTrue(FONT_CHECKS.stream().noneMatch(c -> failed.ruleId().endsWith(" " + c)), failed.ruleId() + " still fails: " + context);
    }
    assertEquals(3, result.findings().stream().filter(f -> f.ruleId().equals(FindingsBuilder.FONT_EMBEDDED_SUBSTITUTE)).count(), context);
  }
}
