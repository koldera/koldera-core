package com.koldera.pdf.structure;

import com.koldera.pdf.cli.EngineVersion;
import com.koldera.pdf.contracts.ContractSchemas;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CLI envelope of the structure snapshot: {@code structure --input PATH --result PATH [--max-bytes N]}.
 * Same exit-code contract as {@code process}; logs carry neither paths nor document text.
 */
public final class StructureCommand {

  private static final long DEFAULT_MAX_BYTES = 104_857_600L;

  private StructureCommand() {}

  public static int run(String[] args, PrintStream log) {
    Path input;
    Path result;
    long maxBytes;
    try {
      Map<String, String> values = new HashMap<>();
      for (int i = 1; i < args.length; i += 2) {
        String key = args[i];
        if (!key.startsWith("--") || i + 1 >= args.length) {
          throw new IllegalArgumentException("malformed option " + key);
        }
        if (values.put(key.substring(2), args[i + 1]) != null) {
          throw new IllegalArgumentException("duplicate option " + key);
        }
      }
      String in = values.remove("input");
      String out = values.remove("result");
      String max = values.remove("max-bytes");
      if (in == null || out == null) {
        throw new IllegalArgumentException("--input and --result are required");
      }
      if (!values.isEmpty()) {
        throw new IllegalArgumentException("unknown option --" + values.keySet().iterator().next());
      }
      input = Path.of(in);
      result = Path.of(out);
      maxBytes = max == null ? DEFAULT_MAX_BYTES : Long.parseLong(max);
      if (maxBytes <= 0) {
        throw new IllegalArgumentException("--max-bytes must be positive");
      }
    } catch (IllegalArgumentException e) {
      log.println("usage: structure --input PATH --result PATH [--max-bytes N]");
      log.println("error: " + e.getMessage());
      return 2;
    }
    StructureResult outcome;
    try {
      outcome = new StructureExtractor().extract(input, maxBytes);
    } catch (RuntimeException e) {
      outcome = StructureResult.failed(null, 0, EngineVersion.describe(), StructureResult.ErrorCode.WORKER_ERROR, "unexpected extractor failure (" + e.getClass().getSimpleName() + ")");
    }
    try {
      String json = StructureResultCodec.toJson(outcome);
      Set<String> violations = ContractSchemas.validateStructureResult(json);
      if (!violations.isEmpty()) {
        throw new IOException("structure result violates contract schema: " + String.join("; ", violations));
      }
      writeAtomically(json, result);
    } catch (IOException | RuntimeException e) {
      log.println("{\"event\":\"structure_not_persisted\",\"reason\":\"" + e.getClass().getSimpleName() + "\"}");
      return 1;
    }
    log.println("{\"event\":\"structure_written\",\"status\":\"" + outcome.status().name().toLowerCase() + "\",\"nodes\":" + outcome.nodes().size() + "}");
    return 0;
  }

  public static void writeAtomically(String json, Path target) throws IOException {
    Path absolute = target.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    Path temp = Files.createTempFile(absolute.getParent(), ".result-", ".tmp");
    try {
      Files.writeString(temp, json, StandardCharsets.UTF_8);
      try {
        Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
