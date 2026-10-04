package com.koldera.pdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobWorkspaceTest {

  @Test
  void mapsCanonicalContainerPathsOntoTheMountedRoot(@TempDir Path root) throws IOException {
    Files.createDirectories(root.resolve("in"));
    Files.createDirectories(root.resolve("out"));

    JobWorkspace ws = JobWorkspace.open(root);

    assertEquals(root.toRealPath().resolve("in").resolve("document.pdf"), ws.inputFile());
    assertEquals(root.toRealPath().resolve("out"), ws.outputDirectory());
    assertEquals(root.toRealPath().resolve("out").resolve("remediated.pdf"), ws.outputFile("remediated.pdf"));
    assertEquals("/work/out/remediated.pdf", ws.canonicalOutputPath("remediated.pdf"));
    assertEquals("/work/in/document.pdf", JobWorkspace.CANONICAL_INPUT);
  }

  @Test
  void createsOutputDirectoryWhenMissing(@TempDir Path root) throws IOException {
    Files.createDirectories(root.resolve("in"));

    JobWorkspace ws = JobWorkspace.open(root);

    assertTrue(Files.isDirectory(ws.outputDirectory()));
  }

  @Test
  void refusesMissingRoot(@TempDir Path root) {
    assertThrows(IOException.class, () -> JobWorkspace.open(root.resolve("nope")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../escape.pdf", "sub/dir.pdf", "..", ".", "", "bad name.pdf", "x\\y.pdf", "a/../../b"})
  void refusesOutputNamesThatAreNotPlainFileNames(String name, @TempDir Path root) throws IOException {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace ws = JobWorkspace.open(root);

    assertThrows(IllegalArgumentException.class, () -> ws.outputFile(name));
    assertThrows(IllegalArgumentException.class, () -> ws.canonicalOutputPath(name));
  }

  @Test
  void scratchDirectoryLivesInsideTheRootAndOutsideOut(@TempDir Path root) throws IOException {
    Files.createDirectories(root.resolve("in"));
    JobWorkspace ws = JobWorkspace.open(root);

    Path scratch = ws.scratchDirectory();

    assertTrue(scratch.startsWith(root.toRealPath()));
    assertTrue(!scratch.startsWith(ws.outputDirectory()));
    assertTrue(Files.isDirectory(scratch));
  }
}
