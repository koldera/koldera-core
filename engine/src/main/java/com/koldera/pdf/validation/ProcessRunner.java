package com.koldera.pdf.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Runs an external command with stdout redirected to a file; abstracted so tests need no process. */
public interface ProcessRunner {

  /**
   * @return the process exit code
   * @throws IOException when the process cannot be started (e.g. binary missing)
   * @throws TimeoutException when the process exceeded {@code timeout} and was killed
   */
  int run(List<String> command, Path stdoutFile, Duration timeout) throws IOException, TimeoutException;
}
