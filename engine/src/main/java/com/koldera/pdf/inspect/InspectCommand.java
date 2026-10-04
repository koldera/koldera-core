package com.koldera.pdf.inspect;

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
 * CLI envelope of the structure probe: {@code inspect --input PATH --result PATH [--max-bytes N]}.
 * Same exit-code contract as {@code process}: 0 when a schema-valid result was written (failed
 * inspections included), 1 when it could not be persisted, 2 on usage errors. Logs carry no paths.
 */
public final class InspectCommand {

  static final int EXIT_OK = 0;
  static final int EXIT_RESULT_NOT_PERSISTED = 1;
  static final int EXIT_USAGE = 2;
  private static final long DEFAULT_MAX_BYTES = 104_857_600L;

  private InspectCommand() {}

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
      log.println("usage: inspect --input PATH --result PATH [--max-bytes N]");
      log.println("error: " + e.getMessage());
      return EXIT_USAGE;
    }

    InspectResult outcome;
    try {
      outcome = new StructureInspector().inspect(input, maxBytes);
    } catch (RuntimeException e) {
      outcome = InspectResult.failed(null, 0, EngineVersion.describe(), InspectResult.ErrorCode.WORKER_ERROR, "unexpected inspector failure (" + e.getClass().getSimpleName() + ")");
    }
    try {
      write(outcome, result);
    } catch (IOException | RuntimeException e) {
      log.println("{\"event\":\"inspect_not_persisted\",\"reason\":\"" + e.getClass().getSimpleName() + "\"}");
      return EXIT_RESULT_NOT_PERSISTED;
    }
    log.println("{\"event\":\"inspect_written\",\"status\":\"" + outcome.status().name().toLowerCase() + "\"}");
    return EXIT_OK;
  }

  private static void write(InspectResult outcome, Path target) throws IOException {
    String json = InspectResultCodec.toJson(outcome);
    Set<String> violations = ContractSchemas.validateInspectResult(json);
    if (!violations.isEmpty()) {
      throw new IOException("inspect result violates contract schema: " + String.join("; ", violations));
    }
    Path absolute = target.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    Path temp = Files.createTempFile(absolute.getParent(), ".inspect-", ".tmp");
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
