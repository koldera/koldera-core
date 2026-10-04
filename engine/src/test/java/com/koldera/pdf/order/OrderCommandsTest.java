package com.koldera.pdf.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import com.koldera.pdf.preflight.PdfPreflight;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI envelopes of {@code structure} and {@code reorder}. */
class OrderCommandsTest {

  @TempDir Path tmp;
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

  @Test
  void structureWritesSchemaValidSnapshotWithoutPathsInLogs() throws IOException {
    Path input = SyntheticPdfFactory.write(tmp.resolve("in.pdf"), SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Secret title"), Block.of("P", "Body"))));
    Path result = tmp.resolve("nested/structure.json");

    int code = Main.dispatch(new String[] {"structure", "--input", input.toString(), "--result", result.toString()}, new PrintStream(stderr));

    assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
    String json = Files.readString(result, StandardCharsets.UTF_8);
    assertEquals(java.util.Set.of(), ContractSchemas.validateStructureResult(json));
    JsonNode node = new ObjectMapper().readTree(json);
    assertEquals("inspected", node.get("status").asText());
    assertEquals(3, node.get("nodes").size());
    String log = stderr.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("\"event\":\"structure_written\""), log);
    assertFalse(log.contains(tmp.toString()));
    assertFalse(log.contains("Secret title"));
    assertEquals(2, Main.dispatch(new String[] {"structure", "--input", "x"}, new PrintStream(stderr)));
  }

  @Test
  void reorderAppliesAnApprovedRequestAndRefusesAnInvalidOne() throws IOException {
    byte[] bytes = SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(Block.of("H1", "Intro"), Block.of("P", "Second")).structureOrder(1, 0));
    Files.createDirectories(tmp.resolve("in"));
    Files.createDirectories(tmp.resolve("out"));
    Files.write(tmp.resolve("in/document.pdf"), bytes);
    String request = """
        {"specVersion":"1.0","jobId":"123e4567-e89b-42d3-a456-426614174000","inputPath":"/work/in/document.pdf","outputPath":"/work/out/reordered.pdf",
         "baseSha256":"%s","parentPath":[0],"orderedKidIndexes":[1,0],"limits":{"maxBytes":10485760,"maxPages":50,"timeoutSeconds":60}}
        """.formatted(PdfPreflight.sha256Hex(bytes));
    Files.writeString(tmp.resolve("request.json"), request);

    int code = Main.dispatch(new String[] {"reorder", "--request", tmp.resolve("request.json").toString(), "--result", tmp.resolve("result.json").toString(), "--work-root", tmp.toString()}, new PrintStream(stderr));

    assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
    String json = Files.readString(tmp.resolve("result.json"));
    assertEquals(java.util.Set.of(), ContractSchemas.validateReorderResult(json));
    JsonNode node = new ObjectMapper().readTree(json);
    assertEquals("applied", node.get("status").asText());
    assertTrue(Files.exists(tmp.resolve("out/reordered.pdf")));
    assertEquals(node.get("outputSha256").asText(), PdfPreflight.sha256Hex(Files.readAllBytes(tmp.resolve("out/reordered.pdf"))));

    Files.writeString(tmp.resolve("bad.json"), request.replace("[1,0]", "[1]"));
    assertEquals(0, Main.dispatch(new String[] {"reorder", "--request", tmp.resolve("bad.json").toString(), "--result", tmp.resolve("bad-result.json").toString(), "--work-root", tmp.toString()}, new PrintStream(stderr)));
    JsonNode bad = new ObjectMapper().readTree(Files.readString(tmp.resolve("bad-result.json")));
    assertEquals("failed", bad.get("status").asText());
    assertEquals("INVALID_INPUT", bad.get("error").get("code").asText());
    assertEquals(2, Main.dispatch(new String[] {"reorder", "--request", "x"}, new PrintStream(stderr)));
  }
}
