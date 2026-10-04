package com.koldera.pdf.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureInspectorTest {

  @TempDir Path tmp;

  private final StructureInspector inspector = new StructureInspector();

  @Test
  void untaggedDocumentReportsNoUsableTags() throws IOException {
    byte[] bytes = SyntheticPdfFactory.simpleUntagged();
    Path input = SyntheticPdfFactory.write(tmp.resolve("untagged.pdf"), bytes);

    InspectResult result = inspector.inspect(input, 10_485_760);

    assertEquals(InspectResult.Status.INSPECTED, result.status());
    assertNull(result.error());
    assertEquals(sha256(bytes), result.sha256());
    assertEquals(bytes.length, result.bytes());
    InspectResult.Structure s = result.structure();
    assertNotNull(s);
    assertEquals(1, s.pages());
    assertFalse(s.encrypted());
    assertFalse(s.markInfoMarked());
    assertFalse(s.structTreeRoot());
    assertEquals(0, s.structureElements());
    assertEquals(0, s.contentReferences());
    assertFalse(s.usableTags());
    assertTrue(s.roles().isEmpty());
    assertNull(s.language());
    assertNull(s.title());
  }

  @Test
  void taggedDocumentCountsElementsRolesAndContentReferences() throws IOException {
    Path input = SyntheticPdfFactory.write(tmp.resolve("tagged.pdf"), SyntheticPdfFactory.tagged());

    InspectResult result = inspector.inspect(input, 10_485_760);

    assertEquals(InspectResult.Status.INSPECTED, result.status());
    InspectResult.Structure s = result.structure();
    assertNotNull(s);
    assertTrue(s.markInfoMarked());
    assertTrue(s.structTreeRoot());
    assertEquals(3, s.structureElements(), "Document, H1, P");
    assertEquals(2, s.contentReferences(), "two MCIDs");
    assertTrue(s.usableTags());
    assertEquals(1, s.roles().stream().filter(r -> r.role().equals("H1")).count());
    assertEquals(1, s.roles().stream().filter(r -> r.role().equals("P")).findFirst().orElseThrow().count());
    assertEquals("en", s.language());
    assertEquals("Tagged synthetic fixture", s.title());
  }

  @Test
  void encryptedDocumentIsTypedNotCrashed() throws IOException {
    Path input = SyntheticPdfFactory.write(tmp.resolve("enc.pdf"), SyntheticPdfFactory.encrypted("secret"));

    InspectResult result = inspector.inspect(input, 10_485_760);

    assertEquals(InspectResult.Status.FAILED, result.status());
    assertNotNull(result.error());
    assertEquals(InspectResult.ErrorCode.UNSUPPORTED_ENCRYPTED, result.error().code());
    assertNull(result.structure());
  }

  @Test
  void corruptTruncatedAndOversizedInputsAreTyped() throws IOException {
    InspectResult corrupt = inspector.inspect(SyntheticPdfFactory.write(tmp.resolve("c.pdf"), SyntheticPdfFactory.corrupt()), 10_485_760);
    assertEquals(InspectResult.ErrorCode.INVALID_INPUT, corrupt.error().code());

    InspectResult notPdf = inspector.inspect(SyntheticPdfFactory.write(tmp.resolve("n.pdf"), "hello".getBytes()), 10_485_760);
    assertEquals(InspectResult.ErrorCode.INVALID_INPUT, notPdf.error().code());

    InspectResult missing = inspector.inspect(tmp.resolve("missing.pdf"), 10_485_760);
    assertEquals(InspectResult.ErrorCode.INVALID_INPUT, missing.error().code());

    InspectResult big = inspector.inspect(SyntheticPdfFactory.write(tmp.resolve("b.pdf"), SyntheticPdfFactory.simpleUntagged()), 16);
    assertEquals(InspectResult.ErrorCode.LIMIT_EXCEEDED, big.error().code());
  }

  @Test
  void resultSerializesToTheContractSchema() throws IOException {
    InspectResult tagged = inspector.inspect(SyntheticPdfFactory.write(tmp.resolve("t.pdf"), SyntheticPdfFactory.tagged()), 10_485_760);
    InspectResult failed = inspector.inspect(tmp.resolve("missing.pdf"), 10_485_760);

    assertEquals(java.util.Set.of(), ContractSchemas.validateInspectResult(InspectResultCodec.toJson(tagged)));
    assertEquals(java.util.Set.of(), ContractSchemas.validateInspectResult(InspectResultCodec.toJson(failed)));
    assertTrue(InspectResultCodec.toJson(tagged).contains("\"usableTags\" : true"));
  }

  @Test
  void neverWritesOrChangesTheInput() throws IOException {
    byte[] bytes = SyntheticPdfFactory.tagged();
    Path input = SyntheticPdfFactory.write(tmp.resolve("ro.pdf"), bytes);

    inspector.inspect(input, 10_485_760);

    assertEquals(sha256(bytes), sha256(Files.readAllBytes(input)));
    assertEquals(1, Files.list(tmp).count());
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
