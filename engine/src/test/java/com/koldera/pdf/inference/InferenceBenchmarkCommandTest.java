package com.koldera.pdf.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koldera.pdf.cli.Main;
import com.koldera.pdf.fixtures.SyntheticPdfFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code infer-benchmark}: producer tags as truth, the inference on a stripped copy, matched by text. */
class InferenceBenchmarkCommandTest {

  @TempDir Path dir;

  @Test
  void theStructuredArticleIsRecoveredExactly() throws Exception {
    Path input = SyntheticPdfFactory.write(dir.resolve("article.pdf"), SyntheticPdfFactory.taggedStructuredArticle());
    Path output = dir.resolve("out/benchmark.json");

    int exit = Main.dispatch(new String[] {"infer-benchmark", "--input", input.toString(), "--output", output.toString()}, silent());

    assertEquals(0, exit);
    JsonNode json = new ObjectMapper().readTree(Files.readString(output, StandardCharsets.UTF_8));
    assertEquals("article.pdf", json.get("file").asText());
    assertClass(json.get("headings"), 3, 3, 3, 1.0, 1.0);
    assertClass(json.get("listItems"), 6, 6, 6, 1.0, 1.0);
    assertEquals(0, json.get("tables").get("truth").asInt());
    assertEquals(0, json.get("tables").get("inferred").asInt());
  }

  @Test
  void aWordHeadingStyleThatTheRoleMapSendsToPIsATruthHeading() throws Exception {
    // Word writes "Heading 1" -> /P into the /RoleMap: the style name is the author's heading mark.
    byte[] pdf = SyntheticPdfFactory.taggedBlocks(SyntheticPdfFactory.TaggedOptions.of(
            SyntheticPdfFactory.Block.of("Heading 1", "Results of the year"),
            SyntheticPdfFactory.Block.of("P", "The first body paragraph of the fixture."),
            SyntheticPdfFactory.Block.of("P", "The second body paragraph of the fixture."))
        .roleMap(java.util.Map.of("Heading 1", "P")));
    Path input = SyntheticPdfFactory.write(dir.resolve("word.pdf"), pdf);
    Path output = dir.resolve("word.json");

    assertEquals(0, Main.dispatch(new String[] {"infer-benchmark", "--input", input.toString(), "--output", output.toString()}, silent()));

    JsonNode json = new ObjectMapper().readTree(Files.readString(output, StandardCharsets.UTF_8));
    assertClass(json.get("headings"), 1, 1, 1, 1.0, 1.0);
  }

  @Test
  void tablesAreMatchedByCellText() throws Exception {
    // The producer's caption is a P before its Table (as Word writes it), the inferred one a Caption inside the Table:
    // tables are keyed by their cells' text only.
    Path input = SyntheticPdfFactory.write(dir.resolve("table.pdf"), SyntheticPdfFactory.taggedRuledTable());
    Path output = dir.resolve("table.json");

    assertEquals(0, Main.dispatch(new String[] {"infer-benchmark", "--input", input.toString(), "--output", output.toString()}, silent()));

    JsonNode json = new ObjectMapper().readTree(Files.readString(output, StandardCharsets.UTF_8));
    assertClass(json.get("tables"), 1, 1, 1, 1.0, 1.0);
    assertEquals(1, json.get("inference").get("tables").asInt());
  }

  @Test
  void anUntaggedInputIsRefused() throws Exception {
    Path input = SyntheticPdfFactory.write(dir.resolve("plain.pdf"), SyntheticPdfFactory.simpleUntagged());
    Path output = dir.resolve("plain.json");

    assertEquals(1, Main.dispatch(new String[] {"infer-benchmark", "--input", input.toString(), "--output", output.toString()}, silent()));
    assertFalse(Files.exists(output));
  }

  @Test
  void usageErrorsExitTwo() {
    assertEquals(2, Main.dispatch(new String[] {"infer-benchmark", "--input"}, silent()));
    assertEquals(2, Main.dispatch(new String[] {"infer-benchmark", "--output", "x.json"}, silent()));
    assertEquals(2, Main.dispatch(new String[] {"infer-benchmark", "--input", "a.pdf", "--output", "b.json", "--other", "c"}, silent()));
  }

  @Test
  void theLogLineIsEscapedJson() throws Exception {
    String line = InferenceBenchmarkCommand.logLine("benchmark_refused", "the inference refused the document: a \"quoted\" \\ reason");
    JsonNode json = new ObjectMapper().readTree(line);
    assertEquals("benchmark_refused", json.get("event").asText());
    assertEquals("the inference refused the document: a \"quoted\" \\ reason", json.get("reason").asText());
  }

  @Test
  void theMatchingKeyIsNormalized() {
    assertEquals("firstheadingofthetext", InferenceBenchmarkCommand.key("  ﬁrst   Heading\tof the TEXT "));
    // MCIDs split at different places put spaces at different places ("Selected k ey p oints"): the key ignores them.
    assertEquals(InferenceBenchmarkCommand.key("Selected key points"), InferenceBenchmarkCommand.key("Selected k ey p oints"));
    // The whole text counts: a heading is not matched by a paragraph that merely starts with it.
    assertEquals(200, InferenceBenchmarkCommand.key("x ".repeat(200)).length());
    assertFalse(InferenceBenchmarkCommand.key("Results").equals(InferenceBenchmarkCommand.key("Results of the year were measured in all regions")));
    // A match: the first 80 key characters agree and the whole keys differ in length by at most 0.8-1.25.
    assertFalse(InferenceBenchmarkCommand.matches("Results", "Results of the year were measured in all regions"));
    String item = "The applicant states the purpose of the request and the period it covers in the form";
    org.junit.jupiter.api.Assertions.assertTrue(InferenceBenchmarkCommand.matches(item, item + " [2]"));
    org.junit.jupiter.api.Assertions.assertTrue(InferenceBenchmarkCommand.matches("Selected key points", "Selected k ey p oints"));
    assertFalse(InferenceBenchmarkCommand.matches(item, item + " and a second paragraph that the inference merged into the item by mistake"));
  }

  private static void assertClass(JsonNode node, int truth, int inferred, int matched, double precision, double recall) {
    assertEquals(truth, node.get("truth").asInt(), node.toString());
    assertEquals(inferred, node.get("inferred").asInt(), node.toString());
    assertEquals(matched, node.get("matched").asInt(), node.toString());
    assertEquals(precision, node.get("precision").asDouble(), node.toString());
    assertEquals(recall, node.get("recall").asDouble(), node.toString());
  }

  private static PrintStream silent() {
    return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
  }
}
