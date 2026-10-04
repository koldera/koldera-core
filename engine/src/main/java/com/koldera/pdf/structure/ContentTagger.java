package com.koldera.pdf.structure;

import com.koldera.pdf.structure.ContentWalker.FormCensus;
import com.koldera.pdf.structure.ContentWalker.Item;
import com.koldera.pdf.structure.ContentWalker.Kind;
import com.koldera.pdf.structure.ContentWalker.StreamPlan;
import com.koldera.pdf.structure.ContentWalker.WalkOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDLayoutAttributeObject;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Gives an untagged document a basic, deterministic tag structure from its content streams: {@code P} from consecutive text
 * lines, {@code Figure} for images, {@code Artifact} for paths, shadings and repeated forms, in content order under one {@code Document}. {@link #analyze} is read-only and fails closed; {@link #apply} only inserts
 * {@code BDC}/{@code EMC} wrappers and builds the tree. Existing MCIDs are adopted. A {@link Grouping} may assign leaves and
 * elements instead. Nothing infers headings, tables or alternative text.
 */
public final class ContentTagger {

  /** Content-free refusal: the document is not one the tagger can wrap without guessing. */
  public static final class UnsupportedContentException extends Exception {
    public UnsupportedContentException(String reason) {
      super(reason);
    }
  }

  /** Element counts written into the action reason; never a statement about accessibility. */
  public record Summary(int pages, int paragraphs, int figures, int artifacts, int mcids, int strayPaints, int sharedForms, int sharedFormsAsArtifact) {
    public String describe() {
      return "tagged "
          + paragraphs
          + " paragraphs, "
          + figures
          + " figures, "
          + artifacts
          + " artifacts on "
          + pages
          + " pages"
          + notes()
          + "; headings, tables, alternative text and reading order need human review";
    }

    /** The stray-paint and shared-form notes of {@link #describe()} (each with its leading {@code "; "}), or empty. */
    public String notes() {
      return (strayPaints > 0 ? "; removed " + strayPaints + " painting operators that had no path to paint (no visible change, verified by rendering)" : "")
          + (sharedForms > 0 ? "; shared forms " + sharedForms + " (page furniture " + sharedFormsAsArtifact + ")" : "");
    }
  }

  /** A layout grouping: decides per page which items form which structure leaves, and which are artifacts. */
  public interface Grouping {
    /** One assignment per page, in page order. Must be deterministic. */
    List<PageAssignment> assign(PDDocument document, List<PageItems> pages) throws IOException, UnsupportedContentException;
  }

  /** The walker's item kinds as a grouping sees them. */
  public enum ItemKind { TEXT, FIGURE, PATH, EXISTING_ARTIFACT, FORM_ONCE, FORM_SHARED }

  /**
   * One walked item, read-only: its content-order {@code index}, {@code stream}, text ordinal and count, position and size or
   * bounding box, whether it is {@code adopted} (already has MCIDs) and whether a shared form shows text.
   */
  public record ItemView(int index, ItemKind kind, PDContentStream stream, int textOrdinal, float x, float y, float size,
      PDRectangle bbox, String band, boolean outsideCropBox, boolean adopted, String digest, boolean formText, int textCount) {}

  /** The items of one page (1-based {@code pageNumber}) in content order. */
  public record PageItems(int pageNumber, PDPage page, List<ItemView> items) {}

  /** leafOfItem: item index → leaf id; artifactOfItem: item index → artifact /Type name; items in neither are copied unchanged. */
  public record PageAssignment(Map<Integer, Integer> leafOfItem, Map<Integer, String> artifactOfItem, List<Node> roots) {}

  /** A leaf (leaf >= 0, kids empty) or a grouping element (leaf == -1). attributes: e.g. /A for TH /Scope, Figure /BBox. */
  public record Node(String type, int leaf, List<Node> kids, COSDictionary attributes) {
    public static Node leaf(String type, int leaf) {
      return new Node(type, leaf, List.of(), null);
    }

    public static Node group(String type, List<Node> kids) {
      return new Node(type, -1, List.copyOf(kids), null);
    }
  }

  /** Read-only analysis result: what {@link #apply} will write. */
  public static final class Plan {
    final List<PagePlan> pages = new ArrayList<>();
    Summary summary;

    public Summary summary() {
      return summary;
    }

    /** 1-based numbers of the pages whose own stream or once-drawn forms hold a painting operator with no path. */
    public List<Integer> pagesWithStrayPaints() {
      List<Integer> out = new ArrayList<>();
      for (int p = 0; p < pages.size(); p++) {
        if (pages.get(p).streams.stream().anyMatch(s -> !s.strayPaints.isEmpty())) {
          out.add(p + 1);
        }
      }
      return out;
    }
  }

  static final class PagePlan {
    final PDPage page;
    final List<StreamPlan> streams = new ArrayList<>();
    final List<Item> order = new ArrayList<>();
    final List<Element> elements = new ArrayList<>();
    final Map<Item, Decision> decisions = new IdentityHashMap<>();
    int mcidCount;
    /** Grouping path only: the roots the grouping assigned, and the element of each leaf id. */
    List<Node> roots;
    final Map<Integer, Element> leaves = new HashMap<>();

    PagePlan(PDPage page) {
      this.page = page;
    }
  }

  /** One marked-content reference: the stream that carries the MCID (page or once-drawn form) and the id. */
  record Mcid(StreamPlan stream, int id) {}

  /** A structure element to create: {@code P} or {@code Figure}, with its MCIDs in content order. */
  static final class Element {
    final String type;
    final List<Mcid> mcids = new ArrayList<>();
    final PDRectangle bbox;

    Element(String type, PDRectangle bbox) {
      this.type = type;
      this.bbox = bbox;
    }
  }

  enum Wrap {
    PARAGRAPH,
    /** A shared form's {@code Do} as its own {@code P}: one closed sequence around the draw, never joined to a run. */
    FORM_PARAGRAPH,
    FIGURE,
    ARTIFACT,
    NONE
  }

  /** What pass two emits for one item; {@code tag} (grouping path) names the BDC tag, null = the wrap's default. */
  record Decision(Wrap wrap, int mcid, int run, String artifactType, String tag) {
    Decision(Wrap wrap, int mcid, int run, String artifactType) {
      this(wrap, mcid, run, artifactType, null);
    }
  }

  private static final int PAGINATION_MIN_PAGES = 3;
  /** Two draws of one shared form are "at the same position" when x and y each differ by at most this (pt). */
  static final float SHARED_FORM_POSITION_TOLERANCE = 2f;
  /** A shared form drawn at the same position on at least this many pages is page furniture (a master-page item). */
  private static final int SHARED_FORM_MIN_PAGES = 2;
  private static final COSName TYPE_PAGINATION = COSName.getPDFName("Pagination");
  private static final COSName TYPE_LAYOUT = COSName.getPDFName("Layout");
  private static final Operator BDC = Operator.getOperator("BDC");
  private static final Operator EMC = Operator.getOperator("EMC");
  private static final Set<String> SECTION_BOUNDARIES = Set.of("BT", "ET", "q", "Q");

  private ContentTagger() {}

  /**
   * Parses every page (and once-drawn form) and decides the wrappers; does not modify the document.
   *
   * @throws UnsupportedContentException when a stream cannot be wrapped without guessing
   */
  public static Plan analyze(PDDocument document) throws IOException, UnsupportedContentException {
    return analyze(document, true);
  }

  /**
   * As {@link #analyze(PDDocument)}; with {@code removeStrayPaints} false (the conservative walk used by tests) a painting operator
   * with no path is tagged like any other path and a form drawn more than once with text or images refuses the document. With
   * {@code true} (mode {@code fix}) each draw of such a form is wrapped from the invoking stream: a {@code Pagination} artifact
   * when drawn at the same place on two pages, else a {@code P} or {@code Figure}; a shared form with marked content is refused.
   */
  public static Plan analyze(PDDocument document, boolean removeStrayPaints) throws IOException, UnsupportedContentException {
    return analyze(document, removeStrayPaints, null);
  }

  /**
   * As {@link #analyze(PDDocument, boolean)}; with a non-null {@code grouping} the leaves, elements and artifacts are the
   * grouping's. Consecutive text items of a leaf in one stream without a {@code BT/ET/q/Q} boundary share one MCID; every other
   * run, figure, path or form draw gets its own. An inconsistent assignment is a programming error.
   */
  public static Plan analyze(PDDocument document, boolean removeStrayPaints, Grouping grouping) throws IOException, UnsupportedContentException {
    Plan plan = new Plan();
    FormCensus census = new FormCensus();
    for (PDPage page : document.getPages()) {
      census.count(page);
    }
    for (PDPage page : document.getPages()) {
      PagePlan pagePlan = new PagePlan(page);
      plan.pages.add(pagePlan);
      new ContentWalker(census, page.getCropBox(), pagePlan.order, pagePlan.streams, new WalkOptions(removeStrayPaints, removeStrayPaints)).walkPage(page);
    }
    if (grouping != null) {
      return analyzeGrouped(document, plan, grouping);
    }
    Map<String, Set<Integer>> bandPages = new HashMap<>();
    // Every draw of each shared form: {page, x, y}, in document order.
    Map<String, List<float[]>> sharedDraws = new HashMap<>();
    for (int p = 0; p < plan.pages.size(); p++) {
      for (Item item : plan.pages.get(p).order) {
        if (item.band != null && (item.kind == Kind.TEXT || item.kind == Kind.PATH)) {
          bandPages.computeIfAbsent(item.band + "|" + item.digest, k -> new HashSet<>()).add(p);
        }
        if (item.kind == Kind.FORM_SHARED) {
          sharedDraws.computeIfAbsent(item.formKey, k -> new ArrayList<>()).add(new float[] {p, item.x, item.y});
        }
      }
    }
    int paragraphs = 0;
    int figures = 0;
    int artifacts = 0;
    int mcids = 0;
    int strayPaints = 0;
    int sharedForms = 0;
    int sharedFormsAsArtifact = 0;
    for (int p = 0; p < plan.pages.size(); p++) {
      PagePlan pagePlan = plan.pages.get(p);
      for (StreamPlan stream : pagePlan.streams) {
        strayPaints += stream.strayPaints.size();
      }
      decide(pagePlan, bandPages, furniture(pagePlan, p, sharedDraws));
      for (Item item : pagePlan.order) {
        if (item.kind == Kind.FORM_SHARED) {
          sharedForms++;
          if (pagePlan.decisions.get(item).wrap() == Wrap.ARTIFACT) {
            sharedFormsAsArtifact++;
          }
        }
      }
      for (Element element : pagePlan.elements) {
        if (StandardStructureTypes.P.equals(element.type)) {
          paragraphs++;
        } else {
          figures++;
        }
      }
      for (Decision decision : pagePlan.decisions.values()) {
        if (decision.wrap() == Wrap.ARTIFACT) {
          artifacts++;
        }
      }
      mcids += pagePlan.mcidCount;
    }
    plan.summary = new Summary(plan.pages.size(), paragraphs, figures, artifacts, mcids, strayPaints, sharedForms, sharedFormsAsArtifact);
    return plan;
  }

  private static Plan analyzeGrouped(PDDocument document, Plan plan, Grouping grouping) throws IOException, UnsupportedContentException {
    List<PageItems> views = new ArrayList<>();
    for (int p = 0; p < plan.pages.size(); p++) {
      PagePlan pagePlan = plan.pages.get(p);
      List<ItemView> items = new ArrayList<>(pagePlan.order.size());
      for (int i = 0; i < pagePlan.order.size(); i++) {
        items.add(view(i, pagePlan.order.get(i)));
      }
      views.add(new PageItems(p + 1, pagePlan.page, List.copyOf(items)));
    }
    List<PageAssignment> assignments = grouping.assign(document, List.copyOf(views));
    if (assignments == null || assignments.size() != plan.pages.size()) {
      throw new IllegalStateException("a grouping must assign every page");
    }
    int paragraphs = 0;
    int figures = 0;
    int artifacts = 0;
    int mcids = 0;
    int strayPaints = 0;
    int sharedForms = 0;
    int sharedFormsAsArtifact = 0;
    for (int p = 0; p < plan.pages.size(); p++) {
      PagePlan pagePlan = plan.pages.get(p);
      for (StreamPlan stream : pagePlan.streams) {
        strayPaints += stream.strayPaints.size();
      }
      decideGrouped(pagePlan, assignments.get(p));
      for (Item item : pagePlan.order) {
        Decision decision = pagePlan.decisions.get(item);
        if (decision.wrap() == Wrap.ARTIFACT) {
          artifacts++;
        }
        if (item.kind == Kind.FORM_SHARED) {
          sharedForms++;
          if (decision.wrap() == Wrap.ARTIFACT) {
            sharedFormsAsArtifact++;
          }
        }
      }
      for (Element element : pagePlan.leaves.values()) {
        if (StandardStructureTypes.P.equals(element.type)) {
          paragraphs++;
        } else if (StandardStructureTypes.Figure.equals(element.type)) {
          figures++;
        }
      }
      mcids += pagePlan.mcidCount;
    }
    plan.summary = new Summary(plan.pages.size(), paragraphs, figures, artifacts, mcids, strayPaints, sharedForms, sharedFormsAsArtifact);
    return plan;
  }

  private static ItemView view(int index, Item item) {
    ItemKind kind = switch (item.kind) {
      case TEXT -> ItemKind.TEXT;
      case FIGURE -> ItemKind.FIGURE;
      case PATH -> ItemKind.PATH;
      case EXISTING_ARTIFACT -> ItemKind.EXISTING_ARTIFACT;
      case FORM_ONCE -> ItemKind.FORM_ONCE;
      case FORM_SHARED -> ItemKind.FORM_SHARED;
    };
    return new ItemView(index, kind, item.stream.owner, item.textOrdinal, item.x, item.y, item.size, item.bbox, item.band, item.outsideCropBox,
        !item.existingMcids.isEmpty(), item.digest, item.formText, item.textCount);
  }

  /** Leaf id → its node, depth first; fails on a leaf listed twice or a tree nested too deeply. */
  private static void collectLeaves(List<Node> nodes, Map<Integer, Node> out, int depth) {
    if (depth > 64) {
      throw new IllegalStateException("a grouping's tree is nested too deeply");
    }
    for (Node node : nodes) {
      if (node.leaf() >= 0) {
        if (!node.kids().isEmpty() || out.put(node.leaf(), node) != null) {
          throw new IllegalStateException("a grouping listed a leaf twice or gave it kids");
        }
      } else {
        collectLeaves(node.kids(), out, depth + 1);
      }
    }
  }

  private static void decideGrouped(PagePlan pagePlan, PageAssignment assignment) {
    Map<Integer, Node> leafNodes = new HashMap<>();
    collectLeaves(assignment.roots(), leafNodes, 0);
    int run = -1;
    Item previous = null;
    Integer previousLeaf = null;
    for (int i = 0; i < pagePlan.order.size(); i++) {
      Item item = pagePlan.order.get(i);
      Integer leaf = assignment.leafOfItem().get(i);
      String artifactType = assignment.artifactOfItem().get(i);
      boolean adopted = !item.existingMcids.isEmpty();
      if ((leaf != null || artifactType != null) && (item.kind == Kind.EXISTING_ARTIFACT || item.kind == Kind.FORM_ONCE)) {
        throw new IllegalStateException("a grouping assigned an existing artifact or a once-drawn form's Do");
      }
      if (leaf != null && artifactType != null) {
        throw new IllegalStateException("a grouping made an item both a leaf and an artifact");
      }
      Decision decision;
      if (artifactType != null) {
        if (adopted) {
          throw new IllegalStateException("a grouping made author-marked content an artifact");
        }
        decision = new Decision(Wrap.ARTIFACT, -1, -1, artifactType);
      } else if (leaf != null) {
        Node node = leafNodes.get(leaf);
        if (node == null) {
          throw new IllegalStateException("a grouping assigned an item to a leaf that is not in its tree");
        }
        Element element = pagePlan.leaves.computeIfAbsent(leaf, id -> new Element(node.type(), null));
        if (adopted) {
          for (int existing : item.existingMcids) {
            element.mcids.add(new Mcid(item.stream, existing));
          }
          item.stream.adoptedMcids += item.existingMcids.size();
          decision = new Decision(Wrap.NONE, -1, -1, null);
        } else if (item.kind == Kind.TEXT) {
          boolean continues = previous != null
              && previous.kind == Kind.TEXT
              && previous.existingMcids.isEmpty()
              && leaf.equals(previousLeaf)
              && previous.stream == item.stream
              && !item.boundaryBefore;
          if (continues) {
            decision = new Decision(Wrap.PARAGRAPH, pagePlan.decisions.get(previous).mcid(), run, null, node.type());
          } else {
            run++;
            Mcid mcid = new Mcid(item.stream, item.stream.allocateMcid());
            element.mcids.add(mcid);
            decision = new Decision(Wrap.PARAGRAPH, mcid.id(), run, null, node.type());
          }
        } else {
          Mcid mcid = new Mcid(item.stream, item.stream.allocateMcid());
          element.mcids.add(mcid);
          decision = new Decision(item.kind == Kind.FORM_SHARED ? Wrap.FORM_PARAGRAPH : Wrap.FIGURE, mcid.id(), -1, null, node.type());
        }
      } else {
        decision = new Decision(Wrap.NONE, -1, -1, null);
      }
      pagePlan.decisions.put(item, decision);
      previous = item;
      previousLeaf = leaf;
    }
    for (Integer leaf : leafNodes.keySet()) {
      Element element = pagePlan.leaves.get(leaf);
      if (element == null || element.mcids.isEmpty()) {
        throw new IllegalStateException("a grouping listed a leaf that holds no content");
      }
    }
    pagePlan.roots = List.copyOf(assignment.roots());
    for (StreamPlan stream : pagePlan.streams) {
      pagePlan.mcidCount += stream.mcidsInUse();
    }
  }

  /**
   * The shared-form draws that are page furniture under the basic rule, per page (item indexes): the
   * same form ({@code digest} up to its last {@code |}) drawn at the same position (x and y within
   * {@link #SHARED_FORM_POSITION_TOLERANCE}) on at least {@link #SHARED_FORM_MIN_PAGES} pages. For
   * groupings, so shared forms are judged exactly as the basic path judges them.
   */
  public static List<Set<Integer>> sharedFormFurniture(List<PageItems> pages) {
    Map<String, List<float[]>> draws = new HashMap<>();
    for (int p = 0; p < pages.size(); p++) {
      for (ItemView item : pages.get(p).items()) {
        if (item.kind() == ItemKind.FORM_SHARED) {
          draws.computeIfAbsent(formKey(item), k -> new ArrayList<>()).add(new float[] {p, item.x(), item.y()});
        }
      }
    }
    List<Set<Integer>> out = new ArrayList<>();
    for (int p = 0; p < pages.size(); p++) {
      Set<Integer> furniture = new java.util.TreeSet<>();
      for (ItemView item : pages.get(p).items()) {
        if (item.kind() != ItemKind.FORM_SHARED) {
          continue;
        }
        Set<Integer> on = new HashSet<>();
        for (float[] draw : draws.getOrDefault(formKey(item), List.of())) {
          if (Math.abs(draw[1] - item.x()) <= SHARED_FORM_POSITION_TOLERANCE && Math.abs(draw[2] - item.y()) <= SHARED_FORM_POSITION_TOLERANCE) {
            on.add((int) draw[0]);
          }
        }
        if (on.contains(p) && on.size() >= SHARED_FORM_MIN_PAGES) {
          furniture.add(item.index());
        }
      }
      out.add(furniture);
    }
    return out;
  }

  /** The digest of a path, text or form item repeated in the top/bottom band on at least this many pages is pagination (basic rule). */
  public static int paginationMinPages() {
    return PAGINATION_MIN_PAGES;
  }

  private static String formKey(ItemView item) {
    int bar = item.digest().lastIndexOf('|');
    return bar < 0 ? item.digest() : item.digest().substring(0, bar);
  }

  /**
   * The shared-form draws of page {@code p} that are page furniture: the same form object is drawn at
   * the same position (x and y within {@link #SHARED_FORM_POSITION_TOLERANCE}) on at least {@link
   * #SHARED_FORM_MIN_PAGES} pages, this one included.
   */
  private static Set<Item> furniture(PagePlan pagePlan, int p, Map<String, List<float[]>> sharedDraws) {
    Set<Item> furniture = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (Item item : pagePlan.order) {
      if (item.kind != Kind.FORM_SHARED) {
        continue;
      }
      Set<Integer> pages = new HashSet<>();
      for (float[] draw : sharedDraws.getOrDefault(item.formKey, List.of())) {
        if (Math.abs(draw[1] - item.x) <= SHARED_FORM_POSITION_TOLERANCE && Math.abs(draw[2] - item.y) <= SHARED_FORM_POSITION_TOLERANCE) {
          pages.add((int) draw[0]);
        }
      }
      if (pages.contains(p) && pages.size() >= SHARED_FORM_MIN_PAGES) {
        furniture.add(item);
      }
    }
    return furniture;
  }

  private static void decide(PagePlan pagePlan, Map<String, Set<Integer>> bandPages, Set<Item> sharedFurniture) {
    // 1. classify every item; collect the real text lines for paragraph grouping
    List<Item> textItems = new ArrayList<>();
    List<ParagraphGrouper.Line> lines = new ArrayList<>();
    Map<Item, String> artifactTypes = new IdentityHashMap<>();
    boolean figureSinceText = false;
    for (Item item : pagePlan.order) {
      boolean repeated = item.band != null && bandPages.getOrDefault(item.band + "|" + item.digest, Set.of()).size() >= PAGINATION_MIN_PAGES;
      switch (item.kind) {
        case TEXT -> {
          // Author-marked content (an existing MCID) is content wherever it sits: never an artifact.
          boolean adopted = !item.existingMcids.isEmpty();
          if (item.outsideCropBox && !adopted) {
            artifactTypes.put(item, TYPE_LAYOUT.getName());
          } else if (repeated && !adopted) {
            artifactTypes.put(item, TYPE_PAGINATION.getName());
          } else {
            textItems.add(item);
            lines.add(new ParagraphGrouper.Line(item.x, item.y, item.size, figureSinceText));
            figureSinceText = false;
          }
        }
        case PATH -> artifactTypes.put(item, repeated ? TYPE_PAGINATION.getName() : TYPE_LAYOUT.getName());
        case FIGURE -> figureSinceText = true;
        case FORM_SHARED -> {
          if (sharedFurniture.contains(item)) {
            artifactTypes.put(item, TYPE_PAGINATION.getName());
          } else {
            // its own element: the text around it never joins one paragraph across it
            figureSinceText = true;
          }
        }
        case EXISTING_ARTIFACT, FORM_ONCE -> {
          // copied unchanged
        }
      }
    }
    int[] paragraphOf = ParagraphGrouper.group(lines);
    Map<Item, Integer> paragraphIndex = new IdentityHashMap<>();
    for (int i = 0; i < textItems.size(); i++) {
      paragraphIndex.put(textItems.get(i), paragraphOf[i]);
    }

    // 2. runs, MCIDs and elements in content order; MCIDs are unique per content stream (page or form)
    int run = -1;
    Item previous = null;
    Item lastText = null;
    Element currentParagraph = null;
    int currentParagraphIndex = -1;
    for (Item item : pagePlan.order) {
      Decision decision;
      Integer paragraph = paragraphIndex.get(item);
      if (paragraph != null) {
        boolean adopted = !item.existingMcids.isEmpty();
        boolean continues =
            lastText != null
                && previous == lastText
                && lastText.stream == item.stream
                && paragraph.equals(paragraphIndex.get(lastText))
                && !item.boundaryBefore
                && !adopted
                && lastText.existingMcids.isEmpty();
        if (adopted) {
          // Already a marked-content sequence: copied as is, its MCIDs join the paragraph's element.
          if (paragraph != currentParagraphIndex) {
            currentParagraph = new Element(StandardStructureTypes.P, null);
            currentParagraphIndex = paragraph;
            pagePlan.elements.add(currentParagraph);
          }
          for (int existing : item.existingMcids) {
            currentParagraph.mcids.add(new Mcid(item.stream, existing));
          }
          item.stream.adoptedMcids += item.existingMcids.size();
          decision = new Decision(Wrap.NONE, -1, -1, null);
        } else if (!continues) {
          run++;
          if (paragraph != currentParagraphIndex) {
            currentParagraph = new Element(StandardStructureTypes.P, null);
            currentParagraphIndex = paragraph;
            pagePlan.elements.add(currentParagraph);
          }
          Mcid mcid = new Mcid(item.stream, item.stream.allocateMcid());
          currentParagraph.mcids.add(mcid);
          decision = new Decision(Wrap.PARAGRAPH, mcid.id(), run, null);
        } else {
          decision = new Decision(Wrap.PARAGRAPH, pagePlan.decisions.get(lastText).mcid(), run, null);
        }
        lastText = item;
      } else if (item.kind == Kind.FIGURE) {
        Element figure = new Element(StandardStructureTypes.Figure, item.bbox);
        pagePlan.elements.add(figure);
        if (item.existingMcids.isEmpty()) {
          Mcid mcid = new Mcid(item.stream, item.stream.allocateMcid());
          figure.mcids.add(mcid);
          decision = new Decision(Wrap.FIGURE, mcid.id(), -1, null);
        } else {
          for (int existing : item.existingMcids) {
            figure.mcids.add(new Mcid(item.stream, existing));
          }
          item.stream.adoptedMcids += item.existingMcids.size();
          decision = new Decision(Wrap.NONE, -1, -1, null);
        }
      } else if (item.kind == Kind.FORM_SHARED && !artifactTypes.containsKey(item)) {
        // One draw of a shared form, wrapped as a unit from the invoking stream: its own P when the
        // form shows text (a paragraph break before and after it), otherwise a Figure with the form box.
        Element element = new Element(item.formText ? StandardStructureTypes.P : StandardStructureTypes.Figure, item.formText ? null : item.bbox);
        pagePlan.elements.add(element);
        Mcid mcid = new Mcid(item.stream, item.stream.allocateMcid());
        element.mcids.add(mcid);
        decision = new Decision(item.formText ? Wrap.FORM_PARAGRAPH : Wrap.FIGURE, mcid.id(), -1, null);
        if (item.formText) {
          currentParagraph = null;
          currentParagraphIndex = -1;
        }
      } else if (artifactTypes.containsKey(item)) {
        decision = new Decision(Wrap.ARTIFACT, -1, -1, artifactTypes.get(item));
      } else {
        decision = new Decision(Wrap.NONE, -1, -1, null);
      }
      pagePlan.decisions.put(item, decision);
      previous = item;
    }
    for (StreamPlan stream : pagePlan.streams) {
      pagePlan.mcidCount += stream.mcidsInUse();
    }
  }

  /** Rewrites the content streams and builds the structure tree as decided by {@code plan}. */
  public static Summary apply(PDDocument document, Plan plan) throws IOException {
    for (PagePlan pagePlan : plan.pages) {
      for (StreamPlan stream : pagePlan.streams) {
        List<Object> rewritten = rewrite(stream, pagePlan.decisions);
        if (stream.owner instanceof PDPage page) {
          PDStream replacement = new PDStream(document);
          try (OutputStream out = replacement.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(rewritten);
          }
          page.setContents(replacement);
        } else if (stream.owner instanceof PDFormXObject form) {
          // The form keeps its dictionary (BBox, Matrix, Resources, Group, ...) but the data is new:
          // drop the old filter parameters so a predictor or other DecodeParms cannot outlive them.
          form.getCOSObject().removeItem(COSName.DECODE_PARMS);
          form.getCOSObject().removeItem(COSName.DP);
          form.getCOSObject().removeItem(COSName.FILTER);
          try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(rewritten);
          }
        }
      }
    }
    buildTree(document, plan);
    return plan.summary;
  }

  static List<Object> rewrite(StreamPlan stream, Map<Item, Decision> decisions) {
    List<Object> tokens = stream.tokens;
    List<Object> out = new ArrayList<>(tokens.size() + stream.items.size() * 4);
    int next = 0;
    int openRun = -1;
    for (int i = 0; i < tokens.size(); i++) {
      if (next < stream.items.size() && stream.items.get(next).start == i) {
        Item item = stream.items.get(next++);
        Decision decision = decisions.get(item);
        switch (decision.wrap()) {
          case PARAGRAPH -> {
            if (openRun != decision.run()) {
              openRun = close(out, openRun);
              open(out, decision.tag() != null ? COSName.getPDFName(decision.tag()) : COSName.P, decision.mcid(), null);
              openRun = decision.run();
            }
            copy(stream, item.start, item.end, out);
          }
          case FORM_PARAGRAPH -> {
            openRun = close(out, openRun);
            open(out, decision.tag() != null ? COSName.getPDFName(decision.tag()) : COSName.P, decision.mcid(), null);
            copy(stream, item.start, item.end, out);
            out.add(EMC);
          }
          case FIGURE -> {
            openRun = close(out, openRun);
            open(out, COSName.getPDFName(decision.tag() != null ? decision.tag() : StandardStructureTypes.Figure), decision.mcid(), null);
            copy(stream, item.start, item.end, out);
            out.add(EMC);
          }
          case ARTIFACT -> {
            openRun = close(out, openRun);
            open(out, COSName.ARTIFACT, -1, decision.artifactType());
            copy(stream, item.start, item.end, out);
            out.add(EMC);
          }
          case NONE -> {
            openRun = close(out, openRun);
            copy(stream, item.start, item.end, out);
          }
        }
        i = item.end - 1;
        continue;
      }
      Object token = tokens.get(i);
      if (stream.strayPaints.contains(i)) {
        continue;
      }
      if (token instanceof Operator op && SECTION_BOUNDARIES.contains(op.getName())) {
        openRun = close(out, openRun);
      }
      out.add(token);
    }
    close(out, openRun);
    return out;
  }

  /** Tokens {@code [from, to)} of {@code stream} without the painting operators that have no path. */
  private static void copy(StreamPlan stream, int from, int to, List<Object> out) {
    if (stream.strayPaints.isEmpty()) {
      out.addAll(stream.tokens.subList(from, to));
      return;
    }
    for (int k = from; k < to; k++) {
      if (!stream.strayPaints.contains(k)) {
        out.add(stream.tokens.get(k));
      }
    }
  }

  private static void open(List<Object> out, COSName tag, int mcid, String artifactType) {
    COSDictionary properties = new COSDictionary();
    properties.setDirect(true);
    if (mcid >= 0) {
      properties.setInt(COSName.MCID, mcid);
    }
    if (artifactType != null) {
      properties.setItem(COSName.TYPE, COSName.getPDFName(artifactType));
    }
    out.add(tag);
    out.add(properties);
    out.add(BDC);
  }

  private static int close(List<Object> out, int openRun) {
    if (openRun >= 0) {
      out.add(EMC);
    }
    return -1;
  }

  private static void buildTree(PDDocument document, Plan plan) {
    PDDocumentCatalog catalog = document.getDocumentCatalog();
    PDStructureTreeRoot root = new PDStructureTreeRoot();
    PDStructureElement documentElement = new PDStructureElement(StandardStructureTypes.DOCUMENT, root);
    root.appendKid(documentElement);
    // One ParentTree key per content stream: the page's own stream (its /StructParents) and every
    // once-drawn form (the form's /StructParents, ISO 32000-1 14.7.4.4); MCIDs are scoped per stream.
    COSArray nums = new COSArray();
    Map<StreamPlan, COSArray> parentsByStream = new IdentityHashMap<>();
    int nextKey = 0;
    for (PagePlan pagePlan : plan.pages) {
      for (StreamPlan stream : pagePlan.streams) {
        COSArray parents = new COSArray();
        for (int m = 0; m < stream.mcidSlots(); m++) {
          parents.add(COSNull.NULL);
        }
        parentsByStream.put(stream, parents);
        int key = nextKey++;
        if (stream.owner instanceof PDPage page) {
          page.getCOSObject().setInt(COSName.STRUCT_PARENTS, key);
        } else if (stream.owner instanceof PDFormXObject form) {
          form.setStructParents(key);
        }
        nums.add(COSInteger.get(key));
        nums.add(parents);
      }
      if (pagePlan.roots != null) {
        for (Node node : pagePlan.roots) {
          appendNode(node, documentElement, pagePlan, parentsByStream);
        }
        continue;
      }
      for (Element element : pagePlan.elements) {
        PDStructureElement structure = new PDStructureElement(element.type, documentElement);
        structure.setPage(pagePlan.page);
        if (element.mcids.size() == 1) {
          structure.getCOSObject().setItem(COSName.K, kid(element.mcids.get(0), pagePlan.page));
        } else {
          COSArray kids = new COSArray();
          for (Mcid mcid : element.mcids) {
            kids.add(kid(mcid, pagePlan.page));
          }
          structure.getCOSObject().setItem(COSName.K, kids);
        }
        if (element.bbox != null) {
          PDLayoutAttributeObject layout = new PDLayoutAttributeObject();
          layout.setBBox(rounded(element.bbox));
          structure.getCOSObject().setItem(COSName.A, layout.getCOSObject());
        }
        documentElement.appendKid(structure);
        for (Mcid mcid : element.mcids) {
          parentsByStream.get(mcid.stream()).set(mcid.id(), structure.getCOSObject());
        }
      }
    }
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, nums);
    root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
    root.setParentTreeNextKey(nextKey);
    catalog.setStructureTreeRoot(root);
    PDMarkInfo markInfo = catalog.getMarkInfo();
    if (markInfo == null) {
      markInfo = new PDMarkInfo();
    }
    markInfo.setMarked(true);
    catalog.setMarkInfo(markInfo);
  }

  /** Grouping path: creates the element of {@code node} (a leaf with its MCIDs, or a grouping element) under {@code parent}. */
  private static void appendNode(Node node, PDStructureElement parent, PagePlan pagePlan, Map<StreamPlan, COSArray> parentsByStream) {
    PDStructureElement structure = new PDStructureElement(node.type(), parent);
    structure.setPage(pagePlan.page);
    if (node.attributes() != null) {
      structure.getCOSObject().setItem(COSName.A, node.attributes());
    }
    if (node.leaf() >= 0) {
      Element element = pagePlan.leaves.get(node.leaf());
      if (element.mcids.size() == 1) {
        structure.getCOSObject().setItem(COSName.K, kid(element.mcids.get(0), pagePlan.page));
      } else {
        COSArray kids = new COSArray();
        for (Mcid mcid : element.mcids) {
          kids.add(kid(mcid, pagePlan.page));
        }
        structure.getCOSObject().setItem(COSName.K, kids);
      }
      for (Mcid mcid : element.mcids) {
        parentsByStream.get(mcid.stream()).set(mcid.id(), structure.getCOSObject());
      }
    } else {
      for (Node kid : node.kids()) {
        appendNode(kid, structure, pagePlan, parentsByStream);
      }
    }
    parent.appendKid(structure);
  }

  /** A page MCID is a plain integer kid; an MCID inside a form needs an MCR naming the stream. */
  private static COSBase kid(Mcid mcid, PDPage page) {
    if (mcid.stream().owner instanceof PDFormXObject form) {
      COSDictionary mcr = new COSDictionary();
      mcr.setItem(COSName.TYPE, COSName.MCR);
      mcr.setItem(COSName.PG, page);
      mcr.setItem(COSName.getPDFName("Stm"), form);
      mcr.setInt(COSName.MCID, mcid.id());
      return mcr;
    }
    return COSInteger.get(mcid.id());
  }

  /** Two decimals: enough for a layout box, and the same bytes on every run. */
  private static PDRectangle rounded(PDRectangle box) {
    return new PDRectangle(round(box.getLowerLeftX()), round(box.getLowerLeftY()), round(box.getWidth()), round(box.getHeight()));
  }

  private static float round(float value) {
    return Math.round(value * 100f) / 100f;
  }
}
