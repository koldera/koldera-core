package com.koldera.pdf.pipeline;

import static com.koldera.pdf.pipeline.UploadIdentifierTest.failed;
import static com.koldera.pdf.pipeline.UploadIdentifierTest.passed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerRequest;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResult.ActionOutcome;
import com.koldera.pdf.contracts.WorkerResult.ErrorCode;
import com.koldera.pdf.contracts.WorkerResult.RemediationStatus;
import com.koldera.pdf.contracts.WorkerResult.RuleId;
import com.koldera.pdf.contracts.WorkerResult.Status;
import com.koldera.pdf.contracts.WorkerResult.ValidationStatus;
import com.koldera.pdf.contracts.WorkerResultCodec;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.preflight.PdfPreflight;
import com.koldera.pdf.rules.PdfUaIdentifierStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Documents rendered by Typst with {@code --pdf-standard ua-1}: Typst writes {@code pdfuaid:part=1} itself. A {@code fix} run with
 * origin {@code document_generated} withdraws exactly that identifier ({@code PDF_UA_IDENTIFIER_WITHDRAWN_V1}) and repairs nothing
 * else; the identifier step may write it back only on the reviewed opt-in. A copy failing only {@code 5-1} is {@code declaration_withheld}.
 */
class DocumentDeclarationTest {

  private static final String UAID_RULE = "ISO 14289-1:2014 5-1";
  private static final String OTHER_RULE = "ISO 14289-1:2014 7.1-3";
  private static final String IDENTIFIER_ELEMENT = "<pdfuaid:part>1</pdfuaid:part>";

  @TempDir Path root;
  private JobWorkspace workspace;
  private UploadIdentifierTest.ScriptedValidator validator;
  private byte[] typst;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("in"));
    workspace = JobWorkspace.open(root);
    validator = new UploadIdentifierTest.ScriptedValidator();
    try (var in = DocumentDeclarationTest.class.getResourceAsStream("/typst/typst-ua1-note.pdf")) {
      typst = in.readAllBytes();
    }
    Files.write(workspace.inputFile(), typst);
  }

  @Test
  void theRenderersIdentifierIsWithdrawnAndTheVerdictIsDeclarationWithheld() throws IOException {
    validator.enqueue(passed());
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));

    WorkerResult result = production().process(document(), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.APPLIED, result.remediationStatus());
    assertEquals(List.of(RuleId.PDF_UA_IDENTIFIER_WITHDRAWN_V1, RuleId.PDF_UA_IDENTIFIER_V1), result.actions().stream().map(WorkerResult.Action::ruleId).toList(),
        "nothing but the withdrawal and the identifier step: the rendered document is not repaired");
    WorkerResult.Action withdrawn = result.actions().get(0);
    assertEquals(ActionOutcome.APPLIED, withdrawn.outcome(), withdrawn.reason());
    assertEquals(PdfUaIdentifierStep.WITHDRAWN, withdrawn.reason());
    assertEquals(PdfUaIdentifierStep.NOT_REQUESTED, result.actions().get(1).reason());
    assertEquals(ValidationStatus.DECLARATION_WITHHELD, result.finalValidation().status());
    assertEquals(List.of(UAID_RULE), result.finalValidation().failedRules().stream().map(WorkerResult.FailedRule::ruleId).toList(), "the missing identifier stays listed");
    assertEquals(2, validator.calls.size(), "the rendered PDF and the withdrawn copy");

    String before = xmp(typst);
    String after = xmp(Files.readAllBytes(workspace.outputFile("remediated.pdf")));
    assertTrue(before.contains(IDENTIFIER_ELEMENT), before);
    assertEquals(before.replace(IDENTIFIER_ELEMENT, ""), after, "only the identifier element is removed; every other XMP byte stays");
    try (PDDocument original = Loader.loadPDF(typst); PDDocument copy = Loader.loadPDF(workspace.outputFile("remediated.pdf").toFile())) {
      assertEquals(original.getNumberOfPages(), copy.getNumberOfPages());
      assertEquals(PdfPreflight.extractText(original), PdfPreflight.extractText(copy));
      assertEquals(original.getDocumentInformation().getCOSObject().toString(), copy.getDocumentInformation().getCOSObject().toString(), "Info is untouched");
      assertEquals(original.getDocumentCatalog().getLanguage(), copy.getDocumentCatalog().getLanguage());
    }
    assertTrue(result.manualReviewRequired());
    assertSchemaValid(result);
  }

  @Test
  void theReviewedOptInPutsTheIdentifierBackAfterTheMachineChecks() throws IOException {
    validator.enqueue(passed());
    validator.enqueue(failed(Map.of(UAID_RULE, 1)));
    validator.enqueue(passed());

    WorkerResult result = production().process(UploadIdentifierTest.declaring(document(), true), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(ActionOutcome.APPLIED, result.actions().get(0).outcome());
    WorkerResult.Action ua = result.actions().get(1);
    assertEquals(ActionOutcome.APPLIED, ua.outcome(), ua.reason());
    assertEquals(ValidationStatus.PASSED, result.finalValidation().status());
    assertEquals(3, validator.calls.size());
    assertTrue(xmp(Files.readAllBytes(workspace.outputFile("remediated.pdf"))).contains("pdfuaid"), "declared on request after review");
    assertSchemaValid(result);
  }

  @Test
  void anotherFailingRuleKeepsTheVerdictFailed() throws IOException {
    validator.enqueue(failed(Map.of(OTHER_RULE, 1)));
    validator.enqueue(failed(Map.of(OTHER_RULE, 1, UAID_RULE, 1)));

    WorkerResult result = production().process(document(), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(ValidationStatus.FAILED, result.finalValidation().status());
    assertSchemaValid(result);
  }

  @Test
  void withdrawingMayAddOnlyTheIdentifierCheck() throws IOException {
    validator.enqueue(passed());
    validator.enqueue(failed(Map.of(UAID_RULE, 1, OTHER_RULE, 1)));

    WorkerResult result = production().process(document(), workspace);

    assertEquals(Status.FAILED, result.status());
    assertEquals(RemediationStatus.ROLLED_BACK, result.remediationStatus());
    assertEquals(ErrorCode.INTEGRITY_REGRESSION, result.error().code());
    assertFalse(Files.exists(workspace.outputFile("remediated.pdf")), "nothing is published");
    assertSchemaValid(result);
  }

  @Test
  void aDocumentWithoutAnIdentifierHasNothingToWithdraw() throws IOException {
    Files.write(workspace.inputFile(), SyntheticPdfFactory.tagged());
    validator.enqueue(passed());

    WorkerResult result = production().process(document(), workspace);

    assertEquals(Status.COMPLETED, result.status(), String.valueOf(result.error()));
    assertEquals(RemediationStatus.NO_APPLICABLE_FIX, result.remediationStatus());
    assertEquals(List.of(RuleId.PDF_UA_IDENTIFIER_WITHDRAWN_V1), result.actions().stream().map(WorkerResult.Action::ruleId).toList());
    assertEquals(ActionOutcome.SKIPPED, result.actions().get(0).outcome());
    assertFalse(Files.exists(workspace.outputFile("remediated.pdf")));
    assertSchemaValid(result);
  }

  private PdfProcessor production() {
    return new PdfProcessor(new PdfPreflight(), validator, Main.productionRules());
  }

  private static WorkerRequest document() {
    return new WorkerRequest(
        "1.0", UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), WorkerRequest.CANONICAL_INPUT_PATH, WorkerRequest.CANONICAL_OUTPUT_DIRECTORY,
        WorkerRequest.Mode.FIX, "ua1", null, null, "document.pdf", WorkerRequest.Origin.DOCUMENT_GENERATED, new WorkerRequest.Limits(10_485_760, 20, 60));
  }

  private static String xmp(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      assertNotNull(doc.getDocumentCatalog().getMetadata());
      return new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static void assertSchemaValid(WorkerResult result) {
    var errors = ContractSchemas.validateResult(WorkerResultCodec.toJson(result));
    assertTrue(errors.isEmpty(), errors::toString);
  }
}
