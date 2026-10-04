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
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tagged <em>upload</em> using PDF 2.0 type names (as Chromium writes them) through the production
 * rules and the real veraPDF: 7.1-5 (non-standard type without role mapping) fails in the baseline
 * and is gone from the fix copy; the strict upload gate passes; no identifier is written for an upload.
 */
class RoleMapUploadIT {

  @TempDir Path root;

  @Test
  void aTaggedUploadWithStrongEmAndAsideLosesItsRoleMappingFailures() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.taggedBlocks(
        TaggedOptions.of(Block.of("H1", "Heading"), Block.of("Strong", "Important"), Block.of("Em", "Stressed"), Block.of("Aside", "An aside"))));
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Report", "report.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.1-5")), context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.1-5")), context);
    WorkerResult.Action roleMap = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_ROLE_MAP_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, roleMap.outcome(), context);
    // Uploads in mode fix list the identifier step too (UploadIdentifierTest pins when it writes).
    assertEquals(RuleId.PDF_UA_IDENTIFIER_V1, result.actions().get(result.actions().size() - 1).ruleId(), context);
  }
}
