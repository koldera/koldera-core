package com.koldera.pdf.integrity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Byte-level check of a file written by {@link CopyWriter}: PDFBox's parser silently repairs wrong cross-reference offsets
 * and stream {@code /Length}, so both are verified here. Every in-use entry must point exactly at {@code <num> <gen> obj}
 * and every stream must be exactly {@code /Length} bytes (an end-of-line before {@code endstream} allowed).
 */
final class SavedFileSyntax {

  private SavedFileSyntax() {}

  /** {@code null} when the file passes; otherwise a content-free reason. */
  static String check(Path file, Map<Long, Long> streamLengths) throws IOException {
    byte[] b = Files.readAllBytes(file);
    if (!startsWith(b, 0, "%PDF-")) {
      return "no PDF header";
    }
    int startxref = lastIndexOf(b, "startxref");
    if (startxref < 0) {
      return "no startxref";
    }
    int[] pos = {startxref + "startxref".length()};
    long xrefOffset = readNumber(b, pos);
    if (xrefOffset < 0 || xrefOffset >= b.length) {
      return "startxref does not point into the file";
    }
    if (!startsWith(b, (int) xrefOffset, "xref")) {
      // A cross-reference stream: not a CopyWriter file; its offsets are left to PDFBox's parser.
      return null;
    }
    // object number -> offset of every in-use entry
    Map<Long, Long> offsets = new TreeMap<>();
    pos[0] = (int) xrefOffset + 4;
    while (true) {
      skipWhitespace(b, pos);
      if (startsWith(b, pos[0], "trailer")) {
        break;
      }
      long first = readNumber(b, pos);
      long count = readNumber(b, pos);
      if (first < 0 || count < 0) {
        return "malformed cross-reference subsection";
      }
      for (long i = 0; i < count; i++) {
        long offset = readNumber(b, pos);
        long generation = readNumber(b, pos);
        skipWhitespace(b, pos);
        if (offset < 0 || generation < 0 || pos[0] >= b.length) {
          return "malformed cross-reference entry";
        }
        byte kind = b[pos[0]++];
        if (kind == 'n') {
          offsets.put(first + i, offset);
          String expected = (first + i) + " " + generation + " obj";
          if (offset >= b.length || !startsWith(b, (int) offset, expected)) {
            return "a cross-reference entry does not point at its object";
          }
        } else if (kind != 'f') {
          return "malformed cross-reference entry";
        }
      }
    }
    List<Long> sorted = new ArrayList<>(offsets.values());
    sorted.add(xrefOffset);
    sorted.sort(Long::compare);
    for (Map.Entry<Long, Long> e : streamLengths.entrySet()) {
      Long start = offsets.get(e.getKey());
      if (start == null) {
        return "a stream object is missing from the cross-reference table";
      }
      int end = (int) (long) sorted.get(sorted.indexOf(start) + 1);
      String problem = streamLength(b, start.intValue(), end, e.getValue());
      if (problem != null) {
        return problem;
      }
    }
    return null;
  }

  /** The object spans {@code [start, end)}; its data starts after {@code stream} + EOL and ends before {@code endstream}. */
  private static String streamLength(byte[] b, int start, int end, long length) {
    int endstream = lastIndexOf(b, start, end, "endstream");
    if (endstream < 0) {
      return "a stream object has no stream data";
    }
    // the first "stream" keyword followed by an end-of-line marker
    int keyword = indexOf(b, start, endstream, "stream");
    while (keyword >= 0 && !(b[keyword + 6] == '\n' || (b[keyword + 6] == '\r' && b[keyword + 7] == '\n'))) {
      keyword = indexOf(b, keyword + 1, endstream, "stream");
    }
    if (keyword < 0) {
      return "a stream object has no stream data";
    }
    long declared = declaredLength(b, start, keyword, length);
    int dataStart = keyword + 6 + (b[keyword + 6] == '\r' ? 2 : 1);
    long actual = endstream - dataStart;
    if (!(actual == declared || actual == declared + 1 || actual == declared + 2)) {
      return "a stream's /Length does not match its data";
    }
    return null;
  }

  /**
   * The {@code /Length} written in the stream dictionary: PDFBox replaces a wrong value with the
   * length it found, so the bytes are read. An indirect length falls back to {@code resolved}.
   */
  private static long declaredLength(byte[] b, int from, int to, long resolved) {
    int at = indexOf(b, from, to, "/Length");
    while (at >= 0 && at + 7 < to && !isWhitespace(b[at + 7])) {
      at = indexOf(b, at + 1, to, "/Length");
    }
    if (at < 0) {
      return resolved;
    }
    int[] pos = {at + 7};
    long first = readNumber(b, pos);
    long second = readNumber(b, pos);
    skipWhitespace(b, pos);
    if (second >= 0 && pos[0] < b.length && b[pos[0]] == 'R') {
      return resolved;
    }
    return first;
  }

  private static boolean isWhitespace(byte c) {
    return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0;
  }

  private static boolean startsWith(byte[] b, int at, String s) {
    if (at < 0 || at + s.length() > b.length) {
      return false;
    }
    byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
    for (int i = 0; i < bytes.length; i++) {
      if (b[at + i] != bytes[i]) {
        return false;
      }
    }
    return true;
  }

  private static int indexOf(byte[] b, int from, int to, String s) {
    for (int i = Math.max(0, from); i + s.length() <= to; i++) {
      if (startsWith(b, i, s)) {
        return i;
      }
    }
    return -1;
  }

  private static int lastIndexOf(byte[] b, String s) {
    return lastIndexOf(b, 0, b.length, s);
  }

  private static int lastIndexOf(byte[] b, int from, int to, String s) {
    for (int i = to - s.length(); i >= from; i--) {
      if (startsWith(b, i, s)) {
        return i;
      }
    }
    return -1;
  }

  private static void skipWhitespace(byte[] b, int[] pos) {
    while (pos[0] < b.length && (b[pos[0]] == ' ' || b[pos[0]] == '\n' || b[pos[0]] == '\r' || b[pos[0]] == '\t' || b[pos[0]] == '\f' || b[pos[0]] == 0)) {
      pos[0]++;
    }
  }

  /** A non-negative decimal integer after optional whitespace; -1 when there is none. */
  private static long readNumber(byte[] b, int[] pos) {
    skipWhitespace(b, pos);
    long value = 0;
    int digits = 0;
    while (pos[0] < b.length && b[pos[0]] >= '0' && b[pos[0]] <= '9' && digits < 18) {
      value = value * 10 + (b[pos[0]++] - '0');
      digits++;
    }
    return digits == 0 ? -1 : value;
  }
}
