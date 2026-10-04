package com.koldera.pdf.generated;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Chromium paints some content outside any marked content (running headers, generated content, aria-hidden content, form
 * controls). This step wraps painting (images, shadings, paths) and, in {@link Scope#GENERATED}, runs of text-showing
 * operators, in {@code /Artifact BMC ... EMC}; {@link Scope#PAINTING_ONLY} never wraps text. Markers go only at operator
 * boundaries; unbalanced streams are left alone; a form is rewritten only when every draw is outside tagged content.
 */
public final class UntaggedArtifacts {

  private static final int MAX_FORM_DEPTH = 16;
  private static final int SAMPLE_LIMIT = 80;
  private static final Set<String> PATH_CONSTRUCTION = Set.of("m", "l", "c", "v", "y", "h", "re", "W", "W*");
  private static final Set<String> PATH_PAINTING = Set.of("S", "s", "f", "F", "f*", "B", "B*", "b", "b*");
  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");
  private static final int BROKEN = -2;

  private UntaggedArtifacts() {}

  /**
   * Counts: painting and text runs marked as artifacts, a text sample (at most 80 characters, null when undecodable) and
   * {@code textLeft}, untagged text runs left unwrapped (always 0 in scope {@link Scope#GENERATED}).
   */
  public record Outcome(int painting, int text, String textSample, int textLeft) {
    public Outcome(int painting, int text, String textSample) {
      this(painting, text, textSample, 0);
    }
  }

  /**
   * What is wrapped: {@link #GENERATED} wraps untagged painting and untagged text (HTML-generated
   * PDFs); {@link #PAINTING_ONLY} wraps exactly the same painting but never text, which it only
   * counts ({@link Outcome#textLeft}): in an upload, text outside the tags is real content, never
   * hidden on a guess (tagged uploads, {@code PDF_UNTAGGED_CONTENT_V1}).
   */
  public enum Scope {
    GENERATED,
    PAINTING_ONLY
  }

  /** Scope {@link Scope#GENERATED}: the generated-PDF step, unchanged. */
  public static Outcome apply(PDDocument doc) throws IOException {
    return apply(doc, Scope.GENERATED);
  }

  public static Outcome apply(PDDocument doc, Scope scope) throws IOException {
    Map<COSStream, FormUse> forms = new IdentityHashMap<>();
    Map<PDPage, Scan> pages = new LinkedHashMap<>();
    for (PDPage page : doc.getPages()) {
      if (!page.hasContents()) {
        continue;
      }
      List<Object> tokens = new PDFStreamParser(page).parse();
      pages.put(page, scan(tokens, page.getResources(), false, forms, new HashSet<>(), 0, scope));
    }
    Tally tally = new Tally();
    for (Map.Entry<PDPage, Scan> e : pages.entrySet()) {
      Scan scan = e.getValue();
      tally.textLeft += scan.textLeft;
      if (scan.ranges.isEmpty()) {
        continue;
      }
      PDStream replacement = new PDStream(doc);
      try (OutputStream out = replacement.createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(out).writeTokens(rewrite(scan));
      }
      e.getKey().setContents(replacement);
      tally.add(scan);
    }
    // Identity lookup (two equal-looking streams are still two forms), but draw order for the output.
    List<FormUse> inDrawOrder = new ArrayList<>(forms.values());
    inDrawOrder.sort(Comparator.comparingInt(u -> u.order));
    for (FormUse use : inDrawOrder) {
      if (use.drawnInsideMarkedContent || use.scan == null) {
        continue;
      }
      tally.textLeft += use.scan.textLeft;
      if (use.scan.ranges.isEmpty()) {
        continue;
      }
      PDFormXObject form = use.form;
      List<Object> rewritten = rewrite(use.scan);
      form.getCOSObject().removeItem(COSName.DECODE_PARMS);
      form.getCOSObject().removeItem(COSName.DP);
      form.getCOSObject().removeItem(COSName.FILTER);
      try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
        new ContentStreamWriter(out).writeTokens(rewritten);
      }
      tally.add(use.scan);
    }
    return new Outcome(tally.painting, tally.text, tally.sample, tally.textLeft);
  }

  /** Control and format characters dropped, whitespace collapsed and trimmed, cut to 80 characters with {@code …}; null for null or blank text. */
  static String sample(String shown) {
    if (shown == null) {
      return null;
    }
    // Control (Cc) and format (Cf) code points are dropped: U+0000 cannot be stored in a PostgreSQL text
    // column and none of them is visible; the whitespace controls stay for the collapse below.
    String visible = shown.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\t\\n\\r\\f\\u000B]]", "");
    String collapsed = visible.replaceAll("\\s+", " ").trim();
    if (collapsed.isEmpty()) {
      return null;
    }
    if (collapsed.length() <= SAMPLE_LIMIT) {
      return collapsed;
    }
    int cut = SAMPLE_LIMIT - 1;
    if (Character.isHighSurrogate(collapsed.charAt(cut - 1))) {
      cut--; // never keep half a surrogate pair
    }
    return collapsed.substring(0, cut) + "…";
  }

  private static final class Tally {
    int painting;
    int text;
    int textLeft;
    String sample;

    void add(Scan scan) {
      for (Range range : scan.ranges) {
        if (range.text) {
          text++;
          if (sample == null) {
            sample = sample(range.shown);
          }
        } else {
          painting++;
        }
      }
    }
  }

  private static final class FormUse {
    final PDFormXObject form;
    /** First-draw position: forms are rewritten and tallied in draw order, so the finding's sample is stable. */
    final int order;
    boolean drawnInsideMarkedContent;
    boolean scannedInside;
    Scan scan;

    FormUse(PDFormXObject form, int order) {
      this.form = form;
      this.order = order;
    }
  }

  /** Tokens {@code [start, end]} (inclusive) to wrap; {@code shown} is the decoded text of a text run, null if not decodable. */
  private record Range(int start, int end, boolean text, String shown) {}

  /** Ranges to wrap, in order and disjoint, over the parsed tokens of one stream; {@code textLeft}: text ranges not wrapped ({@link Scope#PAINTING_ONLY}). */
  private record Scan(List<Object> tokens, List<Range> ranges, int textLeft) {}

  /** An MCID-less, non-artifact sequence opened in untagged content; wrapped whole if it paints and holds no tagged content. */
  private static final class Unit {
    final int start;
    final int depth;
    boolean tagged;
    boolean painted;
    boolean text;
    boolean decodable = true;
    final StringBuilder shown = new StringBuilder();

    Unit(int start, int depth) {
      this.start = start;
      this.depth = depth;
    }
  }

  /** An open run of untagged text-showing operators inside one text object. */
  private static final class TextRun {
    int start = -1;
    int end;
    final StringBuilder shown = new StringBuilder();
    boolean decodable = true;

    void flushInto(List<Range> ranges) {
      if (start != -1) {
        ranges.add(new Range(start, end, true, decodable ? shown.toString() : null));
      }
      start = -1;
      shown.setLength(0);
      decodable = true;
    }
  }

  /**
   * Finds the ranges to wrap in one stream and records every form drawn from it, with whether that
   * draw happens inside marked content ({@code inherited} or an open sequence at the {@code Do}).
   */
  private static Scan scan(
      List<Object> tokens, PDResources resources, boolean inherited, Map<COSStream, FormUse> forms, Set<COSStream> path, int formDepth, Scope scope)
      throws IOException {
    List<Range> ranges = new ArrayList<>();
    int depth = 0;
    List<Boolean> covering = new ArrayList<>(); // per open sequence: does it make its content tagged or artifact?
    int covered = 0; // open sequences that do
    boolean balanced = true;
    int operandStart = 0;
    int pathStart = -1;
    boolean inText = false;
    TextRun run = new TextRun();
    Unit unit = null; // an open sequence without an MCID that sits in untagged content
    COSName font = null;
    List<COSName> savedFonts = new ArrayList<>(); // the font is graphics state: q saves it, Q restores it (null = none set)
    List<COSBase> operands = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      Object token = tokens.get(i);
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      String name = op.getName();
      boolean outside = covered == 0;
      switch (name) {
        case "BMC", "BDC" -> {
          run.flushInto(ranges);
          boolean covers = covers(name, operands, resources);
          if (unit == null && covered == 0 && !covers) {
            unit = new Unit(operandStart, depth);
          } else if (unit != null && covers && !isArtifact(operands)) {
            unit.tagged = true; // tagged content inside: the sequence is never wrapped
          }
          depth++;
          covering.add(covers);
          covered += covers ? 1 : 0;
        }
        case "EMC" -> {
          run.flushInto(ranges);
          depth--;
          if (depth < 0) {
            balanced = false;
            depth = 0;
          } else if (covering.remove(covering.size() - 1)) {
            covered--;
          }
          if (unit != null && depth == unit.depth) {
            if (!unit.tagged && unit.painted) {
              ranges.add(new Range(unit.start, i, unit.text, unit.decodable ? unit.shown.toString() : null));
            }
            unit = null;
          }
        }
        case "BT" -> inText = true;
        case "ET" -> {
          run.flushInto(ranges);
          inText = false;
        }
        case "q" -> savedFonts.add(font);
        case "Q" -> font = savedFonts.isEmpty() ? font : savedFonts.remove(savedFonts.size() - 1);
        case "Tf" -> font = !operands.isEmpty() && operands.get(0) instanceof COSName fontName ? fontName : null;
        default -> { }
      }
      if (unit != null && outside && (TEXT_SHOWING.contains(name) || PATH_PAINTING.contains(name) || "sh".equals(name) || "BI".equals(name)
          || ("Do".equals(name) && operands.size() == 1 && operands.get(0) instanceof COSName imageName && resources != null
              && resources.getXObject(imageName) instanceof PDImageXObject))) {
        // Inside an MCID-less sequence (e.g. /Span <</ActualText>>): the whole sequence is wrapped from
        // outside when it closes, so its /ActualText keeps applying to exactly the same content.
        unit.painted = true;
        if (TEXT_SHOWING.contains(name)) {
          unit.text = true;
          String shown = ShownText.decode(resources, font, operands);
          if (shown == null) {
            unit.decodable = false;
          } else {
            unit.shown.append(shown);
          }
        }
        pathStart = -1;
      } else if (TEXT_SHOWING.contains(name)) {
        if (outside && inText) {
          if (run.start == -1) {
            run.start = operandStart;
          }
          run.end = i;
          String shown = ShownText.decode(resources, font, operands);
          if (shown == null) {
            run.decodable = false;
          } else {
            run.shown.append(shown);
          }
        }
      } else if (PATH_CONSTRUCTION.contains(name)) {
        if (pathStart == -1) {
          pathStart = operandStart;
        }
      } else if (PATH_PAINTING.contains(name)) {
        if (outside && pathStart != BROKEN) {
          ranges.add(new Range(pathStart == -1 ? operandStart : pathStart, i, false, null));
        }
        pathStart = -1;
      } else if ("n".equals(name)) {
        pathStart = -1;
      } else {
        if (pathStart != -1) {
          // Something other than path construction interrupted a path: never insert a marker into it.
          pathStart = BROKEN;
        }
        if ("sh".equals(name) || "BI".equals(name)) {
          if (outside) {
            ranges.add(new Range(operandStart, i, false, null));
          }
        } else if ("Do".equals(name) && operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null) {
          PDXObject xObject = resources.getXObject(xName);
          if (xObject instanceof PDImageXObject) {
            if (outside) {
              ranges.add(new Range(operandStart, i, false, null));
            }
          } else if (xObject instanceof PDFormXObject form) {
            visitForm(form, resources, inherited || !outside || unit != null, forms, path, formDepth, scope);
          }
        }
      }
      operands.clear();
      operandStart = i + 1;
    }
    run.flushInto(ranges);
    if (!balanced || depth != 0 || inherited) {
      return new Scan(tokens, List.of(), 0);
    }
    int textLeft = 0;
    if (scope == Scope.PAINTING_ONLY) {
      int all = ranges.size();
      ranges.removeIf(Range::text); // a text run, or an MCID-less sequence that shows text: left as it is
      textLeft = all - ranges.size();
    }
    return new Scan(tokens, disjoint(ranges), textLeft);
  }

  /**
   * Whether a sequence makes what it encloses tagged or artifact: an {@code /Artifact} sequence, or
   * one whose properties carry an {@code /MCID}. Other sequences ({@code /Span <</ActualText …>>}
   * without an MCID, {@code /ReversedChars}) leave their content untagged. Properties given by name
   * that cannot be resolved count as covering, so nothing is wrapped on a guess.
   */
  private static boolean isArtifact(List<COSBase> operands) {
    return !operands.isEmpty() && COSName.ARTIFACT.equals(operands.get(0));
  }

  private static boolean covers(String operator, List<COSBase> operands, PDResources resources) {
    if (operands.isEmpty() || !(operands.get(0) instanceof COSName tag)) {
      return true;
    }
    if (COSName.ARTIFACT.equals(tag)) {
      return true;
    }
    if (!"BDC".equals(operator) || operands.size() < 2) {
      return false;
    }
    COSBase properties = operands.get(1);
    if (properties instanceof COSName named) {
      PDPropertyList list = resources == null ? null : resources.getProperties(named);
      return list == null || list.getCOSObject().containsKey(COSName.MCID);
    }
    return !(properties instanceof COSDictionary dict) || dict.containsKey(COSName.MCID);
  }

  /**
   * Ranges in token order; a range that overlaps an earlier one (possible only in a malformed stream,
   * such as painting inside a text object) is dropped, so markers always nest properly.
   */
  private static List<Range> disjoint(List<Range> ranges) {
    List<Range> sorted = new ArrayList<>(ranges);
    sorted.sort(Comparator.comparingInt(Range::start));
    List<Range> out = new ArrayList<>(sorted.size());
    int lastEnd = -1;
    for (Range range : sorted) {
      if (range.start > lastEnd) {
        out.add(range);
        lastEnd = range.end;
      }
    }
    return out;
  }

  private static void visitForm(
      PDFormXObject form, PDResources parent, boolean inside, Map<COSStream, FormUse> forms, Set<COSStream> path, int formDepth, Scope scope)
      throws IOException {
    COSStream key = form.getCOSObject();
    FormUse use = forms.computeIfAbsent(key, k -> new FormUse(form, forms.size()));
    use.drawnInsideMarkedContent |= inside;
    if (inside ? use.scannedInside : use.scan != null) {
      return; // already walked in this context; nested draws are recorded
    }
    if (path.contains(key) || formDepth >= MAX_FORM_DEPTH) {
      use.drawnInsideMarkedContent = true; // a cycle or runaway nesting: leave the form untouched
      return;
    }
    path.add(key);
    try {
      PDResources resources = form.getResources() != null ? form.getResources() : parent;
      Scan scan = scan(new PDFStreamParser(form).parse(), resources, inside, forms, path, formDepth + 1, scope);
      if (inside) {
        use.scannedInside = true;
      } else {
        use.scan = scan;
      }
    } finally {
      path.remove(key);
    }
  }

  private static List<Object> rewrite(Scan scan) {
    List<Object> tokens = scan.tokens();
    List<Object> out = new ArrayList<>(tokens.size() + scan.ranges().size() * 3);
    int r = 0;
    for (int i = 0; i < tokens.size(); i++) {
      if (r < scan.ranges().size() && scan.ranges().get(r).start() == i) {
        out.add(COSName.ARTIFACT);
        out.add(Operator.getOperator("BMC"));
      }
      out.add(tokens.get(i));
      if (r < scan.ranges().size() && scan.ranges().get(r).end() == i) {
        out.add(Operator.getOperator("EMC"));
        r++;
      }
    }
    return out;
  }
}
