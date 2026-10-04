package com.koldera.pdf.fonts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.contentstream.operator.text.BeginText;
import org.apache.pdfbox.contentstream.operator.text.EndText;
import org.apache.pdfbox.contentstream.operator.text.MoveText;
import org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading;
import org.apache.pdfbox.contentstream.operator.text.NextLine;
import org.apache.pdfbox.contentstream.operator.text.SetCharSpacing;
import org.apache.pdfbox.contentstream.operator.text.SetFontAndSize;
import org.apache.pdfbox.contentstream.operator.text.SetTextHorizontalScaling;
import org.apache.pdfbox.contentstream.operator.text.SetTextLeading;
import org.apache.pdfbox.contentstream.operator.text.SetTextRenderingMode;
import org.apache.pdfbox.contentstream.operator.text.SetTextRise;
import org.apache.pdfbox.contentstream.operator.text.SetWordSpacing;
import org.apache.pdfbox.contentstream.operator.text.ShowText;
import org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLine;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Which fonts the document shows text with, and how: every page's content (form XObjects inline),
 * then the normal appearance of each of the page's annotations. One {@link Use} per font dictionary
 * (identity), in order of first use. Type 3 glyph procedures are not entered.
 */
public final class FontUses extends PDFStreamEngine {

  /**
   * One font's use: code to occurrence count, 1-based pages, whether glyphs were shown visibly, invisibly,
   * or in an annotation's normal appearance.
   */
  public record Use(PDFont font, SortedMap<Integer, Integer> codes, SortedSet<Integer> pages, boolean visible, boolean invisible, boolean inAppearance) {}

  private static final class Builder {
    final PDFont font;
    final TreeMap<Integer, Integer> codes = new TreeMap<>();
    final TreeSet<Integer> pages = new TreeSet<>();
    boolean visible;
    boolean invisible;
    boolean inAppearance;

    Builder(PDFont font) {
      this.font = font;
    }

    Use build() {
      return new Use(font, Collections.unmodifiableSortedMap(codes), Collections.unmodifiableSortedSet(pages), visible, invisible, inAppearance);
    }
  }

  private final Map<COSDictionary, Builder> uses = new IdentityHashMap<>();
  private final List<Builder> order = new ArrayList<>();
  private int pageNumber;
  private boolean inAnnotation;

  private FontUses() {
    addOperator(new BeginText(this));
    addOperator(new Concatenate(this));
    addOperator(new DrawObject(this));
    addOperator(new EndText(this));
    addOperator(new SetGraphicsStateParameters(this));
    addOperator(new Save(this));
    addOperator(new Restore(this));
    addOperator(new NextLine(this));
    addOperator(new SetCharSpacing(this));
    addOperator(new MoveText(this));
    addOperator(new MoveTextSetLeading(this));
    addOperator(new SetFontAndSize(this));
    addOperator(new ShowText(this));
    addOperator(new ShowTextAdjusted(this));
    addOperator(new SetTextLeading(this));
    addOperator(new SetMatrix(this));
    addOperator(new SetTextRenderingMode(this));
    addOperator(new SetTextRise(this));
    addOperator(new SetWordSpacing(this));
    addOperator(new SetTextHorizontalScaling(this));
    addOperator(new ShowTextLine(this));
    addOperator(new ShowTextLineAndSpace(this));
  }

  /** Every font the document's pages and annotation appearances show glyphs with. */
  public static List<Use> collect(PDDocument document) throws IOException {
    FontUses engine = new FontUses();
    int number = 0;
    for (PDPage page : document.getPages()) {
      number++;
      engine.pageNumber = number;
      engine.processPage(page);
      for (PDAnnotation annotation : page.getAnnotations()) {
        engine.inAnnotation = true;
        try {
          engine.showAnnotation(annotation);
        } finally {
          engine.inAnnotation = false;
        }
      }
    }
    return engine.order.stream().map(Builder::build).toList();
  }

  @Override
  protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) {
    Builder use = uses.computeIfAbsent(font.getCOSObject(), key -> {
      Builder created = new Builder(font);
      order.add(created);
      return created;
    });
    use.codes.merge(code, 1, Integer::sum);
    use.pages.add(pageNumber);
    if (getGraphicsState().getTextState().getRenderingMode() == RenderingMode.NEITHER) {
      use.invisible = true;
    } else {
      use.visible = true;
    }
    use.inAppearance |= inAnnotation;
  }
}
