package com.koldera.pdf.inference;

import com.koldera.pdf.rules.RoleMapRule;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Read-only, per upload: tags are kept ({@code tagged_usable}), discarded and rebuilt from content ({@code tagged_unusable}),
 * or absent ({@code untagged}). Unusable means too little text inside tags, one element holding most marked content, an
 * unmappable structure type, or an unwalkable tree.
 */
public final class StructureStrategy {

  public enum Kind {
    UNTAGGED,
    TAGGED_USABLE,
    TAGGED_UNUSABLE
  }

  /**
   * What was measured: glyphs shown inside an MCID sequence (tagged) and outside any MCID or
   * Artifact sequence (untagged); MCIDs referenced by the tree and the most held directly by one
   * element (MCRs counted); structure types that cannot be mapped; whether the tree could be walked.
   */
  public record Metrics(long taggedGlyphs, long untaggedGlyphs, int mcids, int largestElementMcids, List<String> unmappableTypes, boolean treeUnwalkable) {
    public Metrics {
      unmappableTypes = List.copyOf(unmappableTypes);
    }

    /** Tagged share of the counted glyphs; 1.0 when there are none. */
    public double coverage() {
      long all = taggedGlyphs + untaggedGlyphs;
      return all == 0 ? 1.0 : (double) taggedGlyphs / all;
    }
  }

  public record Decision(Kind kind, String reason, Metrics metrics) {}

  static final String UNTAGGED_REASON = "strategy untagged: no StructTreeRoot; structure is built from the page content";
  static final int MAX_TREE_DEPTH = 512;
  /** Forms drawn inside forms deeper than this are not followed (PDFBox itself stops at 50). */
  static final int MAX_FORM_NESTING = 64;

  private StructureStrategy() {}

  public static Decision decide(PDDocument doc, InferenceSettings s) throws IOException {
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return new Decision(Kind.UNTAGGED, UNTAGGED_REASON, new Metrics(0, 0, 0, 0, List.of(), false));
    }
    GlyphCounter counter = new GlyphCounter();
    for (PDPage page : doc.getPages()) {
      counter.count(page);
    }
    TreeWalk tree = new TreeWalk(root.getCOSObject());
    Metrics metrics = new Metrics(counter.tagged, counter.untagged, tree.mcids, tree.largest, tree.unmappable(), tree.unwalkable);

    List<String> failing = new ArrayList<>();
    String coverage = "tagged text coverage " + percent(metrics.coverage());
    if (metrics.coverage() < s.minTaggedTextCoverage()) {
      failing.add(coverage + " (< " + limit(s.minTaggedTextCoverage()) + " %)");
      coverage = null;
    } else {
      coverage += " (≥ " + limit(s.minTaggedTextCoverage()) + " %)";
    }
    double share = metrics.mcids() == 0 ? 0 : (double) metrics.largestElementMcids() / metrics.mcids();
    String largest;
    if (share > s.maxLargestElementShare() && metrics.mcids() >= s.largestElementMinMcids()) {
      failing.add("one element holds " + percent(share) + " of " + metrics.mcids() + " MCIDs (> " + limit(s.maxLargestElementShare()) + " %)");
      largest = null;
    } else if (share > s.maxLargestElementShare()) {
      largest = "largest element holds " + percent(share) + " of " + metrics.mcids() + " MCIDs (fewer than " + s.largestElementMinMcids() + " MCIDs, not assessed)";
    } else {
      largest = "largest element holds " + percent(share) + " of " + metrics.mcids() + " MCIDs (≤ " + limit(s.maxLargestElementShare()) + " %)";
    }
    if (!metrics.unmappableTypes().isEmpty()) {
      failing.add("structure types that cannot be mapped to standard types: " + String.join(", ", metrics.unmappableTypes()));
    }
    if (metrics.treeUnwalkable()) {
      failing.add("the structure tree cannot be walked");
    }
    if (!failing.isEmpty()) {
      return new Decision(
          Kind.TAGGED_UNUSABLE,
          "strategy tagged_unusable: " + String.join("; ", failing) + "; the existing tags are discarded on the copy and structure is built from the page content",
          metrics);
    }
    return new Decision(
        Kind.TAGGED_USABLE,
        "strategy tagged_usable: " + coverage + ", " + largest + ", no unmappable structure types; the existing tags are kept",
        metrics);
  }

  /**
   * Whether any page shows, inside an MCID-marked sequence (and not inside an Artifact one nested in
   * it), a glyph whose Unicode is not whitespace: text that assistive technology is given. A document
   * without a StructTreeRoot has none. Used for uploads by the PDF/UA identifier step.
   */
  public static boolean hasTaggedText(PDDocument doc) throws IOException {
    if (doc.getDocumentCatalog().getStructureTreeRoot() == null) {
      return false;
    }
    GlyphCounter counter = new GlyphCounter();
    for (PDPage page : doc.getPages()) {
      counter.count(page);
      if (counter.taggedVisible > 0) {
        return true;
      }
    }
    return false;
  }

  /** {@code 0.973} becomes {@code "97.3 %"}. */
  private static String percent(double share) {
    return String.format(Locale.ROOT, "%.1f %%", share * 100);
  }

  /** A setting as a percentage without a needless decimal: {@code 0.90} becomes {@code "90"}. */
  private static String limit(double share) {
    double p = Math.round(share * 1000) / 10.0;
    return p == Math.rint(p) ? Long.toString((long) p) : String.format(Locale.ROOT, "%.1f", p);
  }

  // ---- glyphs ------------------------------------------------------------------------------------

  /**
   * Counts every shown glyph by the innermost enclosing MCID or Artifact sequence: inside an MCID it
   * is tagged, inside an Artifact it is ignored, otherwise untagged. Form XObjects are walked
   * through {@link #showForm}, so a form drawn inside an MCID sequence counts as tagged.
   */
  static final class GlyphCounter extends PDFGraphicsStreamEngine {
    private enum Mark {
      MCID,
      ARTIFACT,
      OTHER
    }

    private final Deque<Mark> marks = new ArrayDeque<>();
    /** Forms being drawn on the current path: a form that draws itself (directly or not) is followed once per path. */
    private final Set<COSBase> forms = Collections.newSetFromMap(new IdentityHashMap<>());
    long tagged;
    long untagged;
    /** Tagged glyphs whose Unicode is not whitespace (a glyph without a Unicode mapping counts). */
    long taggedVisible;

    GlyphCounter() {
      super(null);
    }

    void count(PDPage page) throws IOException {
      marks.clear();
      forms.clear();
      processPage(page);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
      if (forms.size() >= MAX_FORM_NESTING || !forms.add(form.getCOSObject())) {
        return;
      }
      try {
        super.showForm(form);
      } finally {
        forms.remove(form.getCOSObject());
      }
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
      if (forms.size() >= MAX_FORM_NESTING || !forms.add(group.getCOSObject())) {
        return;
      }
      try {
        super.showTransparencyGroup(group);
      } finally {
        forms.remove(group.getCOSObject());
      }
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
      if (COSName.ARTIFACT.equals(tag)) {
        marks.push(Mark.ARTIFACT);
      } else if (properties != null && properties.getDictionaryObject(COSName.MCID) instanceof COSNumber) {
        marks.push(Mark.MCID);
      } else {
        marks.push(Mark.OTHER);
      }
    }

    @Override
    public void endMarkedContentSequence() {
      if (!marks.isEmpty()) {
        marks.pop();
      }
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
      for (Mark mark : marks) {
        if (mark == Mark.MCID) {
          tagged++;
          String unicode = font.toUnicode(code);
          if (unicode == null || !unicode.isBlank()) {
            taggedVisible++;
          }
          return;
        }
        if (mark == Mark.ARTIFACT) {
          return;
        }
      }
      untagged++;
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}

    @Override
    public void drawImage(PDImage pdImage) {}

    @Override
    public void clip(int windingRule) {}

    @Override
    public void moveTo(float x, float y) {}

    @Override
    public void lineTo(float x, float y) {}

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}

    @Override
    public Point2D getCurrentPoint() {
      return new Point2D.Float();
    }

    @Override
    public void closePath() {}

    @Override
    public void endPath() {}

    @Override
    public void strokePath() {}

    @Override
    public void fillPath(int windingRule) {}

    @Override
    public void fillAndStrokePath(int windingRule) {}

    @Override
    public void shadingFill(COSName shadingName) {}
  }

  // ---- tree --------------------------------------------------------------------------------------

  /** One walk over the structure tree: MCIDs per element, types in use; cycle- and depth-safe. */
  private static final class TreeWalk {
    private static final COSName S = COSName.S;
    private final Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSDictionary> path = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSArray> arrayPath = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Arrays already walked anywhere: an array shared by several parents (a DAG) is walked once, not once per path. */
    private final Set<COSArray> arraysWalked = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> types = new TreeSet<>();
    private final COSDictionary roleMap;
    int mcids;
    int largest;
    boolean unwalkable;

    TreeWalk(COSDictionary root) {
      roleMap = root.getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary map ? map : null;
      path.add(root);
      kids(root.getDictionaryObject(COSName.K), 1, true);
    }

    /** Returns how many MCIDs {@code k} holds directly (integers and MCRs); elements are walked. */
    private int kids(COSBase k, int depth, boolean atRoot) {
      if (unwalkable) {
        return 0;
      }
      COSBase kid = k instanceof COSObject o ? o.getObject() : k;
      if (kid instanceof COSArray array) {
        // A /K array may (against the spec) hold arrays, and one may hold itself: count the nesting, refuse an array already on the path, walk each array once.
        if (depth > MAX_TREE_DEPTH || arrayPath.contains(array)) {
          unwalkable = true;
          return 0;
        }
        if (!arraysWalked.add(array)) {
          return 0;
        }
        arrayPath.add(array);
        int held = 0;
        for (int i = 0; i < array.size() && !unwalkable; i++) {
          COSBase item = array.get(i);
          COSBase resolved = item instanceof COSObject o ? o.getObject() : item;
          held += kids(item, resolved instanceof COSArray ? depth + 1 : depth, atRoot);
        }
        arrayPath.remove(array);
        return held;
      }
      if (kid instanceof COSNumber) {
        return atRoot ? 0 : 1;
      }
      if (!(kid instanceof COSDictionary dict)) {
        return 0;
      }
      if (dict.containsKey(S)) {
        element(dict, depth);
        return 0;
      }
      if (COSName.MCR.equals(dict.getCOSName(COSName.TYPE)) || dict.getDictionaryObject(COSName.MCID) instanceof COSNumber) {
        return atRoot ? 0 : 1;
      }
      return 0;
    }

    private void element(COSDictionary element, int depth) {
      if (depth > MAX_TREE_DEPTH || path.contains(element)) {
        unwalkable = true;
        return;
      }
      if (!visited.add(element)) {
        return;
      }
      COSName type = element.getCOSName(S);
      if (type != null) {
        types.add(type.getName());
      }
      path.add(element);
      int held = kids(element.getDictionaryObject(COSName.K), depth + 1, false);
      path.remove(element);
      mcids += held;
      largest = Math.max(largest, held);
    }

    /**
     * Types that are not standard, not (as used directly) a PDF 2.0 type the role-map rule maps, and not
     * role-mapped (transitively, cycle-safe) to a standard type. A chain that ends at a PDF 2.0 type does
     * not count: the role-map rule never completes an existing chain.
     */
    List<String> unmappable() {
      List<String> out = new ArrayList<>();
      for (String type : types) {
        if (!RoleMapRule.PDF_2_0_EQUIVALENTS.containsKey(type) && !mappable(type)) {
          out.add(type);
        }
      }
      return out;
    }

    private boolean mappable(String type) {
      Set<String> seen = new HashSet<>();
      String current = type;
      while (current != null && seen.add(current)) {
        if (RoleMapRule.STANDARD_TYPES.contains(current)) {
          return true;
        }
        COSName next = roleMap == null ? null : roleMap.getCOSName(COSName.getPDFName(current));
        current = next == null ? null : next.getName();
      }
      return false;
    }
  }
}
