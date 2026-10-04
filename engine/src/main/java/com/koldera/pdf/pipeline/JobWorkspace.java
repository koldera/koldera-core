package com.koldera.pdf.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The per-job filesystem the worker may touch: maps the contract's canonical container paths onto the mounted root and
 * refuses anything but a plain file name in {@code out}. Layout: {@code in/document.pdf} (read-only input), {@code out}
 * (published artifacts), {@code scratch} (private working copies).
 */
public final class JobWorkspace {

  public static final String CANONICAL_INPUT = "/work/in/document.pdf";
  public static final String CANONICAL_OUTPUT_DIRECTORY = "/work/out";

  /** Matches the artifact path segment allowed by the result schema. */
  private static final Pattern PLAIN_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

  private final Path root;
  private final Path inputFile;
  private final Path outputDirectory;
  private final Path scratchDirectory;

  private JobWorkspace(Path root) {
    this.root = root;
    this.inputFile = root.resolve("in").resolve("document.pdf");
    this.outputDirectory = root.resolve("out");
    this.scratchDirectory = root.resolve("scratch");
  }

  /** Opens an existing job root, creating {@code out} and {@code scratch} if absent. */
  public static JobWorkspace open(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      throw new IOException("job root is not a directory");
    }
    JobWorkspace ws = new JobWorkspace(root.toRealPath());
    Files.createDirectories(ws.outputDirectory);
    Files.createDirectories(ws.scratchDirectory);
    return ws;
  }

  public Path root() {
    return root;
  }

  public Path inputFile() {
    return inputFile;
  }

  public Path outputDirectory() {
    return outputDirectory;
  }

  public Path scratchDirectory() {
    return scratchDirectory;
  }

  /** Real path of a to-be-published artifact; {@code name} must be a plain file name. */
  public Path outputFile(String name) {
    return outputDirectory.resolve(requirePlainName(name));
  }

  /** Canonical container path of a published artifact, as reported in the result JSON. */
  public String canonicalOutputPath(String name) {
    return CANONICAL_OUTPUT_DIRECTORY + "/" + requirePlainName(name);
  }

  private static String requirePlainName(String name) {
    if (name == null || !PLAIN_FILE_NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
      throw new IllegalArgumentException("artifact name must be a plain file name");
    }
    return name;
  }
}
