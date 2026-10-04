package com.koldera.pdf.order;

import com.koldera.pdf.cli.EngineVersion;
import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.structure.StructureCommand;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CLI envelope of the approved sibling reorder: {@code reorder --request PATH --result PATH
 * [--work-root DIR]}. The request is validated against the contract schema before anything is
 * touched; the mutated copy is written to {@code <work-root>/out/reordered.pdf} only when every
 * invariant held. Exit codes as for {@code process}.
 */
public final class ReorderCommand {

  private static final UUID NIL = new UUID(0L, 0L);

  private ReorderCommand() {}

  public static int run(String[] args, PrintStream log) {
    Path requestPath;
    Path resultPath;
    Path workRoot;
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
      String request = values.remove("request");
      String result = values.remove("result");
      String root = values.getOrDefault("work-root", "/work");
      values.remove("work-root");
      if (request == null || result == null) {
        throw new IllegalArgumentException("--request and --result are required");
      }
      if (!values.isEmpty()) {
        throw new IllegalArgumentException("unknown option --" + values.keySet().iterator().next());
      }
      requestPath = Path.of(request);
      resultPath = Path.of(result);
      workRoot = Path.of(root);
    } catch (IllegalArgumentException e) {
      log.println("usage: reorder --request PATH --result PATH [--work-root DIR]");
      log.println("error: " + e.getMessage());
      return 2;
    }

    ReorderResult outcome;
    UUID jobId = NIL;
    try {
      String json = Files.readString(requestPath, StandardCharsets.UTF_8);
      Set<String> violations = ContractSchemas.validateReorderRequest(json);
      if (!violations.isEmpty()) {
        outcome = ReorderResult.failed(jobId, EngineVersion.describe(), null, ReorderResult.ErrorCode.INVALID_INPUT, "request violates schema: " + String.join("; ", violations), null);
        log.println("{\"event\":\"request_invalid\",\"count\":" + violations.size() + "}");
      } else {
        ReorderRequest request = ReorderResultCodec.requestFromJson(json);
        jobId = request.jobId();
        outcome = new SiblingReorder().apply(request, workRoot);
      }
    } catch (IOException e) {
      outcome = ReorderResult.failed(jobId, EngineVersion.describe(), null, ReorderResult.ErrorCode.INVALID_INPUT, "request file missing or unreadable", null);
    } catch (RuntimeException e) {
      log.println("{\"event\":\"worker_error\",\"exception\":\"" + e.getClass().getName() + "\"}");
      outcome = ReorderResult.failed(jobId, EngineVersion.describe(), null, ReorderResult.ErrorCode.WORKER_ERROR, "unexpected worker failure (" + e.getClass().getSimpleName() + ")", null);
    }
    try {
      String json = ReorderResultCodec.toJson(outcome);
      Set<String> violations = ContractSchemas.validateReorderResult(json);
      if (!violations.isEmpty()) {
        throw new IOException("reorder result violates contract schema: " + String.join("; ", violations));
      }
      StructureCommand.writeAtomically(json, resultPath);
    } catch (IOException | RuntimeException e) {
      log.println("{\"event\":\"result_not_persisted\",\"reason\":\"" + e.getClass().getSimpleName() + "\"}");
      return 1;
    }
    log.println("{\"event\":\"result_written\",\"status\":\"" + outcome.status().name().toLowerCase() + "\",\"jobId\":\"" + outcome.jobId() + "\"}");
    return 0;
  }
}
