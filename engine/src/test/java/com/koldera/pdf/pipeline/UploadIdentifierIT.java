package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An untagged upload in an embedded font, through the production rules and the real veraPDF: the structure is inferred
 * and the copy fails only the missing identifier, yet {@code PDF_UA_IDENTIFIER_V1} writes nothing: the identifier is a
 * conformance declaration, never written by default (and never over inferred structure).
 */
class UploadIdentifierIT {

  @TempDir Path root;

  @Test
  void anEmbeddedFontArticleCleanExceptTheIdentifierIsNotDeclared() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedEmbeddedFontArticle());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Quarterly summary", "summary.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertEquals(java.util.List.of("ISO 14289-1:2014 5-1"),
        result.finalValidation().failedRules().stream().map(WorkerResult.FailedRule::ruleId).toList(), context);
    WorkerResult.Action ua = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_UA_IDENTIFIER_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.SKIPPED, ua.outcome(), context);
    assertEquals(com.koldera.pdf.rules.PdfUaIdentifierStep.NOT_REQUESTED, ua.reason(), context);
    assertTrue(result.manualReviewRequired());
  }
}
