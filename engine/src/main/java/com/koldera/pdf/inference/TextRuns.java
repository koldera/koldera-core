package com.koldera.pdf.inference;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

/**
 * Text positions per text-showing operator of one page ({@link TextRun}), for the page stream and each form XObject drawn
 * exactly once (a form drawn twice is left out). A {@link PDFTextStripper} pass counts operators per current stream; Type 3
 * glyph procedures are not a stream of their own. Read-only.
 */
public final class TextRuns {

  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");

  private TextRuns() {}

  /** Runs per content stream (page first, then forms in first-draw order); list index = ordinal. */
  public static Map<PDContentStream, List<TextRun>> of(PDPage page) throws IOException {
    Collector collector = new Collector(page);
    collector.setStartPage(0); // processPage alone (no getText): the stripper's page counter stays 0
    collector.processPage(page);
    Map<PDContentStream, List<TextRun>> out = new LinkedHashMap<>();
    for (Recorder recorder : collector.order) {
      if (!collector.repeated.contains(recorder.stream)) {
        List<TextRun> runs = new ArrayList<>(recorder.runs.size());
        for (RunBuilder builder : recorder.runs) {
          runs.add(builder.build());
        }
        out.put(recorder.stream, List.copyOf(runs));
      }
    }
    return out;
  }

  /** Font name contains bold/black/heavy/semibold/demi, the descriptor forces bold, or its weight is at least 600. */
  static boolean isBold(PDFont font) {
    if (font == null) {
      return false;
    }
    String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
    if (name.contains("bold") || name.contains("black") || name.contains("heavy") || name.contains("semibold") || name.contains("demi")) {
      return true;
    }
    PDFontDescriptor descriptor = font.getFontDescriptor();
    return descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
  }

  private static final class Recorder {
    final PDContentStream stream;
    final List<RunBuilder> runs = new ArrayList<>();

    Recorder(PDContentStream stream) {
      this.stream = stream;
    }
  }

  private static final class RunBuilder {
    final int ordinal;
    final StringBuilder text = new StringBuilder();
    float x0 = Float.NaN;
    float baseline = Float.NaN;
    float x1 = Float.NaN;
    float fontSize = Float.NaN;
    boolean bold;
    String fontName = "";

    RunBuilder(int ordinal) {
      this.ordinal = ordinal;
    }

    void add(float x, float y, float width, float size, PDFont font, String unicode) {
      if (Float.isNaN(x0)) {
        x0 = x;
        x1 = x + width;
        baseline = y;
        fontSize = size;
        bold = isBold(font);
        fontName = font == null || font.getName() == null ? "" : font.getName();
      } else {
        x0 = Math.min(x0, x);
        x1 = Math.max(x1, x + width);
      }
      if (unicode != null) {
        text.append(unicode);
      }
    }

    TextRun build() {
      return new TextRun(ordinal, text.toString(), x0, baseline, x1, Float.isNaN(baseline) ? Float.NaN : baseline + fontSize, fontSize, bold, fontName);
    }
  }

  private static final class Collector extends PDFTextStripper {
    /** COS object of each recorded stream → its recorder (identity); {@link #order} keeps first-draw order. */
    final Map<COSBase, Recorder> recorders = new IdentityHashMap<>();
    final List<Recorder> order = new ArrayList<>();
    final Set<PDContentStream> repeated = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Current stream: a recorder, or null inside content that is not one item stream (Type 3 glyphs). */
    private final Deque<Recorder> current = new ArrayDeque<>();
    private final float offsetX;
    private final float offsetY;
    private RunBuilder run;
    private int textDepth;

    Collector(PDPage page) throws IOException {
      PDRectangle crop = page.getCropBox();
      // The stripper translates positions by the crop box origin; runs are in user space like the walker's items.
      offsetX = crop.getLowerLeftX();
      offsetY = crop.getLowerLeftY();
      Recorder pageRecorder = new Recorder(page);
      recorders.put(page.getCOSObject(), pageRecorder);
      order.add(pageRecorder);
      current.push(pageRecorder);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      boolean counts = textDepth == 0 && TEXT_SHOWING.contains(operator.getName());
      RunBuilder previous = run;
      if (counts) {
        Recorder recorder = current.peek();
        run = recorder == null ? null : new RunBuilder(recorder.runs.size());
        if (run != null) {
          recorder.runs.add(run);
        }
        textDepth++;
      }
      try {
        super.processOperator(operator, operands);
      } finally {
        if (counts) {
          textDepth--;
          run = previous;
        }
      }
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
      inStream(form, () -> super.showForm(form));
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
      inStream(form, () -> super.showTransparencyGroup(form));
    }

    @Override
    protected void processType3Stream(PDType3CharProc charProc, Matrix textRenderingMatrix) throws IOException {
      RunBuilder saved = run;
      int savedDepth = textDepth;
      Recorder marker = new Recorder(null);
      current.push(marker);
      try {
        run = null;
        textDepth = 1; // nothing inside a glyph procedure is an operator of an item stream
        super.processType3Stream(charProc, textRenderingMatrix);
      } finally {
        current.remove(marker);
        run = saved;
        textDepth = savedDepth;
      }
    }

    private interface Body {
      void run() throws IOException;
    }

    private void inStream(PDFormXObject form, Body body) throws IOException {
      COSBase key = form.getCOSObject();
      Recorder recorder = recorders.get(key);
      if (recorder == null) {
        recorder = new Recorder(form);
        recorders.put(key, recorder);
        order.add(recorder);
      } else {
        repeated.add(recorder.stream); // drawn twice on this page: not one item stream in the walk
      }
      RunBuilder saved = run;
      int savedDepth = textDepth;
      current.push(recorder);
      try {
        run = null;
        textDepth = 0;
        body.run();
      } finally {
        current.pop();
        run = saved;
        textDepth = savedDepth;
      }
    }

    @Override
    protected void processTextPosition(TextPosition text) {
      if (run == null) {
        return;
      }
      Matrix m = text.getTextMatrix();
      run.add(m.getTranslateX() + offsetX, m.getTranslateY() + offsetY, text.getWidthDirAdj(), text.getFontSizeInPt(), text.getFont(), text.getUnicode());
    }

    @Override
    protected void writePage() {
      // positions only: nothing is written
    }
  }
}
