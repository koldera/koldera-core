package com.koldera.pdf.generated;

import static com.koldera.pdf.generated.GeneratedStructure.elements;
import static com.koldera.pdf.generated.GeneratedStructure.kids;
import static com.koldera.pdf.generated.GeneratedStructure.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class FigureBBoxTest {

  private static final COSName LAYOUT = COSName.getPDFName("Layout");
  private static final COSName BBOX = COSName.getPDFName("BBox");
  private static final COSName PLACEMENT = COSName.getPDFName("Placement");

  @Test
  void everyFigureGetsALayoutBBoxMatchingWhatItPaints() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-figures.pdf")) {
      FigureNormalizer.normalize(doc);

      assertEquals(5, FigureBBox.apply(doc));

      try (PDDocument after = GeneratedStructure.roundTrip(doc)) {
        PDPage page = after.getPage(0);
        PDRectangle media = page.getMediaBox();
        Map<Integer, Rectangle2D> images = ImagePlacements.of(page);
        assertEquals(4, images.size(), "fixture has four PNG <img> (the SVG one is drawn as paths)");
        List<COSDictionary> figures = elements(after, "Figure");
        assertEquals(5, figures.size());
        List<Rectangle2D> boxes = new ArrayList<>();
        for (COSDictionary figure : figures) {
          Rectangle2D box = layoutBBox(figure);
          assertNotNull(box, "Figure without a Layout BBox: " + figure.getString(COSName.ALT));
          assertTrue(box.getWidth() > 0 && box.getHeight() > 0, "empty BBox " + box);
          assertTrue(box.getMinX() >= media.getLowerLeftX() && box.getMinY() >= media.getLowerLeftY()
              && box.getMaxX() <= media.getUpperRightX() && box.getMaxY() <= media.getUpperRightY(), "BBox " + box + " outside " + media);
          boxes.add(box);
          Rectangle2D expected = null;
          for (int mcid : figureMcids(figure)) {
            Rectangle2D image = images.get(mcid);
            if (image != null) {
              expected = expected == null ? image : expected.createUnion(image);
            }
          }
          if (expected != null) {
            assertWithinOnePoint(expected, box);
          }
        }
        // Raster figure (160x90 CSS px) and vector figure (120x60 CSS px) at 0.75 pt per px, same left edge.
        assertEquals(120, boxes.get(0).getWidth(), 1);
        assertEquals(67.5, boxes.get(0).getHeight(), 1);
        assertEquals(90, boxes.get(1).getWidth(), 1, "SVG figure width from its paths");
        assertEquals(45, boxes.get(1).getHeight(), 1, "SVG figure height from its paths");
        assertEquals(boxes.get(0).getMinX(), boxes.get(1).getMinX(), 1);
        // The two images of the former two-image figure are stacked, the inline logo is below them.
        assertTrue(boxes.get(3).getMaxY() <= boxes.get(2).getMinY() + 1, "second image below the first");
        assertTrue(boxes.get(4).getMaxY() < boxes.get(3).getMinY(), "inline image below the two images");
        // Divs (former figures that could not be collapsed) are not Figures and get no Layout BBox from this step.
        for (COSDictionary div : elements(after, "Div")) {
          assertNull(layoutBBox(div));
        }
      }
    }
  }

  @Test
  void anExistingNonLayoutAttributeIsKeptAndTheLayoutAttributeAppended() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-figures.pdf")) {
      FigureNormalizer.normalize(doc);
      COSDictionary inline = elements(doc, "Figure").get(4);
      COSDictionary other = new COSDictionary();
      other.setItem(COSName.O, COSName.getPDFName("XML-1.00"));
      other.setString(COSName.getPDFName("class"), "logo");
      inline.setItem(COSName.A, other);

      FigureBBox.apply(doc);

      COSArray attributes = (COSArray) resolve(inline.getItem(COSName.A));
      assertEquals(2, attributes.size());
      assertSame(other, resolve(attributes.get(0)));
      assertNotNull(layoutBBox(inline));
      assertEquals(0, FigureBBox.apply(doc), "a Figure that has a BBox is left alone");
    }
  }

  @Test
  void anExistingLayoutAttributeWithoutBBoxGetsTheBBoxAddedInPlace() throws IOException {
    try (PDDocument doc = GeneratedStructure.fixture("generated-figures.pdf")) {
      FigureNormalizer.normalize(doc);
      COSDictionary inline = elements(doc, "Figure").get(4);
      COSDictionary placement = new COSDictionary();
      placement.setItem(COSName.O, LAYOUT);
      placement.setItem(PLACEMENT, COSName.getPDFName("Inline"));
      inline.setItem(COSName.A, placement);
      COSDictionary first = elements(doc, "Figure").get(0);
      COSDictionary other = new COSDictionary();
      other.setItem(COSName.O, COSName.getPDFName("XML-1.00"));
      COSDictionary layoutInArray = new COSDictionary();
      layoutInArray.setItem(COSName.O, LAYOUT);
      layoutInArray.setItem(PLACEMENT, COSName.getPDFName("Block"));
      first.setItem(COSName.A, new COSArray(List.of(other, layoutInArray)));

      FigureBBox.apply(doc);

      assertSame(placement, resolve(inline.getItem(COSName.A)), "no second Layout object, /A stays the one dictionary");
      assertEquals(COSName.getPDFName("Inline"), placement.getDictionaryObject(PLACEMENT));
      assertTrue(placement.getDictionaryObject(BBOX) instanceof COSArray);
      COSArray firstAttributes = (COSArray) resolve(first.getItem(COSName.A));
      assertEquals(2, firstAttributes.size(), "the Layout dictionary in the array receives the BBox");
      assertTrue(layoutInArray.getDictionaryObject(BBOX) instanceof COSArray);
      assertNotNull(layoutBBox(first));
    }
  }

  @Test
  void figuresOnTwoPagesOrWithNothingPaintedGetNoBBox() throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage one = new PDPage();
      PDPage two = new PDPage();
      doc.addPage(one);
      doc.addPage(two);
      one.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      two.getCOSObject().setInt(COSName.STRUCT_PARENTS, 1);
      PDStructureTreeRoot root = new PDStructureTreeRoot();
      doc.getDocumentCatalog().setStructureTreeRoot(root);
      PDStructureElement document = new PDStructureElement("Document", root);
      root.appendKid(document);
      PDStructureElement spanning = new PDStructureElement("Figure", document);
      document.appendKid(spanning);
      spanning.setAlternateDescription("Across a page break");
      spanning.appendKid(mcr(one, 0));
      spanning.appendKid(mcr(two, 0));
      PDStructureElement empty = new PDStructureElement("Figure", document);
      document.appendKid(empty);
      empty.setAlternateDescription("Nothing painted");
      empty.setPage(one);
      empty.getCOSObject().setItem(COSName.K, COSInteger.get(1));

      assertEquals(0, FigureBBox.apply(doc));
      assertNull(spanning.getCOSObject().getDictionaryObject(COSName.A));
      assertNull(empty.getCOSObject().getDictionaryObject(COSName.A));
    }
  }

  private static PDMarkedContentReference mcr(PDPage page, int mcid) {
    PDMarkedContentReference ref = new PDMarkedContentReference();
    ref.setPage(page);
    ref.setMCID(mcid);
    return ref;
  }

  private static void assertWithinOnePoint(Rectangle2D expected, Rectangle2D actual) {
    assertEquals(expected.getMinX(), actual.getMinX(), 1, "llx of " + actual + " vs " + expected);
    assertEquals(expected.getMinY(), actual.getMinY(), 1, "lly of " + actual + " vs " + expected);
    assertEquals(expected.getMaxX(), actual.getMaxX(), 1, "urx of " + actual + " vs " + expected);
    assertEquals(expected.getMaxY(), actual.getMaxY(), 1, "ury of " + actual + " vs " + expected);
  }

  /** The /BBox of the element's Layout attribute (dictionary or array form), or null. */
  private static Rectangle2D layoutBBox(COSDictionary element) {
    COSBase a = resolve(element.getItem(COSName.A));
    List<COSDictionary> attributes = new ArrayList<>();
    if (a instanceof COSDictionary d) {
      attributes.add(d);
    } else if (a instanceof COSArray array) {
      for (COSBase entry : array) {
        if (resolve(entry) instanceof COSDictionary d) {
          attributes.add(d);
        }
      }
    }
    for (COSDictionary attribute : attributes) {
      if (LAYOUT.equals(attribute.getCOSName(COSName.O)) && attribute.getDictionaryObject(BBOX) instanceof COSArray box) {
        assertEquals(4, box.size());
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
          v[i] = ((COSNumber) resolve(box.get(i))).floatValue();
        }
        assertTrue(v[0] <= v[2] && v[1] <= v[3], "BBox corners in order");
        return new Rectangle2D.Double(v[0], v[1], v[2] - v[0], v[3] - v[1]);
      }
    }
    return null;
  }

  /** Integer MCIDs held by the Figure or its descendants, except those of a Caption. */
  private static List<Integer> figureMcids(COSDictionary figure) {
    List<Integer> out = new ArrayList<>();
    collectMcids(figure, out);
    return out;
  }

  private static void collectMcids(COSDictionary node, List<Integer> out) {
    for (COSBase kid : kids(node)) {
      if (kid instanceof COSInteger mcid) {
        out.add(mcid.intValue());
      } else if (GeneratedStructure.isElement(kid) && !"Caption".equals(((COSDictionary) kid).getNameAsString(COSName.S))) {
        collectMcids((COSDictionary) kid, out);
      }
    }
  }

  /** Independent oracle: where each image XObject is placed (CTM applied to the unit square), by the MCID it is drawn in. */
  static final class ImagePlacements extends PDFStreamEngine {
    private final Map<Integer, Rectangle2D> byMcid = new HashMap<>();
    private final Deque<Integer> mcids = new ArrayDeque<>();

    private ImagePlacements() {
      addOperator(new Concatenate(this));
      addOperator(new Save(this));
      addOperator(new Restore(this));
      addOperator(new BeginMarkedContentSequence(this));
      addOperator(new BeginMarkedContentSequenceWithProperties(this));
      addOperator(new EndMarkedContentSequence(this));
      addOperator(new OperatorProcessor(this) {
        @Override
        public void process(Operator operator, List<COSBase> operands) throws IOException {
          PDXObject xobject = getResources().getXObject((COSName) operands.get(0));
          if (xobject instanceof PDImageXObject && !mcids.isEmpty() && mcids.peek() >= 0) {
            Rectangle2D placed = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()
                .createTransformedShape(new Rectangle(0, 0, 1, 1)).getBounds2D();
            byMcid.merge(mcids.peek(), placed, Rectangle2D::createUnion);
          }
        }

        @Override
        public String getName() {
          return "Do";
        }
      });
    }

    static Map<Integer, Rectangle2D> of(PDPage page) throws IOException {
      ImagePlacements engine = new ImagePlacements();
      engine.processPage(page);
      assertFalse(engine.byMcid.isEmpty(), "no images found on the page");
      return engine.byMcid;
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
      mcids.push(properties != null && properties.containsKey(COSName.MCID) ? properties.getInt(COSName.MCID) : -1);
    }

    @Override
    public void endMarkedContentSequence() {
      mcids.poll();
    }
  }
}
