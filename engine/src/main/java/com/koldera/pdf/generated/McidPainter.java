package com.koldera.pdf.generated;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Reports what each page-level MCID paints in default user space (images, glyph boxes, paths, shadings, each clipped).
 * Form XObject painting counts for the page MCID it is drawn in. Used by {@link FigureBBox} and the upload link tagger.
 */
public final class McidPainter extends PDFGraphicsStreamEngine {
  /** Receives what a page-level MCID paints, clipped to the current clip; {@code glyph} tells text from images, paths and shadings. */
  public interface Sink {
    void painted(int mcid, Rectangle2D visible, boolean glyph);
  }

  private final Sink sink;
  private final MarkedContentScope scope = new MarkedContentScope();
  private GeneralPath path = new GeneralPath();
  private int pendingClip = -1;

  private McidPainter(PDPage page, Sink sink) {
    super(page);
    this.sink = sink;
  }

  /** Walks the page's content and reports every painting inside a page-level MCID to {@code sink}. */
  public static void paint(PDPage page, Sink sink) throws IOException {
    new McidPainter(page, sink).processPage(page);
  }

  /** The union of everything each page-level MCID paints (what {@link FigureBBox} needs). */
  static Map<Integer, Rectangle2D> unions(PDPage page) throws IOException {
    Map<Integer, Rectangle2D> byMcid = new HashMap<>();
    paint(page, (mcid, visible, glyph) -> byMcid.merge(mcid, visible, Rectangle2D::createUnion));
    return byMcid;
  }

  private void record(Rectangle2D bounds) {
    record(bounds, false);
  }

  private void record(Rectangle2D bounds, boolean glyph) {
    Integer mcid = scope.current();
    if (mcid == null || bounds == null) {
      return;
    }
    Rectangle2D visible = bounds;
    Rectangle2D clip = getGraphicsState().getCurrentClippingPath().getBounds2D();
    if (clip != null) {
      visible = bounds.createIntersection(clip);
      if (visible.getWidth() < 0 || visible.getHeight() < 0) {
        return;
      }
    }
    sink.painted(mcid, visible, glyph);
  }

  @Override
  public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
    scope.begin(properties);
  }

  @Override
  public void endMarkedContentSequence() {
    scope.end();
  }

  @Override
  public void showForm(PDFormXObject form) throws IOException {
    scope.enterForm();
    try {
      super.showForm(form);
    } finally {
      scope.exitForm();
    }
  }

  @Override
  public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
    scope.enterForm();
    try {
      super.showTransparencyGroup(form);
    } finally {
      scope.exitForm();
    }
  }

  @Override
  public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    path.moveTo((float) p0.getX(), (float) p0.getY());
    path.lineTo((float) p1.getX(), (float) p1.getY());
    path.lineTo((float) p2.getX(), (float) p2.getY());
    path.lineTo((float) p3.getX(), (float) p3.getY());
    path.closePath();
  }

  @Override
  public void drawImage(PDImage image) {
    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
    record(ctm.createAffineTransform().createTransformedShape(new Rectangle2D.Float(0, 0, 1, 1)).getBounds2D());
  }

  @Override
  protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
    RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
    if (!mode.isFill() && !mode.isStroke()) {
      return;
    }
    BoundingBox fontBox = font.getBoundingBox();
    if (fontBox == null) {
      return;
    }
    Matrix fontMatrix = font.getFontMatrix();
    float width = font.getWidth(code);
    Rectangle2D glyph = new Rectangle2D.Float(0, fontBox.getLowerLeftY(), width, fontBox.getHeight());
    Matrix glyphToDevice = fontMatrix.multiply(textRenderingMatrix);
    record(glyphToDevice.createAffineTransform().createTransformedShape(glyph).getBounds2D(), true);
  }

  @Override
  public void clip(int windingRule) {
    pendingClip = windingRule;
  }

  @Override
  public void moveTo(float x, float y) {
    path.moveTo(x, y);
  }

  @Override
  public void lineTo(float x, float y) {
    path.lineTo(x, y);
  }

  @Override
  public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    path.curveTo(x1, y1, x2, y2, x3, y3);
  }

  @Override
  public Point2D getCurrentPoint() {
    return path.getCurrentPoint();
  }

  @Override
  public void closePath() {
    path.closePath();
  }

  @Override
  public void endPath() {
    finishPath(false);
  }

  @Override
  public void strokePath() {
    finishPath(true);
  }

  @Override
  public void fillPath(int windingRule) {
    finishPath(true);
  }

  @Override
  public void fillAndStrokePath(int windingRule) {
    finishPath(true);
  }

  @Override
  public void shadingFill(COSName shadingName) {
    record(getGraphicsState().getCurrentClippingPath().getBounds2D());
  }

  /** Paints (bounds recorded before the pending clip applies, as in PDF), then applies a pending clip and starts a new path. */
  private void finishPath(boolean paint) {
    if (paint && path.getCurrentPoint() != null) {
      record(path.getBounds2D());
    }
    if (pendingClip >= 0) {
      path.setWindingRule(pendingClip);
      getGraphicsState().intersectClippingPath(path);
      pendingClip = -1;
    }
    path = new GeneralPath();
  }
}
