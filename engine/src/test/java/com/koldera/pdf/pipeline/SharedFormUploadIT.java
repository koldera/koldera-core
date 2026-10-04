package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.FailedRule;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An untagged upload whose master-page item is one form XObject with text drawn on every page (the
 * shape of the InDesign reports), through the production rules and the real veraPDF: each draw is
 * wrapped from the page stream as a pagination artifact, so no content is left untagged.
 */
class SharedFormUploadIT {

  @TempDir Path root;

  @Test
  void aSharedFormOnEveryPageLeavesNo7_1_3() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedWithSharedTextFormOnEveryPage(3));
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Report", "report.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    for (String rule : new String[] {" 7.1-3", " 7.1-11", " 6.2-1"}) {
      assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(rule)), rule + " still fails: " + context);
    }
  }
}
