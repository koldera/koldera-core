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
 * An untagged two-column upload drawn line by line across the columns, through the production rules
 * and the real veraPDF: the structure is inferred from the layout (column reading order) and no
 * content check fails.
 */
class InferredStructureUploadIT {

  @TempDir Path root;

  @Test
  void aTwoColumnReportIsCleanOfContentChecks() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedTwoColumnsInterleaved());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Report", "report.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, inferred.outcome(), context);
    assertTrue(inferred.reason().contains("pages reordered 1"), inferred.reason());
    for (String rule : new String[] {" 7.1-3", " 7.1-11", " 6.2-1"}) {
      assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(rule)), rule + " still fails: " + context);
    }
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.contains(" 7.2-")), "a 7.2 check fails: " + context);
    assertTrue(result.findings().stream().anyMatch(f -> f.ruleId().equals("STRUCTURE_INFERRED")), result.findings().toString());
  }

  @Test
  void aStructuredArticleIsCleanOfStructureChecks() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.untaggedStructuredArticle());
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174001"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Annual review", "review.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, inferred.outcome(), context);
    assertTrue(inferred.reason().contains("headings 3, lists 2, list items 6"), inferred.reason());
    for (FailedRule failed : result.finalValidation().failedRules()) {
      String id = failed.ruleId();
      assertFalse(id.endsWith(" 7.1-3") || id.contains(" 7.2-") || id.contains(" 7.4."), id + " fails: " + context);
    }
    try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc));
    }
  }

  @Test
  void aRuledTableIsCleanOfTableChecks() throws Exception {
    assertCleanTable(SyntheticPdfFactory.untaggedRuledTable(), "123e4567-e89b-42d3-a456-426614174002");
  }

  @Test
  void anAlignedTableIsCleanOfTableChecks() throws Exception {
    assertCleanTable(SyntheticPdfFactory.untaggedAlignedTable(), "123e4567-e89b-42d3-a456-426614174003");
  }

  @Test
  void aRuledTableWithAnEmptyCellIsCleanOfTableChecks() throws Exception {
    // The empty cell is an empty TD: real veraPDF and the PAC-only structure checks must accept it.
    assertCleanTable(SyntheticPdfFactory.untaggedRuledTableWithEmptyCell(), "123e4567-e89b-42d3-a456-426614174004");
  }

  private void assertCleanTable(byte[] input, String jobId) throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), input);
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString(jobId), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Revenue", "revenue.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, inferred.outcome(), context);
    assertTrue(inferred.reason().contains("tables 1"), inferred.reason());
    for (FailedRule failed : result.finalValidation().failedRules()) {
      String id = failed.ruleId();
      assertFalse(id.contains(" 7.5-") || id.contains(" 7.2-") || id.endsWith(" 7.1-3"), id + " fails: " + context);
    }
    try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc));
      assertEquals(1, com.koldera.pdf.generated.StructTree.elementsOfType(doc, "Table").size());
    }
  }
}
