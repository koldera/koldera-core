package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An untagged scan whose invisible OCR layer shows every glyph as {@code .notdef} (the shape of a typical OCR scan), through the
 * production rules and the real veraPDF: 7.21.8-1 fails in the baseline; neither 7.21.8-1 nor 7.21.3.2-1 nor 7.21.4.2-2 fails in
 * the copy.
 */
class InvisibleTextUploadIT {

  @TempDir Path root;

  @Test
  void invisibleOcrTextNoLongerReferencesNotdef() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedInvisibleOcrText(false, false));
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Scan", "scan.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.21.8-1")),
        "the fixture reproduces the .notdef class: " + context);
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action font = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FONT_INVISIBLE_TEXT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, font.outcome(), context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId)
        .anyMatch(r -> r.endsWith(" 7.21.8-1") || r.endsWith(" 7.21.3.2-1") || r.endsWith(" 7.21.4.2-2")), context);
  }
}
