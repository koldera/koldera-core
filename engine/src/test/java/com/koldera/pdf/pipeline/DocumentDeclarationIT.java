package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.validation.VeraPdfCliValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real Typst ua-1 output through the real veraPDF: it passes as rendered, and fails only 5-1 once the identifier is withdrawn. */
class DocumentDeclarationIT {

  @TempDir Path root;

  @Test
  void aTypstDocumentWithoutItsIdentifierFailsOnlyTheIdentifierCheck() throws Exception {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace workspace = JobWorkspace.open(root);
    try (var in = DocumentDeclarationIT.class.getResourceAsStream("/typst/typst-ua1-note.pdf")) {
      Files.write(workspace.inputFile(), in.readAllBytes());
    }
    WorkerRequest request = new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        WorkerRequest.Mode.FIX, "ua1", null, null, "document.pdf", WorkerRequest.Origin.DOCUMENT_GENERATED, new WorkerRequest.Limits(10_485_760, 20, 300));

    WorkerResult result = new PdfProcessor(new PdfPreflight(), VeraPdfCliValidator.fromEnvironment(), Main.productionRules()).process(request, workspace);

    String context = result.error() + " baseline=" + result.baseline() + " final=" + result.finalValidation() + " actions=" + result.actions();
    assertEquals(ValidationStatus.PASSED, result.baseline().status(), context);
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus(), context);
    assertEquals(ActionOutcome.APPLIED, result.actions().get(0).outcome(), context);
    assertEquals(List.of("ISO 14289-1:2014 5-1"), result.finalValidation().failedRules().stream().map(WorkerResult.FailedRule::ruleId).toList(), context);
    assertEquals(ValidationStatus.DECLARATION_WITHHELD, result.finalValidation().status(), context);
    try (PDDocument copy = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      assertFalse(new String(copy.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8).contains("<pdfuaid:part>"));
    }
  }
}
