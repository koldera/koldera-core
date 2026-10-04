package com.koldera.pdf.report;

import com.koldera.pdf.contracts.ContractSchemas;
import com.koldera.pdf.contracts.WorkerResult;
import com.koldera.pdf.contracts.WorkerResultCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * Writes the result JSON atomically: serialize, validate against the result schema, write to a
 * sibling temporary file, then move into place. A half-written or contract-violating result file
 * is never observable by the orchestrator.
 */
public final class ReportWriter {

  public void write(WorkerResult result, Path target) throws IOException {
    String json = WorkerResultCodec.toJson(result);
    Set<String> violations = ContractSchemas.validateResult(json);
    if (!violations.isEmpty()) {
      throw new IOException("result violates contract schema: " + String.join("; ", violations));
    }
    Path absolute = target.toAbsolutePath();
    Path parent = absolute.getParent();
    Files.createDirectories(parent);
    Path temp = Files.createTempFile(parent, ".result-", ".tmp");
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
