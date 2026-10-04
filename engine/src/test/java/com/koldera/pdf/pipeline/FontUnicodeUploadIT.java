package com.koldera.pdf.pipeline;

import static java.nio.charset.StandardCharsets.US_ASCII;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An untagged upload whose Type 1 font names a TeX glyph ({@code summationtext}) and an AGL ligature
 * ({@code f_f}) in its {@code Differences}, through the production rules and the real veraPDF: 7.21.7-1
 * (a glyph without a Unicode value) fails in the baseline and neither 7.21.7-1 nor 7.21.7-2 fails in the
 * copy. The non-embedded Helvetica's 7.21.4.1-1 may remain; nothing is asserted about it.
 */
class FontUnicodeUploadIT {

  @TempDir Path root;

  @Test
  void aTexAndLigatureFontLosesItsUnicodeFailures() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(),
        SyntheticPdfFactory.untaggedType1WithDifferences(Map.of(80, "summationtext", 81, "f_f"), "APQ".getBytes(US_ASCII), null));
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Sums", "sums.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.21.7-1")), context);
    WorkerResult.Action font = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FONT_TOUNICODE_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, font.outcome(), context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.21.7-1") || r.endsWith(" 7.21.7-2")), context);
  }
}
