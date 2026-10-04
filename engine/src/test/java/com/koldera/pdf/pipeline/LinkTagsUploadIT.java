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
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.rules.LinkTagsRuleTest;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tagged upload whose link annotation is outside the structure tree, through the production rules
 * and the real veraPDF: the 7.18 link and tab-order checks fail in the baseline and are gone from the
 * fix copy, and the strict upload gate passes.
 */
class LinkTagsUploadIT {

  @TempDir Path root;

  @Test
  void aTaggedUploadsUntaggedLinkIsTaggedDescribedAndGetsStructureTabOrder() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), LinkTagsRuleTest.taggedWithUntaggedLink());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Report", "report.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertTrue(result.baseline().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.contains(" 7.18.")), context);
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.contains(" 7.18.")), context);
    for (RuleId repair : new RuleId[] {RuleId.PDF_LINK_TAGS_V1, RuleId.PDF_TAB_ORDER_V1}) {
      WorkerResult.Action action = result.actions().stream().filter(a -> a.ruleId() == repair).findFirst().orElseThrow();
      assertEquals(ActionOutcome.APPLIED, action.outcome(), context);
    }
  }
}
