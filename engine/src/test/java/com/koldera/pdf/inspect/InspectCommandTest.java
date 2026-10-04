package com.koldera.pdf.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koldera.pdf.cli.Main;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI envelope of the read-only probe: {@code inspect --input PATH --result PATH [--max-bytes N]}. */
class InspectCommandTest {

  @TempDir Path tmp;
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

  @Test
  void writesSchemaValidResultAndExitsZero() throws IOException {
    Path input = SyntheticPdfFactory.write(tmp.resolve("in.pdf"), SyntheticPdfFactory.tagged());
    Path result = tmp.resolve("nested/inspect.json");

    int code = Main.dispatch(new String[] {"inspect", "--input", input.toString(), "--result", result.toString()}, new PrintStream(stderr));

    assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
    String json = Files.readString(result, StandardCharsets.UTF_8);
    assertEquals(java.util.Set.of(), ContractSchemas.validateInspectResult(json));
    JsonNode node = new ObjectMapper().readTree(json);
    assertEquals("inspected", node.get("status").asText());
    assertTrue(node.get("structure").get("usableTags").asBoolean());
    String log = stderr.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("\"event\":\"inspect_written\""), log);
    assertFalse(log.contains(tmp.toString()), "log must not contain host paths");
  }

  @Test
  void failedInspectionStillWritesTypedResultAndExitsZero() throws IOException {
    Path result = tmp.resolve("inspect.json");

    int code = Main.dispatch(new String[] {"inspect", "--input", tmp.resolve("missing.pdf").toString(), "--result", result.toString()}, new PrintStream(stderr));

    assertEquals(0, code);
    JsonNode node = new ObjectMapper().readTree(Files.readString(result));
    assertEquals("failed", node.get("status").asText());
    assertEquals("INVALID_INPUT", node.get("error").get("code").asText());
  }

  @Test
  void usageErrorsExitTwoWithoutResult() {
    assertEquals(2, Main.dispatch(new String[] {"inspect", "--input", "x"}, new PrintStream(stderr)));
    assertEquals(2, Main.dispatch(new String[] {"inspect", "--input", "x", "--result", "y", "--bogus", "z"}, new PrintStream(stderr)));
    assertEquals(2, Main.dispatch(new String[] {"inspect", "--input", "x", "--result", "y", "--max-bytes", "many"}, new PrintStream(stderr)));
    assertFalse(Files.exists(tmp.resolve("y")));
  }

  @Test
  void processVerbStillRoutesToTheExistingEnvelope() {
    int code = Main.dispatch(new String[] {"process", "--request", tmp.resolve("r.json").toString()}, new PrintStream(stderr));

    assertEquals(2, code, "existing usage error path (missing --result) is unchanged");
    assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("usage: process"));
  }
}
