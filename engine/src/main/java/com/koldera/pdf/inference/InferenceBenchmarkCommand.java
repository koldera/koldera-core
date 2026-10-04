package com.koldera.pdf.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.koldera.pdf.structure.ContentTagger;
import com.koldera.pdf.structure.ContentTagger.UnsupportedContentException;
import com.koldera.pdf.structure.StructureCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import com.koldera.pdf.generated.StructTree;

/**
 * {@code infer-benchmark --input PATH --output PATH}: compares the structure inferred from layout on a copy with its tags
 * stripped against the producer's own headings, list items and tables (truth), matched by normalized text; reports
 * precision and recall. Exit {@code 0} JSON written; {@code 1} untagged input, refused inference or I/O failure; {@code 2}
 * usage error. The log carries no document text.
 */
public final class InferenceBenchmarkCommand {

  /** A Word heading style name; Word maps it to {@code P} in the /RoleMap, yet it is the author's heading mark. */
  private static final java.util.regex.Pattern WORD_HEADING_STYLE = java.util.regex.Pattern.compile("(?i)heading ?[1-9]");
  private static final Set<String> HEADINGS = Set.of("H", "H1", "H2", "H3", "H4", "H5", "H6");
  private static final Set<String> STANDARD = Set.of(
      "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index", "NonStruct", "Private", "P", "H", "H1", "H2",
      "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Span", "Quote", "Note",
      "Reference", "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP", "Figure", "Formula", "Form");

  private InferenceBenchmarkCommand() {}

  public static int run(String[] args, PrintStream log) {
    Path input;
    Path output;
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
      String out = values.remove("output");
      if (in == null || out == null) {
        throw new IllegalArgumentException("--input and --output are required");
      }
      if (!values.isEmpty()) {
        throw new IllegalArgumentException("unknown option --" + values.keySet().iterator().next());
      }
      input = Path.of(in);
      output = Path.of(out);
    } catch (IllegalArgumentException e) {
      log.println("usage: infer-benchmark --input PATH --output PATH");
      log.println("error: " + e.getMessage());
      return 2;
    }
    Map<String, Object> result;
    try {
      byte[] bytes = Files.readAllBytes(input);
      result = benchmark(bytes);
    } catch (Refused e) {
      log.println(logLine("benchmark_refused", e.getMessage()));
      return 1;
    } catch (IOException | RuntimeException e) {
      log.println(logLine("benchmark_failed", e.getClass().getSimpleName()));
      return 1;
    }
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("file", input.getFileName().toString());
    json.putAll(result);
    try {
      StructureCommand.writeAtomically(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(json), output);
    } catch (IOException | RuntimeException e) {
      log.println(logLine("benchmark_not_persisted", e.getClass().getSimpleName()));
      return 1;
    }
    log.println("{\"event\":\"benchmark_written\"}");
    return 0;
  }

  /** A document the benchmark cannot measure; the message is content-free. */
  static final class Refused extends Exception {
    Refused(String reason) {
      super(reason);
    }
  }

  static Map<String, Object> benchmark(byte[] bytes) throws IOException, Refused {
    Map<String, List<String>> truth;
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
      if (root == null) {
        throw new Refused("the input is untagged");
      }
      truth = elements(doc, root);
    }
    byte[] inferredBytes;
    LayoutGrouping grouping;
    try (PDDocument copy = Loader.loadPDF(bytes)) {
      TagStripper.strip(copy);
      grouping = new LayoutGrouping(InferenceSettings.DEFAULTS, StructureInferenceRule.rightToLeft(copy));
      ContentTagger.apply(copy, ContentTagger.analyze(copy, true, grouping));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      copy.save(out);
      inferredBytes = out.toByteArray();
    } catch (UnsupportedContentException e) {
      throw new Refused("the inference refused the document: " + e.getMessage());
    }
    Map<String, List<String>> inferred;
    try (PDDocument doc = Loader.loadPDF(inferredBytes)) {
      inferred = elements(doc, doc.getDocumentCatalog().getStructureTreeRoot());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("headings", compare(truth.get("headings"), inferred.get("headings"), truth.get("headingsBlank")));
    out.put("listItems", compare(truth.get("listItems"), inferred.get("listItems"), truth.get("listItemsBlank")));
    out.put("tables", compare(truth.get("tables"), inferred.get("tables"), truth.get("tablesBlank")));
    LayoutGrouping.Counts c = grouping.counts();
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("paragraphs", c.paragraphs());
    counts.put("headings", c.headings());
    counts.put("lists", c.lists());
    counts.put("listItems", c.listItems());
    counts.put("tables", c.tables());
    counts.put("runningArtifacts", c.runningArtifacts());
    counts.put("pagesReordered", c.pagesReordered());
    counts.put("pagesKept", c.pagesKept());
    counts.put("fallbacks", c.fallbacks());
    out.put("inference", counts);
    return out;
  }

  private static Map<String, Object> compare(List<String> truth, List<String> inferred, List<String> blank) {
    // Unused truth texts in tree order; each inferred element takes the first one it matches.
    List<String> candidates = new java.util.ArrayList<>(truth);
    int matched = 0;
    for (String i : inferred) {
      for (java.util.Iterator<String> it = candidates.iterator(); it.hasNext(); ) {
        if (matches(it.next(), i)) {
          it.remove();
          matched++;
          break;
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("truth", truth.size());
    out.put("inferred", inferred.size());
    out.put("matched", matched);
    out.put("precision", inferred.isEmpty() ? 1.0 : (double) matched / inferred.size());
    out.put("recall", truth.isEmpty() ? 1.0 : (double) matched / truth.size());
    out.put("blank", blank.size());
    return out;
  }

  /** One JSON log line {@code {"event": ..., "reason": ...}}, escaped by the JSON writer. */
  static String logLine(String event, String reason) {
    Map<String, String> line = new LinkedHashMap<>();
    line.put("event", event);
    line.put("reason", reason);
    try {
      return new ObjectMapper().writeValueAsString(line);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{\"event\":\"" + event + "\"}";
    }
  }

  static final int KEY_PREFIX = 80;
  static final double MIN_LENGTH_RATIO = 0.8;
  static final double MAX_LENGTH_RATIO = 1.25;

  /**
   * Whether an inferred text matches a truth text: their keys agree on the first {@value #KEY_PREFIX}
   * characters (or the whole shorter key)
 and the whole keys' lengths are within {@value #MIN_LENGTH_RATIO}–{@value #MAX_LENGTH_RATIO} of
   * each other (a heading is not matched by a paragraph that starts with it; a trailing footnote mark is tolerated).
   */
  static boolean matches(String truth, String inferred) {
    String t = key(truth);
    String i = key(inferred);
    int common = Math.min(KEY_PREFIX, Math.min(t.codePointCount(0, t.length()), i.codePointCount(0, i.length())));
    if (t.isEmpty() || !prefix(t, common).equals(prefix(i, common))) {
      return false;
    }
    double ratio = (double) i.codePointCount(0, i.length()) / t.codePointCount(0, t.length());
    return ratio >= MIN_LENGTH_RATIO && ratio <= MAX_LENGTH_RATIO;
  }

  private static String prefix(String key, int codePoints) {
    return key.substring(0, key.offsetByCodePoints(0, codePoints));
  }

  /** NFKC, lower case, white space removed. */

  static String key(String text) {
    String k = Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    return k;
  }

  /** Texts of headings, list items and tables in tree order (blank ones listed separately). */
  private static Map<String, List<String>> elements(PDDocument doc, PDStructureTreeRoot root) throws IOException {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String name : List.of("headings", "listItems", "tables")) {
      out.put(name, new ArrayList<>());
      out.put(name + "Blank", new ArrayList<>());
    }
    COSDictionary roleMap = root.getCOSObject().getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary d ? d : new COSDictionary();
    StructureText reader = new StructureText(doc);
    walk(root.getCOSObject(), roleMap, reader, out, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    return out;
  }

  private static void walk(COSDictionary node, COSDictionary roleMap, StructureText reader, Map<String, List<String>> out, int depth,
      Set<COSDictionary> visited) throws IOException {
    if (depth > 256 || !visited.add(node)) {
      return;
    }
    for (COSBase entry : StructTree.entries(node)) {
      if (!(StructTree.resolve(entry) instanceof COSDictionary kid) || !kid.containsKey(COSName.S)) {
        continue;
      }
      String own = kid.getNameAsString(COSName.S);
      String type = WORD_HEADING_STYLE.matcher(own).matches() ? "H" : standardType(own, roleMap);
      String name = HEADINGS.contains(type) ? "headings" : "LI".equals(type) ? "listItems" : "Table".equals(type) ? "tables" : null;
      if (name != null) {
        String text = "tables".equals(name) ? cellText(kid, roleMap, reader) : reader.text(kid);
        out.get(text.isBlank() ? name + "Blank" : name).add(text);
      }
      walk(kid, roleMap, reader, out, depth + 1, visited);
    }
  }

  /** The text of a table's kids other than a {@code Caption}, joined by one space. */
  private static String cellText(COSDictionary table, COSDictionary roleMap, StructureText reader) throws IOException {
    List<String> parts = new ArrayList<>();
    for (COSBase entry : StructTree.entries(table)) {
      if (StructTree.resolve(entry) instanceof COSDictionary kid && kid.containsKey(COSName.S)
          && !"Caption".equals(standardType(kid.getNameAsString(COSName.S), roleMap))) {
        String text = reader.text(kid);
        if (!text.isBlank()) {
          parts.add(text);
        }
      }
    }
    return PageLayout.normalize(String.join(" ", parts));
  }

  /** The type after following the /RoleMap to a standard type (at most 16 steps); the type itself when none is reached. */
  static String standardType(String type, COSDictionary roleMap) {
    String current = type;
    for (int i = 0; i < 16 && current != null && !STANDARD.contains(current); i++) {
      current = roleMap.getNameAsString(COSName.getPDFName(current));
    }
    return current == null ? type : current;
  }
}
