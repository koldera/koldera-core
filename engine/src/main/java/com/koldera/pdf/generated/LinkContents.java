package com.koldera.pdf.generated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Chromium's {@code Link} annotations carry no {@code /Contents}. For each {@code Link} element this fills the
 * annotations its OBJRs reference with: the text of the element's own MCIDs, else the {@code /Alt} of a Figure inside, else
 * {@code "Link to " + URI} or {@code "Link to page N"}. Non-blank existing {@code /Contents} are never touched.
 */
public final class LinkContents {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName STM = COSName.getPDFName("Stm");

  private LinkContents() {}

  /** Returns the number of annotations given a description. */
  public static int apply(PDDocument doc) throws IOException {
    PDDocumentCatalog catalog = doc.getDocumentCatalog();
    Map<COSDictionary, PDPage> pages = new IdentityHashMap<>();
    for (PDPage page : doc.getPages()) {
      pages.put(page.getCOSObject(), page);
    }
    Map<COSDictionary, Map<Integer, String>> textByPage = new IdentityHashMap<>();
    int changed = 0;
    for (PDStructureElement link : StructTree.elementsOfType(doc, "Link")) {
      Content content = new Content();
      collect(link.getCOSObject(), content, 0, StructTree.visitedSet());
      String description = normalizeWhitespace(textOf(doc, content, pages, textByPage));
      if (description.isEmpty()) {
        description = content.figureAlt;
      }
      for (COSDictionary target : content.objrTargets) {
        PDAnnotation annotation = PDAnnotation.createAnnotation(target);
        String existing = annotation.getContents();
        if (existing != null && !existing.isBlank()) {
          continue;
        }
        String value = isBlank(description) ? fallbackDescription(catalog, annotation) : description;
        if (!isBlank(value)) {
          annotation.setContents(value);
          changed++;
        }
      }
    }
    return changed;
  }

  private static final class Content {
    record PageMcid(COSDictionary page, int mcid) {}

    final List<PageMcid> mcids = new ArrayList<>();
    final List<COSDictionary> objrTargets = new ArrayList<>();
    String figureAlt;
  }

  /** Each kid element, MCR and OBJR is taken once, however often a hostile /K lists it. */
  private static void collect(COSDictionary node, Content content, int depth, Set<COSDictionary> visited) {
    if (depth > 256 || !visited.add(node)) {
      return;
    }
    COSBase page = node.getItem(COSName.PG);
    for (COSBase entry : StructTree.entries(node)) {
      COSBase kid = StructTree.resolve(entry);
      if (kid instanceof COSInteger mcid) {
        if (StructTree.resolve(page) instanceof COSDictionary pageDict) {
          content.mcids.add(new Content.PageMcid(pageDict, mcid.intValue()));
        }
      } else if (kid instanceof COSDictionary d && MCR.equals(d.getCOSName(COSName.TYPE))) {
        if (!d.containsKey(STM) && visited.add(d)) {
          COSBase owner = d.containsKey(COSName.PG) ? d.getItem(COSName.PG) : page;
          if (StructTree.resolve(owner) instanceof COSDictionary pageDict) {
            content.mcids.add(new Content.PageMcid(pageDict, d.getInt(COSName.MCID)));
          }
        }
      } else if (kid instanceof COSDictionary d && OBJR.equals(d.getCOSName(COSName.TYPE))) {
        if (visited.add(d) && StructTree.resolve(d.getDictionaryObject(COSName.OBJ)) instanceof COSDictionary target) {
          content.objrTargets.add(target);
        }
      } else if (kid instanceof COSDictionary d && d.containsKey(COSName.S)) {
        if (content.figureAlt == null && "Figure".equals(d.getNameAsString(COSName.S))) {
          String alt = d.getString(COSName.ALT);
          if (alt != null && !alt.isBlank()) {
            content.figureAlt = alt;
          }
        }
        collect(d, content, depth + 1, visited);
      }
    }
  }

  /** The element's own MCID text, page by page, in the order the MCIDs were collected. */
  private static String textOf(PDDocument doc, Content content, Map<COSDictionary, PDPage> pages, Map<COSDictionary, Map<Integer, String>> textByPage) throws IOException {
    StringBuilder out = new StringBuilder();
    for (Content.PageMcid pm : content.mcids) {
      Map<Integer, String> byMcid = textByPage.get(pm.page());
      if (byMcid == null) {
        PDPage page = pages.get(pm.page());
        if (page == null) {
          continue;
        }
        int pageNumber = doc.getPages().indexOf(page) + 1;
        byMcid = textByMcid(doc, pageNumber);
        textByPage.put(pm.page(), byMcid);
      }
      String text = byMcid.get(pm.mcid());
      if (text != null) {
        out.append(text);
      }
    }
    return out.toString();
  }

  /**
   * The text of every page-level MCID of page {@code pageNumber} (1-based), glyphs of one MCID joined as
   * {@link TextByMcid} joins them; also read by the inference benchmark ({@code StructureText}).
   */
  public static Map<Integer, String> textByMcid(PDDocument doc, int pageNumber) throws IOException {
    TextByMcid stripper = new TextByMcid();
    stripper.setStartPage(pageNumber);
    stripper.setEndPage(pageNumber);
    stripper.getText(doc);
    Map<Integer, String> out = new HashMap<>();
    stripper.byMcid.forEach((mcid, text) -> out.put(mcid, text.toString()));
    return out;
  }

  /**
   * {@code "Link to " + the annotation's URI}, or {@code "Link to page N"} when the annotation
   * points to a destination this document can resolve to a page; null when neither is available.
   */
  public static String fallbackDescription(PDDocumentCatalog catalog, PDAnnotation annotation) throws IOException {
    if (!(annotation instanceof PDAnnotationLink link)) {
      return null;
    }
    PDAction action = link.getAction();
    if (action instanceof PDActionURI uriAction) {
      String uri = uriAction.getURI();
      if (uri != null && !uri.isBlank()) {
        return "Link to " + uri;
      }
    }
    PDDestination destination = link.getDestination();
    if (destination == null && action instanceof PDActionGoTo goTo) {
      destination = goTo.getDestination();
    }
    Integer pageNumber = pageNumberOf(catalog, destination);
    return pageNumber != null ? "Link to page " + pageNumber : null;
  }

  /** 1-based page number of a destination, resolving a named destination through the catalog. */
  private static Integer pageNumberOf(PDDocumentCatalog catalog, PDDestination destination) throws IOException {
    PDPageDestination pageDestination = null;
    if (destination instanceof PDPageDestination pd) {
      pageDestination = pd;
    } else if (destination instanceof PDNamedDestination named) {
      pageDestination = catalog.findNamedDestinationPage(named);
    }
    if (pageDestination == null) {
      return null;
    }
    PDPage page = pageDestination.getPage();
    if (page == null) {
      return null;
    }
    int index = catalog.getPages().indexOf(page);
    return index >= 0 ? index + 1 : null;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String normalizeWhitespace(String s) {
    return s.strip().replaceAll("\\s+", " ");
  }

  /**
   * Text per page-level MCID, tracked through nested BDC/EMC and forms with a {@link MarkedContentScope}. A wrapped link is
   * one MCID over several lines, so glyphs are joined with a space when a new line starts or a gap exceeds a third of a space.
   */
  private static final class TextByMcid extends PDFTextStripper {
    private final Map<Integer, StringBuilder> byMcid = new HashMap<>();
    private final Map<Integer, TextPosition> lastByMcid = new HashMap<>();
    private final MarkedContentScope scope = new MarkedContentScope();

    private TextByMcid() throws IOException {
      super();
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
      super.beginMarkedContentSequence(tag, properties);
      scope.begin(properties);
    }

    @Override
    public void endMarkedContentSequence() {
      super.endMarkedContentSequence();
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
    protected void processTextPosition(TextPosition text) {
      super.processTextPosition(text);
      Integer mcid = scope.current();
      if (mcid == null) {
        return;
      }
      TextPosition last = lastByMcid.put(mcid, text);
      StringBuilder out = byMcid.computeIfAbsent(mcid, k -> new StringBuilder());
      if (last != null && startsNewWord(last, text)) {
        out.append(' ');
      }
      out.append(text.getUnicode());
    }

    /** A new line (baseline moved by more than half the glyph height), or a horizontal gap wider than about a third of a space. */
    private static boolean startsNewWord(TextPosition last, TextPosition current) {
      float height = Math.max(last.getHeightDir(), current.getHeightDir());
      if (height > 0 && Math.abs(current.getY() - last.getY()) > height / 2f) {
        return true;
      }
      float spaceWidth = last.getWidthOfSpace() > 0 ? last.getWidthOfSpace() : current.getWidthOfSpace();
      float gap = current.getX() - last.getEndX();
      return spaceWidth > 0 && gap > 0.3f * spaceWidth;
    }
  }
}
