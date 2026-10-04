package com.koldera.pdf.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Engine and library versions as recorded in the result JSON, filtered in by Maven at build time. */
public final class EngineVersion {

  private static final String DESCRIPTION = load();

  private EngineVersion() {}

  public static String describe() {
    return DESCRIPTION;
  }

  private static String load() {
    Properties props = new Properties();
    try (InputStream in = EngineVersion.class.getResourceAsStream("/engine-version.properties")) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException ignored) {
      // fall through to unknown
    }
    return "koldera-pdf-engine "
        + props.getProperty("engine.version", "unknown")
        + "; pdfbox "
        + props.getProperty("pdfbox.version", "unknown");
  }
}
