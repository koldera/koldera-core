package com.koldera.pdf.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ProcessBuilder} without a shell: the argument array is passed verbatim, stdout goes to
 * the report file, stderr is discarded (it may echo host paths), stdin is closed immediately.
 */
public final class DefaultProcessRunner implements ProcessRunner {

  @Override
  public int run(List<String> command, Path stdoutFile, Duration timeout)
      throws IOException, TimeoutException {
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectOutput(stdoutFile.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process = builder.start();
    try {
      process.getOutputStream().close(); // nothing is ever fed on stdin
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        throw new TimeoutException("validator exceeded " + timeout.toSeconds() + "s");
      }
      return process.exitValue();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting for validator", e);
    }
  }
}
