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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Through the production rules and the real veraPDF: a {@code tagged_unusable} upload whose only image is a Figure described
 * "Sales by region, 2025" is rebuilt from its content, the rebuilt Figure keeps the description and is block-placed, no
 * figure-alt check (7.3-1) fails on the published copy, and the machine-check identifier is written.
 */
class DescribedFigureUploadIT {

  @TempDir Path root;

  @Test
  void aDescribedFigureSurvivesTheRebuildOfUnusableTags() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    Files.write(workspace.inputFile(), SyntheticPdfFactory.taggedUnusableWithDescribedImages(
        List.of(SyntheticPdfFactory.DescribedImages.figure("Sales by region, 2025")), null, true));
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH,
        WorkerRequest.CANONICAL_OUTPUT_DIRECTORY, WorkerRequest.Mode.FIX, "ua1", "en", "Sales", "sales.pdf",
        WorkerRequest.Origin.UPLOAD, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline().failedRules() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    WorkerResult.Action inferred = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_STRUCTURE_INFERRED_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, inferred.outcome(), context);
    assertTrue(inferred.reason().startsWith("strategy tagged_unusable"), inferred.reason());
    assertTrue(inferred.reason().endsWith("; alternative text carried to 1 rebuilt figure"), inferred.reason());
    assertFalse(result.finalValidation().failedRules().stream().map(FailedRule::ruleId).anyMatch(r -> r.endsWith(" 7.3-1")), "7.3-1 fails: " + context);
    WorkerResult.Action identifier = result.actions().stream().filter(a -> a.ruleId() == RuleId.PDF_UA_IDENTIFIER_V1).findFirst().orElseThrow();
    assertEquals(ActionOutcome.APPLIED, identifier.outcome(), "identifier not written: " + identifier.reason() + " " + context);
    try (PDDocument doc = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      List<String> alts = new ArrayList<>();
      collectFigureAlts(doc.getDocumentCatalog().getStructureTreeRoot().getKids(), alts);
      assertEquals(List.of("Sales by region, 2025"), alts, context);
      assertEquals(java.util.Map.of(), com.koldera.pdf.generated.StructureRules.check(doc), context);
    }
  }

  private static void collectFigureAlts(List<Object> kids, List<String> out) {
    for (Object kid : kids) {
      if (kid instanceof PDStructureElement element) {
        if ("Figure".equals(element.getStructureType())) {
          out.add(element.getCOSObject().getString(COSName.ALT));
        }
        collectFigureAlts(element.getKids(), out);
      }
    }
  }
}
