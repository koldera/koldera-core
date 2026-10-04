package com.koldera.pdf.repair;

import com.koldera.pdf.generated.ShownText;
import com.koldera.pdf.generated.StructTree;
import com.koldera.pdf.structure.RenderIdentity;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * In an upload, a {@code Figure} without alternative text is presentation, as an HTML {@code <img>} without {@code alt}:
 * its marked content becomes an artifact and the element leaves the tree. Kept: a Figure with non-blank {@code /Alt} or
 * {@code /ActualText}, an {@code OBJR} below it, or any ambiguous content (nesting, forms, shared MCIDs, unbalanced streams,
 * unknown streams); a Figure showing text becomes {@code P}. Marker-only; checked on a scratch copy ({@link RenderIdentity}) first.
 */
public final class FigureArtifacts {

  private static final COSName MCR = COSName.getPDFName("MCR");
  private static final COSName OBJR = COSName.getPDFName("OBJR");
  private static final COSName STM = COSName.getPDFName("Stm");
  private static final String FIGURE = "Figure";
  private static final int MAX_FORM_DEPTH = 16;
  private static final int MAX_FORM_VISITS = 100_000;
  private static final Set<String> TEXT_SHOWING = Set.of("Tj", "TJ", "'", "\"");

  private FigureArtifacts() {}

  /**
   * Counts: figures marked as artifact per 1-based page, kept with /Alt or /ActualText, retagged {@code P} (content shows
   * text), kept with an annotation, and kept for other reasons (they hold or sit in other tagged content).
   */
  public record Outcome(SortedMap<Integer, Integer> artifactedPerPage, int keptWithAlt, int retaggedAsParagraph, int keptWithAnnotation, int keptOther) {
    public int artifacted() {
      return artifactedPerPage.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Figures without alternative text that were found at all (changed or kept). */
    public int withoutAlt() {
      return artifacted() + retaggedAsParagraph + keptWithAnnotation + keptOther;
    }
  }

  /** The step would change what a reader sees (or could not prove otherwise); nothing was changed. */
  public static final class Refused extends IOException {
    public Refused(String message) {
      super(message);
    }
  }

  /**
   * Decides and applies. {@link Refused} (the analysis failed, or the proof on a scratch copy found a
   * difference) means the document is unchanged; any other exception comes from the change itself,
   * after which the document may be partly changed.
   */
  public static Outcome apply(PDDocument doc) throws IOException {
    Plan plan;
    try {
      plan = analyze(doc);
    } catch (IOException | RuntimeException e) {
      throw new Refused("the content could not be analysed (" + e.getClass().getSimpleName() + ")");
    }
    if (!plan.toArtifact.isEmpty()) {
      String changed = RenderIdentity.changes(doc, plan.touchedPages(), scratch -> execute(analyze(scratch)));
      if (changed != null) {
        throw new Refused("marking them as artifacts would change " + changed);
      }
    }
    try {
      execute(plan);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return plan.outcome();
  }

  // ---- analysis ------------------------------------------------------------------------------

  /** An MCID in one content stream (page dictionary or form stream), compared by the owner's identity. */
  private record Key(COSBase owner, int mcid) {
    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && k.owner == owner && k.mcid == mcid;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(owner) * 31 + mcid;
    }
  }

  /** One marked-content sequence of a stream: tokens {@code operandStart..open} are its opening operator with operands, {@code close} its EMC. */
  private static final class Seq {
    final int operandStart;
    final int open;
    int close = -1;
    final int mcid;
    final Seq parent;
    final List<Seq> children = new ArrayList<>();
    boolean text;
    final List<StreamScan> forms = new ArrayList<>();

    Seq(int operandStart, int open, int mcid, Seq parent) {
      this.operandStart = operandStart;
      this.open = open;
      this.mcid = mcid;
      this.parent = parent;
    }
  }

  private static final class StreamScan {
    final COSBase owner;
    final PDPage page; // set for a page's own stream
    final PDFormXObject form; // set for a form
    final List<Object> tokens;
    final List<Seq> seqs = new ArrayList<>();
    final List<Draw> draws = new ArrayList<>();
    final Map<Integer, List<Seq>> byMcid = new LinkedHashMap<>();
    boolean balanced = true;
    boolean text;
    // forms only, filled while walking draws from the pages
    int draws_ = 0;
    boolean drawnInsideTagged;
    final Set<Integer> pages = new TreeSet<>();

    StreamScan(COSBase owner, PDPage page, PDFormXObject form, List<Object> tokens) {
      this.owner = owner;
      this.page = page;
      this.form = form;
      this.tokens = tokens;
    }
  }

  private record Draw(StreamScan form, List<Seq> open) {}

  /** What to do with one sequence: replace its opening by /Artifact BMC, or drop its opening and its EMC. */
  private record Edit(Seq seq, boolean remove) {}

  private static final class Plan {
    final PDDocument doc;
    final Map<COSBase, StreamScan> scans = new IdentityHashMap<>();
    final List<StreamScan> pageScans = new ArrayList<>();
    final List<PDStructureElement> toArtifact = new ArrayList<>();
    final List<PDStructureNode> parents = new ArrayList<>();
    final List<PDStructureElement> toParagraph = new ArrayList<>();
    final Map<StreamScan, List<Edit>> edits = new LinkedHashMap<>();
    final SortedMap<Integer, Integer> perPage = new TreeMap<>();
    int keptWithAlt;
    int keptWithAnnotation;
    int keptOther;
    int formVisits;

    Plan(PDDocument doc) {
      this.doc = doc;
    }

    Outcome outcome() {
      return new Outcome(Collections.unmodifiableSortedMap(new TreeMap<>(perPage)), keptWithAlt, toParagraph.size(), keptWithAnnotation, keptOther);
    }

    List<Integer> touchedPages() {
      Set<Integer> pages = new TreeSet<>();
      for (StreamScan scan : edits.keySet()) {
        if (scan.page != null) {
          pages.add(pageNumber(scan));
        } else {
          pages.addAll(scan.pages);
        }
      }
      return List.copyOf(pages);
    }

    private int pageNumber(StreamScan scan) {
      return pageScans.indexOf(scan) + 1;
    }
  }

  /** One element of the tree: its direct MCIDs (with the page they count on), and whether it holds an OBJR directly. */
  private static final class Node {
    final PDStructureElement element;
    final List<Key> mcids = new ArrayList<>();
    final List<COSDictionary> mcidPages = new ArrayList<>();
    final List<Node> kids = new ArrayList<>();
    boolean objr;
    /** An MCID whose content stream could not be named (integer MCID without a /Pg, MCR without /Stm or /Pg). */
    boolean unresolved;
    int listed;

    Node(PDStructureElement element) {
      this.element = element;
    }
  }

  private static Plan analyze(PDDocument doc) throws IOException {
    Plan plan = new Plan(doc);
    PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
    if (root == null) {
      return plan;
    }
    Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
    int n = 0;
    for (PDPage page : doc.getPages()) {
      n++;
      pageNumbers.put(page.getCOSObject(), n);
      List<Object> tokens = page.hasContents() ? new PDFStreamParser(page).parse() : List.of();
      StreamScan scan = scan(new StreamScan(page.getCOSObject(), page, null, tokens), page.getResources(), plan, 0);
      plan.scans.put(page.getCOSObject(), scan);
      plan.pageScans.add(scan);
    }
    for (int p = 0; p < plan.pageScans.size(); p++) {
      walkDraws(plan.pageScans.get(p), p + 1, false, 0, plan, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // The tree: every element once, in pre-order, with the number of times it is listed as a kid.
    COSDictionary roleMap = root.getCOSObject().getDictionaryObject(COSName.ROLE_MAP) instanceof COSDictionary m ? m : null;
    Map<COSDictionary, Node> nodes = new IdentityHashMap<>();
    List<Node> preorder = new ArrayList<>();
    for (Object kid : root.getKids()) {
      if (kid instanceof PDStructureElement element) {
        visit(element, null, nodes, preorder, 0);
      }
    }

    Set<Node> gone = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Node node : preorder) {
      if (gone.contains(node) || !isFigure(node.element.getStructureType(), roleMap)) {
        continue;
      }
      if (hasAlternative(node.element)) {
        plan.keptWithAlt++;
        continue;
      }
      List<Node> subtree = new ArrayList<>();
      subtree(node, subtree, Collections.newSetFromMap(new IdentityHashMap<>()));
      if (subtree.stream().anyMatch(s -> s.objr)) {
        plan.keptWithAnnotation++;
        continue;
      }
      Set<Key> own = new LinkedHashSet<>(); // subtree pre-order, then /K order: deterministic
      subtree.forEach(s -> own.addAll(s.mcids));
      List<Seq> sequences = new ArrayList<>();
      List<StreamScan> streams = new ArrayList<>();
      boolean missing = collect(own, plan, sequences, streams);
      if (showsText(sequences, plan)) {
        if (node.kids.isEmpty()) {
          plan.toParagraph.add(node.element);
        } else {
          plan.keptOther++;
        }
        continue;
      }
      Integer page = pageOf(node, sequences, streams, plan, pageNumbers);
      PDStructureNode parent = parentOf(node.element, root);
      // Never a silent removal: every MCID must name its stream and be found there.
      boolean kept = missing || subtree.stream().anyMatch(s -> s.unresolved) || page == null || parent == null || !listedOnce(parent, node.element) || subtree.stream().anyMatch(s -> s.listed > 1)
          || subtree.stream().anyMatch(s -> s != node && hasAlternative(s.element));
      for (int i = 0; i < sequences.size() && !kept; i++) {
        kept = entangled(sequences.get(i), streams.get(i), own, plan);
      }
      if (kept) {
        plan.keptOther++;
        continue;
      }
      for (int i = 0; i < sequences.size(); i++) {
        Seq seq = sequences.get(i);
        boolean nested = false;
        for (Seq up = seq.parent; up != null && !nested; up = up.parent) {
          nested = up.mcid >= 0 && own.contains(new Key(streams.get(i).owner, up.mcid));
        }
        plan.edits.computeIfAbsent(streams.get(i), k -> new ArrayList<>()).add(new Edit(seq, nested));
      }
      plan.toArtifact.add(node.element);
      plan.parents.add(parent);
      plan.perPage.merge(page, 1, Integer::sum);
      // Figures nested inside it leave with it: each is counted on its own page (the outer one's when it has none).
      for (Node inner : subtree) {
        if (inner != node && isFigure(inner.element.getStructureType(), roleMap)) {
          List<Seq> innerSequences = new ArrayList<>();
          List<StreamScan> innerStreams = new ArrayList<>();
          collect(new LinkedHashSet<>(inner.mcids), plan, innerSequences, innerStreams);
          Integer innerPage = pageOf(inner, innerSequences, innerStreams, plan, pageNumbers);
          plan.perPage.merge(innerPage != null ? innerPage : page, 1, Integer::sum);
        }
      }
      gone.addAll(subtree);
    }
    return plan;
  }

  /** The sequences of these MCIDs in the scanned streams, in key order; true when an MCID has no sequence at all. */
  private static boolean collect(Set<Key> keys, Plan plan, List<Seq> sequences, List<StreamScan> streams) {
    boolean missing = false;
    for (Key key : keys) {
      StreamScan scan = plan.scans.get(key.owner);
      List<Seq> found = scan == null ? List.of() : scan.byMcid.getOrDefault(key.mcid, List.of());
      missing |= found.isEmpty();
      for (Seq seq : found) {
        sequences.add(seq);
        streams.add(scan);
      }
    }
    return missing;
  }

  private static Node visit(PDStructureElement element, COSDictionary inheritedPage, Map<COSDictionary, Node> nodes, List<Node> preorder, int depth) {
    Node existing = nodes.get(element.getCOSObject());
    if (existing != null) {
      existing.listed++;
      return null;
    }
    if (depth > 256) {
      return null;
    }
    Node node = new Node(element);
    node.listed = 1;
    nodes.put(element.getCOSObject(), node);
    preorder.add(node);
    COSDictionary dict = element.getCOSObject();
    COSDictionary page = dict.getDictionaryObject(COSName.PG) instanceof COSDictionary pg ? pg : inheritedPage;
    for (COSBase entry : StructTree.entries(dict)) {
      COSBase resolved = StructTree.resolve(entry);
      if (resolved instanceof COSInteger mcid) {
        if (page != null) {
          node.mcids.add(new Key(page, mcid.intValue()));
          node.mcidPages.add(page);
        } else {
          node.unresolved = true;
        }
      } else if (resolved instanceof COSDictionary kid && OBJR.equals(kid.getCOSName(COSName.TYPE))) {
        node.objr = true;
      } else if (resolved instanceof COSDictionary kid && MCR.equals(kid.getCOSName(COSName.TYPE))) {
        COSDictionary mcrPage = kid.getDictionaryObject(COSName.PG) instanceof COSDictionary pg ? pg : page;
        COSBase owner = kid.getDictionaryObject(STM) instanceof COSStream stm ? stm : mcrPage;
        if (owner != null) {
          node.mcids.add(new Key(owner, kid.getInt(COSName.MCID)));
          node.mcidPages.add(kid.getDictionaryObject(COSName.PG) instanceof COSDictionary pg ? pg : null);
        } else {
          node.unresolved = true;
        }
      } else if (resolved instanceof COSDictionary kid) {
        Node child = visit(new PDStructureElement(kid), page, nodes, preorder, depth + 1);
        if (child != null) {
          node.kids.add(child);
        } else if (nodes.get(kid) != null) {
          node.kids.add(nodes.get(kid));
        }
      }
    }
    return node;
  }

  private static void subtree(Node node, List<Node> out, Set<Node> seen) {
    if (seen.add(node)) {
      out.add(node);
      for (Node kid : node.kids) {
        subtree(kid, out, seen);
      }
    }
  }

  /** The element's type is Figure, or the /RoleMap maps it to Figure (following at most 16 steps). */
  static boolean isFigure(String type, COSDictionary roleMap) {
    String current = type;
    for (int i = 0; i < 16 && current != null; i++) {
      if (FIGURE.equals(current)) {
        return true;
      }
      current = roleMap == null ? null : roleMap.getDictionaryObject(COSName.getPDFName(current)) instanceof COSName mapped ? mapped.getName() : null;
    }
    return false;
  }

  private static boolean hasAlternative(PDStructureElement element) {
    String alt = element.getAlternateDescription();
    String actual = element.getActualText();
    return (alt != null && !alt.strip().isEmpty()) || (actual != null && !actual.strip().isEmpty());
  }

  private static PDStructureNode parentOf(PDStructureElement element, PDStructureTreeRoot root) {
    if (!(element.getCOSObject().getDictionaryObject(COSName.P) instanceof COSDictionary parent)) {
      return null;
    }
    if (parent == root.getCOSObject()) {
      return root;
    }
    return new PDStructureElement(parent);
  }

  /** Whether the element appears exactly once in its parent's /K (what {@link StructTree#remove} requires). */
  private static boolean listedOnce(PDStructureNode parent, PDStructureElement element) {
    int n = 0;
    for (COSBase entry : StructTree.entries(parent.getCOSObject())) {
      if (StructTree.resolve(entry) == element.getCOSObject()) {
        n++;
      }
    }
    return n == 1;
  }

  /** Whether any of these sequences, or a form drawn inside one of them, shows text. */
  private static boolean showsText(List<Seq> sequences, Plan plan) {
    for (Seq seq : sequences) {
      if (seq.text) {
        return true;
      }
      for (StreamScan form : seq.forms) {
        if (formShowsText(form, plan, 0, Collections.newSetFromMap(new IdentityHashMap<>()))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean formShowsText(StreamScan form, Plan plan, int depth, Set<StreamScan> path) {
    if (form.text) {
      return true;
    }
    if (depth > MAX_FORM_DEPTH || !path.add(form)) {
      return true; // cannot tell: never hide it
    }
    for (Draw draw : form.draws) {
      if (formShowsText(draw.form(), plan, depth + 1, path)) {
        return true;
      }
    }
    path.remove(form);
    return false;
  }

  /** Every MCID in a form and the forms it draws; null when the nesting cannot be followed. */
  private static List<Key> formMcids(StreamScan form, int depth, Set<StreamScan> path) {
    if (depth > MAX_FORM_DEPTH || !path.add(form)) {
      return null;
    }
    List<Key> out = new ArrayList<>();
    for (Integer mcid : form.byMcid.keySet()) {
      out.add(new Key(form.owner, mcid));
    }
    for (Draw draw : form.draws) {
      List<Key> nested = formMcids(draw.form(), depth + 1, path);
      if (nested == null) {
        return null;
      }
      out.addAll(nested);
    }
    path.remove(form);
    return out;
  }

  /** Rule 4: the sequence holds or sits in marked content that is not the figure's own, or cannot be rewritten safely. */
  private static boolean entangled(Seq seq, StreamScan stream, Set<Key> own, Plan plan) {
    if (!stream.balanced || seq.close < 0) {
      return true;
    }
    for (Seq up = seq.parent; up != null; up = up.parent) {
      if (up.mcid >= 0 && !own.contains(new Key(stream.owner, up.mcid))) {
        return true;
      }
    }
    if (holdsForeign(seq, stream, own)) {
      return true;
    }
    for (StreamScan form : seq.forms) {
      List<Key> inside = formMcids(form, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
      if (inside == null || !own.containsAll(inside)) {
        return true;
      }
    }
    if (stream.form != null) {
      if (stream.drawnInsideTagged || stream.draws_ == 0) {
        return true;
      }
      if (stream.draws_ > 1) {
        for (Integer mcid : stream.byMcid.keySet()) {
          if (!own.contains(new Key(stream.owner, mcid))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean holdsForeign(Seq seq, StreamScan stream, Set<Key> own) {
    for (Seq child : seq.children) {
      if (child.mcid >= 0 && !own.contains(new Key(stream.owner, child.mcid))) {
        return true;
      }
      if (holdsForeign(child, stream, own)) {
        return true;
      }
    }
    return false;
  }

  /** /Pg of the element, else of an MCR, else the page whose stream (or whose drawn form) holds the first sequence; null if none. */
  private static Integer pageOf(Node node, List<Seq> sequences, List<StreamScan> streams, Plan plan, Map<COSDictionary, Integer> pageNumbers) {
    if (node.element.getCOSObject().getDictionaryObject(COSName.PG) instanceof COSDictionary pg && pageNumbers.containsKey(pg)) {
      return pageNumbers.get(pg);
    }
    for (COSDictionary pg : node.mcidPages) {
      if (pg != null && pageNumbers.containsKey(pg)) {
        return pageNumbers.get(pg);
      }
    }
    for (StreamScan stream : streams) {
      if (stream.page != null) {
        return plan.pageScans.indexOf(stream) + 1;
      }
      if (!stream.pages.isEmpty()) {
        return stream.pages.iterator().next();
      }
    }
    return null;
  }

  /** Parses the marked-content sequences of one stream; forms drawn are scanned (once each) and recorded as draws. */
  private static StreamScan scan(StreamScan scan, PDResources resources, Plan plan, int depth) throws IOException {
    List<Object> tokens = scan.tokens;
    List<Seq> open = new ArrayList<>();
    List<COSBase> operands = new ArrayList<>();
    int operandStart = 0;
    COSName font = null;
    List<COSName> savedFonts = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      Object token = tokens.get(i);
      if (!(token instanceof Operator op)) {
        operands.add((COSBase) token);
        continue;
      }
      String name = op.getName();
      switch (name) {
        case "BMC", "BDC" -> {
          int mcid = "BDC".equals(name) ? mcid(operands, resources) : -1;
          Seq seq = new Seq(operandStart, i, mcid, open.isEmpty() ? null : open.get(open.size() - 1));
          if (seq.parent != null) {
            seq.parent.children.add(seq);
          }
          scan.seqs.add(seq);
          if (mcid >= 0) {
            scan.byMcid.computeIfAbsent(mcid, k -> new ArrayList<>()).add(seq);
          }
          open.add(seq);
        }
        case "EMC" -> {
          if (open.isEmpty()) {
            scan.balanced = false;
          } else {
            open.remove(open.size() - 1).close = i;
          }
        }
        case "q" -> savedFonts.add(font);
        case "Q" -> font = savedFonts.isEmpty() ? font : savedFonts.remove(savedFonts.size() - 1);
        case "Tf" -> font = !operands.isEmpty() && operands.get(0) instanceof COSName fontName ? fontName : null;
        case "Do" -> {
          if (operands.size() == 1 && operands.get(0) instanceof COSName xName && resources != null) {
            PDXObject xObject = resources.getXObject(xName);
            if (xObject instanceof PDFormXObject form) {
              StreamScan formScan = plan.scans.get(form.getCOSObject());
              if (formScan == null && depth >= MAX_FORM_DEPTH) {
                // Runaway nesting: the form is not read; it counts as showing text and as not rewritable.
                formScan = new StreamScan(form.getCOSObject(), null, form, List.of());
                formScan.text = true;
                formScan.balanced = false;
                formScan.drawnInsideTagged = true;
                plan.scans.put(form.getCOSObject(), formScan);
              } else if (formScan == null) {
                formScan = new StreamScan(form.getCOSObject(), null, form, new PDFStreamParser(form).parse());
                plan.scans.put(form.getCOSObject(), formScan);
                scan(formScan, form.getResources() != null ? form.getResources() : resources, plan, depth + 1);
              }
              scan.draws.add(new Draw(formScan, List.copyOf(open)));
              for (Seq seq : open) {
                seq.forms.add(formScan);
              }
            }
          }
        }
        default -> { }
      }
      if (TEXT_SHOWING.contains(name)) {
        String shown = ShownText.decode(resources, font, operands);
        if (shown == null || !shown.isBlank()) {
          scan.text = true;
          for (Seq seq : open) {
            seq.text = true;
          }
        }
      }
      operands.clear();
      operandStart = i + 1;
    }
    if (!open.isEmpty()) {
      scan.balanced = false;
    }
    return scan;
  }

  /** Walks the draws reachable from a page: counts every actual draw of each form and whether it happens inside tagged content. */
  private static void walkDraws(StreamScan stream, int page, boolean insideTagged, int depth, Plan plan, Set<StreamScan> path) throws IOException {
    if (!path.add(stream)) {
      return;
    }
    for (Draw draw : stream.draws) {
      StreamScan form = draw.form();
      if (++plan.formVisits > MAX_FORM_VISITS || depth >= MAX_FORM_DEPTH || path.contains(form)) {
        form.drawnInsideTagged = true; // a cycle or runaway nesting: leave its content alone
        continue;
      }
      form.draws_++;
      form.pages.add(page);
      boolean tagged = insideTagged || draw.open().stream().anyMatch(s -> s.mcid >= 0);
      form.drawnInsideTagged |= tagged;
      walkDraws(form, page, tagged, depth + 1, plan, path);
    }
    path.remove(stream);
  }

  private static int mcid(List<COSBase> operands, PDResources resources) {
    if (operands.size() < 2) {
      return -1;
    }
    COSBase properties = operands.get(1);
    COSDictionary dict = null;
    if (properties instanceof COSDictionary d) {
      dict = d;
    } else if (properties instanceof COSName named && resources != null) {
      PDPropertyList list = resources.getProperties(named);
      dict = list == null ? null : list.getCOSObject();
    }
    return dict == null ? -1 : dict.getInt(COSName.MCID, -1);
  }

  // ---- execution -----------------------------------------------------------------------------

  private static void execute(Plan plan) throws IOException {
    for (Map.Entry<StreamScan, List<Edit>> e : plan.edits.entrySet()) {
      StreamScan scan = e.getKey();
      List<Object> rewritten = rewrite(scan.tokens, e.getValue());
      if (scan.page != null) {
        PDStream replacement = new PDStream(plan.doc);
        try (OutputStream out = replacement.createOutputStream(COSName.FLATE_DECODE)) {
          new ContentStreamWriter(out).writeTokens(rewritten);
        }
        scan.page.setContents(replacement);
      } else {
        PDFormXObject form = scan.form;
        form.getCOSObject().removeItem(COSName.DECODE_PARMS);
        form.getCOSObject().removeItem(COSName.DP);
        form.getCOSObject().removeItem(COSName.FILTER);
        try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
          new ContentStreamWriter(out).writeTokens(rewritten);
        }
      }
    }
    for (int i = 0; i < plan.toArtifact.size(); i++) {
      if (!StructTree.remove(plan.parents.get(i), plan.toArtifact.get(i))) {
        throw new IllegalStateException("a figure could not be removed from the structure tree");
      }
      StructTree.removeEmptyAncestors(plan.parents.get(i));
    }
    for (PDStructureElement element : plan.toParagraph) {
      element.setStructureType("P");
    }
  }

  private static List<Object> rewrite(List<Object> tokens, List<Edit> edits) {
    Map<Integer, Edit> byOpen = new HashMap<>();
    Set<Integer> dropped = new HashSet<>();
    for (Edit edit : edits) {
      byOpen.put(edit.seq().operandStart, edit);
      for (int i = edit.seq().operandStart; i <= edit.seq().open; i++) {
        dropped.add(i);
      }
      if (edit.remove()) {
        dropped.add(edit.seq().close);
      }
    }
    List<Object> out = new ArrayList<>(tokens.size());
    for (int i = 0; i < tokens.size(); i++) {
      Edit edit = byOpen.get(i);
      if (edit != null && !edit.remove()) {
        out.add(COSName.ARTIFACT);
        out.add(Operator.getOperator("BMC"));
      }
      if (!dropped.contains(i)) {
        out.add(tokens.get(i));
      }
    }
    return out;
  }
}
