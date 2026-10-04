package com.koldera.pdf.fixtures;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.koldera.pdf.fixtures.SyntheticPdfFactory.Block;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.Options;
import com.koldera.pdf.fixtures.SyntheticPdfFactory.TaggedOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Writes the PDF half of an accessibility test corpus from the deterministic synthetic factory, so every fixture is
 * reproducible and free of customer content. Runs only when {@code -Dkoldera.corpus.out=DIR} is set:
 * {@code mvn -q test -Dtest=CorpusGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false -Dkoldera.corpus.out=DIR}.
 * The case ids must match the corpus manifest.
 */
class CorpusGeneratorTest {

  @Test
  void writeCorpus() throws IOException {
    String out = System.getProperty("koldera.corpus.out");
    assumeTrue(out != null && !out.isBlank(), "set -Dkoldera.corpus.out=DIR to (re)generate the corpus");
    Path dir = Path.of(out);
    Files.createDirectories(dir);
    Map<String, byte[]> cases = cases();
    StringBuilder index = new StringBuilder();
    for (Map.Entry<String, byte[]> entry : cases.entrySet()) {
      Path target = dir.resolve(entry.getKey() + ".pdf");
      Files.write(target, entry.getValue());
      index.append(entry.getKey()).append('\n');
    }
    Files.writeString(dir.resolve("INDEX.txt"), index.toString(), StandardCharsets.UTF_8);
  }

  static Map<String, byte[]> cases() {
    Map<String, byte[]> cases = new LinkedHashMap<>();
    // ---- untagged family: what a plain generator produces ------------------------------------------------
    cases.put("pdf-01-untagged-plain", SyntheticPdfFactory.simpleUntagged());
    cases.put("pdf-02-untagged-lang-de", SyntheticPdfFactory.build(Options.plain().language("de")));
    cases.put("pdf-03-untagged-title-no-display", SyntheticPdfFactory.build(Options.plain().infoTitle("Quarterly statement")));
    cases.put("pdf-04-untagged-title-display-true", SyntheticPdfFactory.build(Options.plain().infoTitle("Quarterly statement").displayDocTitle(true)));
    cases.put("pdf-05-untagged-empty-title", SyntheticPdfFactory.build(Options.plain().infoTitle("")));
    cases.put("pdf-06-untagged-five-pages", SyntheticPdfFactory.build(Options.plain().pages(5)));
    cases.put("pdf-07-untagged-image", SyntheticPdfFactory.build(Options.plain().image()));
    cases.put("pdf-08-untagged-visual-table", SyntheticPdfFactory.build(Options.plain().table()));
    cases.put("pdf-09-scanned-image-only", SyntheticPdfFactory.build(Options.plain().pages(2).scanned()));
    cases.put("pdf-10-untagged-hide-toolbar", SyntheticPdfFactory.build(Options.plain().infoTitle("Invoice 42").hideToolbar(true)));
    // ---- tagged family: structure present, order and semantics vary --------------------------------------
    cases.put("pdf-11-tagged-minimal", SyntheticPdfFactory.tagged());
    cases.put("pdf-12-tagged-linear-article", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Annual report"), Block.of("P", "First paragraph of the report."), Block.of("H2", "Results"), Block.of("P", "Second paragraph with results."))));
    cases.put("pdf-13-tagged-structure-reversed", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Title"), Block.of("P", "Step one"), Block.of("P", "Step two"), Block.of("P", "Step three")).structureOrder(0, 3, 2, 1)));
    cases.put("pdf-14-tagged-one-swap", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Notice"), Block.of("P", "Read this first."), Block.of("P", "Then this.")).structureOrder(0, 2, 1)));
    cases.put("pdf-15-tagged-link", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Links"), Block.of("P", "See the appendix"), Block.of("P", "Closing line")).link(1)));
    cases.put("pdf-16-tagged-nested-sections", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Handbook"), Block.of("P", "Intro"), Block.of("H2", "Chapter"), Block.of("P", "Body")).nested(true)));
    cases.put("pdf-17-tagged-two-pages", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Page one heading"), Block.of("P", "Page one body"), new Block("H2", "Page two heading", 2), new Block("P", "Page two body", 2))));
    cases.put("pdf-18-tagged-cross-page-swap", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Start"), Block.of("P", "Continues on the next page"), new Block("P", "Second page text", 2)).structureOrder(0, 2, 1)));
    cases.put("pdf-19-tagged-heading-skips-level", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Top"), Block.of("H3", "Skipped a level"), Block.of("P", "Paragraph"))));
    cases.put("pdf-20-tagged-heading-after-body", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("P", "Lead paragraph before any heading"), Block.of("H1", "Late heading"), Block.of("P", "Body"))));
    cases.put("pdf-21-tagged-two-columns", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.at("H1", "Two columns", 1, 72, 740),
        Block.at("P", "Left column first", 1, 72, 700), Block.at("P", "Left column second", 1, 72, 680),
        Block.at("P", "Right column first", 1, 320, 700), Block.at("P", "Right column second", 1, 320, 680))));
    cases.put("pdf-22-tagged-two-columns-tags-row-major", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.at("H1", "Two columns", 1, 72, 740),
        Block.at("P", "Left column first", 1, 72, 700), Block.at("P", "Left column second", 1, 72, 680),
        Block.at("P", "Right column first", 1, 320, 700), Block.at("P", "Right column second", 1, 320, 680)).structureOrder(0, 1, 3, 2, 4)));
    cases.put("pdf-23-tagged-visual-order-ambiguous", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.at("P", "Sidebar note", 1, 320, 720), Block.at("H1", "Main heading", 1, 72, 720), Block.at("P", "Main body", 1, 72, 690))));
    cases.put("pdf-24-tagged-long-list", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Checklist"), Block.of("P", "1. Alpha"), Block.of("P", "2. Bravo"), Block.of("P", "3. Charlie"), Block.of("P", "4. Delta"), Block.of("P", "5. Echo"), Block.of("P", "6. Foxtrot"))));
    // ---- refusals: the engine must not touch these ----------------------------------------------------------
    cases.put("pdf-25-encrypted-user-password", SyntheticPdfFactory.encrypted("s3cret"));
    cases.put("pdf-26-signed", SyntheticPdfFactory.signed());
    cases.put("pdf-27-corrupt", SyntheticPdfFactory.corrupt());
    cases.put("pdf-28-truncated", SyntheticPdfFactory.truncated());
    // ---- two more wrong-order tagged fixtures for the reading-order correction drill ------
    cases.put("pdf-29-tagged-three-way-rotation", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Recipe"), Block.of("P", "Gather the ingredients"), Block.of("P", "Mix them"), Block.of("P", "Bake for an hour")).structureOrder(0, 2, 3, 1)));
    cases.put("pdf-30-tagged-nested-sections-swapped", SyntheticPdfFactory.taggedBlocks(TaggedOptions.of(
        Block.of("H1", "Manual"), Block.of("P", "Overview"), Block.of("H2", "Setup"), Block.of("P", "Plug it in")).nested(true).structureOrder(0, 2, 1, 3)));
    return cases;
  }
}
