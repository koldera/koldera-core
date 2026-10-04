package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * An untagged upload with one raster image, through the production rules and the real veraPDF: the
 * basic tagger's Figure (no alternative text) becomes an artifact, so no figure-alt check (7.3-1) fails.
 */
class FiguresWithoutAltUploadIT {

  @TempDir Path root;

  @Test
  void noFigureAltCheckRemainsOnAnUntaggedArticle() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedArticle());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Article", "article.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action figures = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_FIGURES_WITHOUT_ALT_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, figures.outcome(), context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.3-1")), "7.3-1 still fails: " + context);
  }
}
